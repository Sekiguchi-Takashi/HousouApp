package com.appathy.housou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 管理コンソール側。端末探索・状態ポーリング・スケジュール実行・通話受話を担当。
 */
class ConsoleService : Service() {

    companion object {
        const val CH_ID = "housou_console"
        const val NOTI_ID = 1002

        @Volatile var running = false
        @Volatile var instance: ConsoleService? = null

        private var txSender: Audio.Sender? = null
        private var callRx: Audio.Receiver? = null

        @Volatile var broadcasting = false
        @Volatile var calling = false
        @Volatile var callTargetId = ""

        var onUpdate: (() -> Unit)? = null

        fun push() {
            Handler(Looper.getMainLooper()).post {
                try { onUpdate?.invoke() } catch (e: Exception) { }
            }
        }

        val micLevel: Int get() = txSender?.level ?: 0

        /** マイク送話開始（放送・通話共通） */
        @Synchronized
        fun startTx(ips: List<String>, rate: Int, agc: Boolean): Boolean {
            if (txSender != null) return true
            val s = Audio.Sender(rate, agc)
            if (!s.start(ips, Proto.PORT_AUDIO_DOWN)) return false
            txSender = s
            push()
            return true
        }

        @Synchronized
        fun stopTx() {
            txSender?.stop()
            txSender = null
            push()
        }

        @Synchronized
        fun startCallRx(rate: Int) {
            if (callRx != null) return
            val r = Audio.Receiver(rate, Proto.PORT_AUDIO_UP)
            if (r.start()) callRx = r
        }

        @Synchronized
        fun stopCallRx() {
            callRx?.stop()
            callRx = null
        }
    }

