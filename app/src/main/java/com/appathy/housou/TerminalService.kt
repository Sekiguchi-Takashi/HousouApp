package com.appathy.housou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * フロア端末側。常駐して
 *  - 自己アナウンス(UDP broadcast)
 *  - 制御コマンド受付(TCP)
 *  - 放送音声受信(UDP) / 通話送話(UDP)
 *  - AI読み上げ(TTS) / チャイム
 * を行う。
 */
class TerminalService : Service() {

    companion object {
        const val CH_ID = "housou_terminal"
        const val NOTI_ID = 1001

        @Volatile var running = false
        @Volatile var consoleIp = ""
        @Volatile var alertOn = false
        @Volatile var alertName = ""
        @Volatile var alertText = ""
        @Volatile var remoteOk = false

        // ---- 字幕
        @Volatile var captionText = ""
        @Volatile var captionUrgent = false
        @Volatile var captionSeq = 0

        /** 字幕を即座に消す */
        fun clearCaption() {
            captionSeq++
            captionText = ""
            captionUrgent = false
            push()
        }
        @Volatile var playing = false
        @Volatile var talking = false
        @Volatile var lastCmd = "-"
        @Volatile var lastCmdAt = 0L
        @Volatile var micOn = true
        @Volatile var spkOn = true

        var onUpdate: (() -> Unit)? = null

        fun push() {
            Handler(Looper.getMainLooper()).post {
                try { onUpdate?.invoke() } catch (e: Exception) { }
            }
        }
    }

    private lateinit var store: Store
    private var announceTh: Thread? = null
    private var ctrlTh: Thread? = null
    private var server: ServerSocket? = null
    private var receiver: Audio.Receiver? = null
    private var sender: Audio.Sender? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var watchTh: Thread? = null
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        alive = true
        running = true
        startForegroundSafe(false)
        acquireLocks()
        initTts()
        startAnnounce()
        startCtrlServer()
        startWatchdog()
        startRemoteRegister()
        store.log("system", "端末サービス起動 (${store.termFloor}F ${store.termName})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val boot = intent?.getBooleanExtra("boot", false) ?: false
        startForegroundSafe(boot)
        return START_STICKY
    }

