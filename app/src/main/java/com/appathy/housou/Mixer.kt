package com.appathy.housou

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.locks.LockSupport

/**
 * コンソール側の音声ミキサー。
 *
 * 3系統（マイク / 音声ファイル / BGM）をそれぞれ独立した宛先集合で持ち、
 * 20ms ごとに「宛先ごとに」必要な系統だけを合成して UDP 送出する。
 * BGM はマイク・ファイル・読み上げの発生中に自動でダッキングする。
 *
 * 端末側の再生開始/終了は宛先ごとの参照カウントで管理するため、
 * BGM を流したまま PTT を押しても再生が途切れない。
 */
object Mixer {

    private val lock = Any()

    @Volatile private var running = false
    private var th: Thread? = null
    private var sock: DatagramSocket? = null
    @Volatile private var rate = Proto.RATE_HIGH

    // ---- マイク
    private var recorder: Audio.Recorder? = null
    private val ring = ShortArray(32000)
    private var rw = 0
    private var rr = 0
    private var micIps: List<String> = emptyList()
    @Volatile var micActive = false
        private set
    val micLevel: Int get() = recorder?.level ?: 0

    // ---- 音声ファイル
    private var filePcm: ShortArray? = null
    private var filePos = 0
    private var fileIps: List<String> = emptyList()
    @Volatile var fileActive = false
        private set
    @Volatile var fileTitle = ""

    // ---- BGM
    private var bgmPcm: ShortArray? = null
    private var bgmPos = 0
    private var bgmIps: List<String> = emptyList()
    @Volatile var bgmActive = false
        private set
    @Volatile var bgmTitle = ""
    @Volatile var bgmGain = 0.35f
    @Volatile var bgmLoop = true

    @Volatile private var duckUntil = 0L
    private var duck = 1.0f

    @Volatile private var fileEnding = false
    @Volatile private var bgmEnding = false

    /** 端末ごとの再生セッション参照カウント */
    private val refs = HashMap<String, Int>()

    /** 読み上げなどの間だけ BGM を下げる */
    fun duckFor(ms: Long) {
        val t = System.currentTimeMillis() + ms
        if (t > duckUntil) duckUntil = t
    }

    fun fileProgress(): Int {
        val p = filePcm ?: return 0
        if (p.isEmpty()) return 0
        return (filePos.toLong() * 100 / p.size).toInt().coerceIn(0, 100)
    }

    // ------------------------------------------------------------ 制御
    @Synchronized
    fun startMic(ips: List<String>, r: Int, agc: Boolean): Boolean {
        if (micActive) return true
        if (ips.isEmpty()) return false
        ensure(r)
        val rec = Audio.Recorder(rate, agc) { data, len -> pushMic(data, len) }
        if (!rec.start()) {
            release()
            return false
        }
        synchronized(lock) {
            rr = 0; rw = 0
            recorder = rec
            micIps = ips
        }
        micActive = true
        open(ips)
        return true
    }

    @Synchronized
    fun stopMic() {
        if (!micActive) return
        micActive = false
        val rec: Audio.Recorder?
        val ips: List<String>
        synchronized(lock) {
            rec = recorder
            recorder = null
            ips = micIps
            micIps = emptyList()
        }
        rec?.stop()
        close(ips)
        release()
    }

    @Synchronized
    fun startFile(pcm: ShortArray, title: String, ips: List<String>, r: Int): Boolean {
        if (ips.isEmpty() || pcm.isEmpty()) return false
        stopFile()
        ensure(r)
        synchronized(lock) {
            filePcm = pcm
            filePos = 0
            fileIps = ips
        }
        fileTitle = title
        fileActive = true
        open(ips)
        return true
    }

    @Synchronized
    fun stopFile() {
        if (!fileActive) return
        fileActive = false
        val ips: List<String>
        synchronized(lock) {
            filePcm = null
            filePos = 0
            ips = fileIps
            fileIps = emptyList()
        }
        fileTitle = ""
        close(ips)
        release()
    }

    @Synchronized
    fun startBgm(pcm: ShortArray, title: String, ips: List<String>, r: Int, loop: Boolean): Boolean {
        if (ips.isEmpty() || pcm.isEmpty()) return false
        stopBgm()
        ensure(r)
        synchronized(lock) {
            bgmPcm = pcm
            bgmPos = 0
            bgmIps = ips
        }
        bgmTitle = title
        bgmLoop = loop
        bgmActive = true
        open(ips)
        return true
    }

    @Synchronized
    fun stopBgm() {
        if (!bgmActive) return
        bgmActive = false
        val ips: List<String>
        synchronized(lock) {
            bgmPcm = null
            bgmPos = 0
            ips = bgmIps
            bgmIps = emptyList()
        }
        bgmTitle = ""
        close(ips)
        release()
    }

    @Synchronized
    fun stopAll() {
        stopMic()
        stopFile()
        stopBgm()
    }

    // ------------------------------------------------------------ 端末セッション
    private fun open(ips: List<String>) {
        val fresh = ArrayList<String>()
        synchronized(refs) {
            for (ip in ips) {
                val n = (refs[ip] ?: 0) + 1
                refs[ip] = n
                if (n == 1) fresh.add(ip)
            }
        }
        if (fresh.isEmpty()) return
        val r = rate
        Thread {
            val req = Net.cmd("bcast_start")
            req.put("rate", r)
            for (ip in fresh) Net.ctrl(ip, req, 2000)
        }.start()
    }