    private lateinit var store: Store
    @Volatile private var alive = false
    private var discoverTh: Thread? = null
    private var pollTh: Thread? = null
    private var schedTh: Thread? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Registry.load(store)
        alive = true
        running = true
        instance = this
        startForegroundSafe()
        acquireLocks()
        startDiscovery()
        startPolling()
        startScheduler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        return START_STICKY
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "管理コンソール", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CH_ID)
            .setContentTitle("放送室 管理コンソール")
            .setContentText("${store.building} / ${Net.localIp()}")
            .setSmallIcon(R.drawable.ic_noti)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTI_ID, n, type)
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (e: Exception) {
            try { startForeground(NOTI_ID, n) } catch (e2: Exception) { }
        }
    }

    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val l = wm.createMulticastLock("housou:mc")
            l.setReferenceCounted(false)
            l.acquire()
            mcLock = l
        } catch (e: Exception) { }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "housou:console")
            w.acquire()
            wake = w
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------- 探索
    private fun startDiscovery() {
        val t = Thread {
            var sock: DatagramSocket? = null
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Proto.PORT_ANNOUNCE))
                s.soTimeout = 2000
                sock = s
            } catch (e: Exception) {
                return@Thread
            }
            val buf = ByteArray(4096)
            while (alive) {
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    sock.receive(pk)
                    val txt = String(pk.data, 0, pk.length, Charsets.UTF_8)
                    val o = JSONObject(txt)
                    if (o.optString("t") == "announce") {
                        val ip = pk.address?.hostAddress ?: ""
                        val known = Registry.byId(o.optString("id")) != null
                        val d = Registry.upsert(o, ip)
                        if (!known) {
                            store.log("system", "端末を自動検出: ${d.label()} (${d.ip})")
                            Registry.save(store)
                        }
                        push()
                    }
                } catch (e: Exception) {
                    // timeout
                }
            }
            try { sock.close() } catch (e: Exception) { }
        }
        discoverTh = t
        t.start()
    }

    // ------------------------------------------------------------- ポーリング
    private fun startPolling() {
        val t = Thread {
            while (alive) {
                val list = Registry.all()
                for (d in list) {
                    if (d.ip.isEmpty()) continue
                    val t0 = System.currentTimeMillis()
                    val res = Net.ctrl(d.ip, Net.cmd("status"), 2000)
                    val dt = (System.currentTimeMillis() - t0).toInt()
                    if (res != null && res.optBoolean("ok", true)) {
                        Registry.upsert(res, d.ip)
                        val nd = Registry.byId(d.id)
                        if (nd != null) {
                            nd.rtt = dt
                            nd.loss = if (dt > 500) 10 else 0
                        }
                    } else {
                        d.rtt = -1
                    }
                }
                push()
                var i = 0
                while (i < 40 && alive) {
                    try { Thread.sleep(100) } catch (e: Exception) { }
                    i++
                }
            }
        }
        pollTh = t
        t.start()
    }

    // ------------------------------------------------------------- スケジュール
    private fun startScheduler() {
        val t = Thread {
            val key = SimpleDateFormat("yyyyMMddHHmm", Locale.JAPAN)
            while (alive) {
                try {
                    val now = Date()
                    val cal = Calendar.getInstance()
                    cal.time = now
                    val hh = cal.get(Calendar.HOUR_OF_DAY)
                    val mm = cal.get(Calendar.MINUTE)
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                    val weekday = dow in Calendar.MONDAY..Calendar.FRIDAY
                    val stamp = key.format(now)

                    val arr = store.schedules()
                    var changed = false
                    var i = 0
                    while (i < arr.length()) {
                        val o = arr.getJSONObject(i)
                        i++
                        if (!o.optBoolean("enabled", true)) continue
                        if (o.optInt("hour", -1) != hh) continue
                        if (o.optInt("min", -1) != mm) continue
                        val mode = o.optString("mode", "daily")
                        if (mode == "weekday" && !weekday) continue
                        if (o.optString("fired") == stamp) continue
                        o.put("fired", stamp)
                        changed = true
                        fire(o)
                    }
                    if (changed) store.saveSchedules(arr)
                } catch (e: Exception) { }
                var n = 0
                while (n < 100 && alive) {
                    try { Thread.sleep(100) } catch (e: Exception) { }
                    n++
                }
            }
        }
        schedTh = t
        t.start()
    }

    private fun fire(o: JSONObject) {
        val target = o.optString("target", "all")
        val text = o.optString("text", "")
        val urgent = o.optBoolean("urgent", false)
        val targets = Targeting.resolve(target)
        val req = Net.cmd("tts")
        req.put("text", text)
        req.put("urgent", urgent)
        req.put("chime", o.optBoolean("chime", true))
        for (d in targets) {
            Net.ctrl(d.ip, req, 3000)
        }
        store.log("schedule", "予約放送を実行: ${o.optString("title", text)} → ${targets.size}台")
        push()
    }

    override fun onDestroy() {
        alive = false
        running = false
        instance = null
        stopTx()
        stopCallRx()
        try { mcLock?.release() } catch (e: Exception) { }
        try { wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}

/** 放送対象の指定を端末リストへ解決する */
object Targeting {
    fun resolve(spec: String): List<Dev> {
        val on = Registry.online()
        if (spec == "all" || spec.isEmpty()) return on
        if (spec.startsWith("floor:")) {
            val f = spec.substring(6).toIntOrNull() ?: return on
            return on.filter { it.floor == f }
        }
        if (spec.startsWith("group:")) {
            val g = spec.substring(6)
            return on.filter { it.group == g }
        }
        if (spec.startsWith("dev:")) {
            val id = spec.substring(4)
            return on.filter { it.id == id }
        }
        return on
    }

    fun label(spec: String): String {
        if (spec == "all" || spec.isEmpty()) return "全館"
        if (spec.startsWith("floor:")) return spec.substring(6) + "階"
        if (spec.startsWith("group:")) return "グループ " + spec.substring(6)
        if (spec.startsWith("dev:")) {
            val d = Registry.byId(spec.substring(4))
            return d?.label() ?: "個別"
        }
        return spec
    }
}