    // ------------------------------------------------------------- 基盤
    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startForegroundSafe(fromBoot: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "放送端末", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "${store.termFloor}F ${store.termName} / ${Net.localIp()}"
        val n: Notification = Notification.Builder(this, CH_ID)
            .setContentTitle("放送端末 待機中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (!fromBoot && hasMic()) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTI_ID, n, type)
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (e: Exception) {
            try { startForeground(NOTI_ID, n) } catch (e2: Exception) { }
        }
    }

    /** 再入可能。既に保持していれば何もしない */
    @Synchronized
    private fun acquireLocks() {
        try {
            val cur = wake
            if (cur == null || !cur.isHeld) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "housou:term")
                w.acquire()
                wake = w
            }
        } catch (e: Exception) { }
        try {
            val cur = wifiLock
            if (cur == null || !cur.isHeld) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "housou:wifi")
                l.acquire()
                wifiLock = l
            }
        } catch (e: Exception) { }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale.JAPANESE
                    } catch (e: Exception) { }
                    ttsReady = true
                }
            }
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------- アナウンス
    private fun startAnnounce() {
        val t = Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.broadcast = true
            } catch (e: Exception) {
                return@Thread
            }
            while (alive) {
                try {
                    val payload = statusJson().toString().toByteArray(Charsets.UTF_8)
                    for (a in Net.broadcastAddresses()) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, a, Proto.PORT_ANNOUNCE))
                        } catch (e: Exception) { }
                    }
                } catch (e: Exception) { }
                try { Thread.sleep(3000) } catch (e: Exception) { }
            }
            try { sock.close() } catch (e: Exception) { }
        }
        announceTh = t
        t.start()
    }

    /**
     * 遠隔運用（VPN / 別サブネット）向けの登録送信。
     * UDPブロードキャストはルータを越えないため、コンソールのアドレスが
     * 設定されている場合はTCPで直接ステータスを送りつけて台帳に載せてもらう。
     */
    private fun startRemoteRegister() {
        Thread {
            while (alive) {
                val host = store.consoleHost.trim()
                if (host.isNotEmpty()) {
                    try {
                        val i = host.lastIndexOf(':')
                        val h = if (i > 0) host.substring(0, i) else host
                        val port = if (i > 0) {
                            host.substring(i + 1).toIntOrNull() ?: Proto.PORT_REG
                        } else {
                            Proto.PORT_REG
                        }
                        val sock = java.net.Socket()
                        sock.connect(java.net.InetSocketAddress(h, port), 4000)
                        sock.soTimeout = 4000
                        val w = sock.getOutputStream().bufferedWriter()
                        w.write(statusJson().toString())
                        w.write("\n")
                        w.flush()
                        try { sock.getInputStream().bufferedReader().readLine() } catch (e: Exception) { }
                        sock.close()
                        remoteOk = true
                    } catch (e: Exception) {
                        remoteOk = false
                    }
                } else {
                    remoteOk = false
                }
                var n = 0
                while (n < 100 && alive) {
                    try { Thread.sleep(100) } catch (e: Exception) { }
                    n++
                }
            }
        }.start()
    }

    // ------------------------------------------------------------- 自己復旧
    /**
     * 5秒ごとに自身を点検する。
     *  - 制御サーバが落ちていれば再起動
     *  - 放送受信中なのに音声が10秒来ていなければセッションを閉じる
     *  - WakeLockが外れていれば取り直す
     */
    private fun startWatchdog() {
        val t = Thread {
            while (alive) {
                try {
                    val ss = server
                    if (ss == null || ss.isClosed) {
                        store.log("system", "制御サーバを自動復旧しました")
                        startCtrlServer()
                    }
                    val rx = receiver
                    if (rx != null && playing) {
                        val last = rx.lastPacketAt
                        if (last > 0L && System.currentTimeMillis() - last > 10000) {
                            stopPlayback()
                            store.log("system", "音声が途絶したため受信を終了しました")
                        }
                    }
                    val w = wake
                    if (w == null || !w.isHeld) acquireLocks()
                } catch (e: Exception) { }
                var n = 0
                while (n < 50 && alive) {
                    try { Thread.sleep(100) } catch (e: Exception) { }
                    n++
                }
            }
        }
        watchTh = t
        t.start()
    }

    private fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("t", "announce")
        o.put("ok", true)
        o.put("id", store.deviceId)
        o.put("name", store.termName)
        o.put("floor", store.termFloor)
        o.put("group", store.termGroup)
        o.put("bldg", store.building)
        o.put("ip", Net.localIp())
        o.put("ver", Proto.APP_VER)
        o.put("batt", battery())
        o.put("rssi", rssi())
        o.put("vol", volumePercent())
        o.put("playing", playing)
        o.put("talking", talking)
        o.put("mic", micOn)
        o.put("spk", spkOn)
        o.put("route", store.route)
        o.put("caption", store.captionEnabled)
        o.put("route_name", Routing.status(this, store.route))
        return o
    }

    private fun battery(): Int {
        return try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (lv < 0) -1 else lv * 100 / sc
        } catch (e: Exception) {
            -1
        }
    }

    private fun rssi(): Int {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.connectionInfo.rssi
        } catch (e: Exception) {
            0
        }
    }

    private fun am(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volumePercent(): Int {
        return try {
            val a = am()
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) 0 else a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        } catch (e: Exception) {
            0
        }
    }

    private fun setVolumePercent(v: Int) {
        try {
            val a = am()
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = (max * v.coerceIn(0, 100) / 100).coerceIn(0, max)
            a.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------- 制御サーバ
    private fun startCtrlServer() {
        val t = Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Proto.PORT_CTRL))
                server = ss
                while (alive) {
                    val c = try {
                        ss.accept()
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    Thread { handle(c) }.start()
                }
            } catch (e: Exception) { }
        }
        ctrlTh = t
        t.start()
    }

    private fun handle(sk: Socket) {
        try {
            sk.soTimeout = 4000
            val r = BufferedReader(InputStreamReader(sk.getInputStream(), "UTF-8"))
            val line = r.readLine()
            val res: JSONObject = if (line == null) {
                JSONObject().put("ok", false)
            } else {
                val req = JSONObject(line)
                val from = sk.inetAddress?.hostAddress ?: ""
                exec(req, from)
            }
            val w = OutputStreamWriter(sk.getOutputStream(), "UTF-8")
            w.write(res.toString())
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            // ignore
        } finally {
            try { sk.close() } catch (e: Exception) { }
        }
    }

    private fun exec(req: JSONObject, from: String): JSONObject {
        val cmd = req.optString("cmd")
        lastCmd = cmd
        lastCmdAt = System.currentTimeMillis()
        if (from.isNotEmpty()) consoleIp = from

        when (cmd) {
            "ping", "status" -> {
                // no-op
            }

            "set_info" -> {
                if (req.has("name")) store.termName = req.optString("name")
                if (req.has("floor")) store.termFloor = req.optInt("floor", store.termFloor)
                if (req.has("group")) store.termGroup = req.optString("group")
                if (req.has("bldg")) store.building = req.optString("bldg")
                startForegroundSafe(false)
                store.log("system", "コンソールから端末情報を更新")
            }

            "volume" -> setVolumePercent(req.optInt("value", 50))

            "mic" -> micOn = req.optBoolean("on", true)

            "spk" -> {
                spkOn = req.optBoolean("on", true)
                if (!spkOn) stopPlayback()
            }

            "chime" -> {
                val urgent = req.optBoolean("urgent", false)
                if (spkOn) {
                    if (urgent) setVolumePercent(100)
                    Thread {
                        Audio.playBlob(
                            Proto.RATE_HIGH, Audio.chime(Proto.RATE_HIGH, urgent),
                            this@TerminalService, store.route
                        )
                    }.start()
                }
                store.log("broadcast", if (urgent) "緊急チャイム受信" else "チャイム受信")
            }

            "tts" -> {
                val text = req.optString("text")
                val urgent = req.optBoolean("urgent", false)
                if (spkOn && text.isNotEmpty()) {
                    if (urgent) setVolumePercent(100)
                    Thread {
                        if (req.optBoolean("chime", true)) {
                            Audio.playBlob(
                                Proto.RATE_HIGH, Audio.chime(Proto.RATE_HIGH, urgent),
                                this@TerminalService, store.route
                            )
                        }
                        speak(text, urgent)
                    }.start()
                }
                store.log("broadcast", "読み上げ: $text")
            }

            "caption" -> {
                store.captionEnabled = req.optBoolean("on", true)
                if (!store.captionEnabled) clearCaption()
                store.log("system", "字幕表示を" + (if (store.captionEnabled) "有効化" else "無効化"))
                push()
            }

            "route" -> {
                val m = req.optString("mode", Routing.AUTO)
                if (Routing.modes.contains(m)) {
                    store.route = m
                    store.log("system", "音声出力先を変更: " + Routing.label(m))
                    if (playing) {
                        stopPlayback()
                        startPlayback(Proto.RATE_HIGH)
                    }
                    push()
                }
            }

            "alert" -> {
                alertOn = req.optBoolean("on", false)
                if (alertOn) {
                    alertName = req.optString("name", "緊急")
                    alertText = req.optString("text", "")
                    setVolumePercent(100)
                    store.log("emergency", "警報表示を開始: $alertName")
                } else {
                    alertName = ""
                    alertText = ""
                    store.log("emergency", "警報表示を解除")
                }
                push()
            }

            "bcast_start" -> {
                if (!spkOn) return err("スピーカー無効")
                val rate = req.optInt("rate", Proto.RATE_HIGH)
                startPlayback(rate)
                store.log("broadcast", "放送受信開始")
            }

            "bcast_stop" -> {
                stopPlayback()
                store.log("broadcast", "放送受信終了")
            }

            "talk_start" -> {
                if (!spkOn) return err("スピーカー無効")
                val rate = req.optInt("rate", Proto.RATE_HIGH)
                val ip = req.optString("console_ip", consoleIp)
                startPlayback(rate)
                if (micOn && hasMic()) startTalk(ip, rate)
                talking = true
                store.log("call", "通話開始 ($ip)")
            }

            "talk_stop" -> {
                stopTalk()
                stopPlayback()
                talking = false
                store.log("call", "通話終了")
            }

            "reboot_audio" -> {
                stopTalk(); stopPlayback()
            }

            else -> return err("unknown cmd")
        }
        push()
        return statusJson()
    }

    private fun err(m: String): JSONObject {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", m)
        o.put("id", store.deviceId)
        return o
    }

    /**
     * 読み上げ。字幕が有効なら、読み上げている間だけ本文を画面に出す。
     * TTSの完了通知は端末によって来ないことがあるため、
     * 文字数からの推定時間を上限として必ず消えるようにしている。
     */
    private fun speak(text: String, urgent: Boolean) {
        val e = tts ?: return
        if (!ttsReady) {
            try { Thread.sleep(900) } catch (ex: Exception) { }
        }
        showCaption(text, urgent)
        try {
            val n = if (urgent) 2 else 1
            var i = 0
            while (i < n) {
                e.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "h$i")
                i++
            }
        } catch (ex: Exception) { }
    }

    private fun showCaption(text: String, urgent: Boolean) {
        if (!store.captionEnabled) return
        captionText = text
        captionUrgent = urgent
        captionSeq++
        val mine = captionSeq
        push()
        val repeat = if (urgent) 2 else 1
        val ms = (2000L + text.length * 190L) * repeat
        Thread {
            try { Thread.sleep(ms) } catch (e: Exception) { }
            if (captionSeq == mine) {
                captionText = ""
                captionUrgent = false
                push()
            }
        }.start()
    }



    // ------------------------------------------------------------- 音声
    private fun startPlayback(rate: Int) {
        if (receiver != null) return
        val r = Audio.Receiver(rate, Proto.PORT_AUDIO_DOWN)
        r.routeCtx = this
        r.routeMode = store.route
        if (r.start()) {
            receiver = r
            playing = true
            push()
        }
    }

    private fun stopPlayback() {
        receiver?.stop()
        receiver = null
        playing = false
        push()
    }

    private fun startTalk(ip: String, rate: Int) {
        if (sender != null || ip.isEmpty()) return
        val s = Audio.Sender(rate, store.autoGain)
        if (s.start(listOf(ip), Proto.PORT_AUDIO_UP)) {
            sender = s
        }
    }

    private fun stopTalk() {
        sender?.stop()
        sender = null
    }

    override fun onDestroy() {
        alive = false
        running = false
        stopTalk()
        stopPlayback()
        try { server?.close() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        try { wake?.release() } catch (e: Exception) { }
        try { wifiLock?.release() } catch (e: Exception) { }
        push()
        super.onDestroy()
    }
}