    private fun close(ips: List<String>) {
        val done = ArrayList<String>()
        synchronized(refs) {
            for (ip in ips) {
                val n = (refs[ip] ?: 1) - 1
                if (n <= 0) {
                    refs.remove(ip)
                    done.add(ip)
                } else {
                    refs[ip] = n
                }
            }
        }
        if (done.isEmpty()) return
        Thread {
            val req = Net.cmd("bcast_stop")
            for (ip in done) Net.ctrl(ip, req, 2000)
        }.start()
    }

    // ------------------------------------------------------------ 実行基盤
    private fun ensure(r: Int) {
        if (running) return
        rate = r
        try {
            sock = DatagramSocket()
        } catch (t: Throwable) {
            sock = null
            return
        }
        running = true
        val t = Thread { loop() }
        t.priority = Thread.MAX_PRIORITY
        th = t
        t.start()
    }

    private fun release() {
        if (micActive || fileActive || bgmActive) return
        running = false
        try { th?.join(300) } catch (t: Throwable) { }
        th = null
        try { sock?.close() } catch (t: Throwable) { }
        sock = null
    }

    private fun pushMic(data: ByteArray, len: Int) {
        synchronized(lock) {
            var i = 0
            while (i + 1 < len) {
                val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                ring[rw] = v
                rw = (rw + 1) % ring.size
                if (rw == rr) rr = (rr + 1) % ring.size
                i += 2
            }
        }
    }

    private fun loop() {
        val n = rate / (1000 / Proto.FRAME_MS)
        val mic = ShortArray(n)
        val fil = ShortArray(n)
        val bgm = ShortArray(n)
        val mix = IntArray(n)
        val out = ByteArray(n * 2)
        var next = System.nanoTime()

        while (running) {
            next += Proto.FRAME_MS * 1_000_000L

            var mIps: List<String>
            var fIps: List<String>
            var bIps: List<String>
            var hasMic = false
            var hasFile = false
            var hasBgm = false

            synchronized(lock) {
                mIps = micIps
                fIps = fileIps
                bIps = bgmIps
                hasMic = micActive && mIps.isNotEmpty()
                hasFile = fileActive && fIps.isNotEmpty()
                hasBgm = bgmActive && bIps.isNotEmpty()

                if (hasMic) readRing(mic, n)
                if (hasFile) hasFile = readFile(fil, n)
                if (hasBgm) hasBgm = readBgm(bgm, n)
            }

            if (!hasFile && fileActive && !fileEnding) {
                fileEnding = true
                Thread {
                    stopFile()
                    fileEnding = false
                }.start()
            }
            if (!hasBgm && bgmActive && !bgmLoop && !bgmEnding) {
                bgmEnding = true
                Thread {
                    stopBgm()
                    bgmEnding = false
                }.start()
            }

            // ダッキング
            val speaking = hasMic || hasFile || System.currentTimeMillis() < duckUntil
            val want = if (speaking) 0.22f else 1.0f
            duck += (want - duck) * 0.12f
            val bg = bgmGain * duck

            if (hasMic || hasFile || hasBgm) {
                val ips = LinkedHashSet<String>()
                if (hasMic) ips.addAll(mIps)
                if (hasFile) ips.addAll(fIps)
                if (hasBgm) ips.addAll(bIps)

                for (ip in ips) {
                    val useMic = hasMic && mIps.contains(ip)
                    val useFile = hasFile && fIps.contains(ip)
                    val useBgm = hasBgm && bIps.contains(ip)
                    if (!useMic && !useFile && !useBgm) continue

                    var i = 0
                    while (i < n) {
                        var v = 0
                        if (useMic) v += mic[i].toInt()
                        if (useFile) v += fil[i].toInt()
                        if (useBgm) v += (bgm[i] * bg).toInt()
                        mix[i] = v
                        i++
                    }
                    i = 0
                    while (i < n) {
                        var v = mix[i]
                        if (v > 32767) v = 32767
                        if (v < -32768) v = -32768
                        out[i * 2] = (v and 0xFF).toByte()
                        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        i++
                    }
                    send(ip, out, n * 2)
                }
            }

            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                LockSupport.parkNanos(sleep)
            } else {
                next = System.nanoTime()
            }
        }
    }

    private fun send(ip: String, data: ByteArray, len: Int) {
        val s = sock ?: return
        try {
            s.send(DatagramPacket(data, len, InetAddress.getByName(ip), Proto.PORT_AUDIO_DOWN))
        } catch (t: Throwable) { }
    }

    private fun readRing(dst: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            if (rr == rw) {
                dst[i] = 0
            } else {
                dst[i] = ring[rr]
                rr = (rr + 1) % ring.size
            }
            i++
        }
    }

    /** 保存PCMは常に16kHz。8kHz運用時は1つ飛ばしで読む */
    private fun step(): Int = if (rate == Proto.RATE_LOW) 2 else 1

    private fun readFile(dst: ShortArray, n: Int): Boolean {
        val p = filePcm ?: return false
        val st = step()
        var i = 0
        while (i < n) {
            if (filePos >= p.size) {
                while (i < n) { dst[i] = 0; i++ }
                return false
            }
            dst[i] = p[filePos]
            filePos += st
            i++
        }
        return true
    }

    private fun readBgm(dst: ShortArray, n: Int): Boolean {
        val p = bgmPcm ?: return false
        val st = step()
        var i = 0
        while (i < n) {
            if (bgmPos >= p.size) {
                if (!bgmLoop) {
                    while (i < n) { dst[i] = 0; i++ }
                    return false
                }
                bgmPos = 0
            }
            dst[i] = p[bgmPos]
            bgmPos += st
            i++
        }
        return true
    }
}
