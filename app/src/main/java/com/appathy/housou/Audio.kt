package com.appathy.housou

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.min
import kotlin.math.sqrt

object Audio {

    // ---------------------------------------------------------------- 再生
    class Player(private val rate: Int) {
        private var track: AudioTrack? = null

        /** 出力先を固定したい場合に設定する（null なら自動） */
        var routeCtx: android.content.Context? = null
        var routeMode: String = Routing.AUTO

        fun start() {
            if (track != null) return
            val min = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val buf = if (min > 0) min * 2 else rate
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val c = routeCtx
            if (c != null) Routing.apply(c, t, routeMode)
            t.play()
            track = t
        }

        fun write(data: ByteArray, len: Int) {
            try {
                track?.write(data, 0, len)
            } catch (e: Exception) {
                // ignore
            }
        }

        fun stop() {
            val t = track ?: return
            track = null
            try {
                t.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                t.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------------- 録音
    class Recorder(
        private val rate: Int,
        private val agcOn: Boolean,
        private val onFrame: (ByteArray, Int) -> Unit
    ) {
        private var rec: AudioRecord? = null
        private var th: Thread? = null
        @Volatile private var running = false
        private var gain = 1.0f

        @Volatile var level = 0
            private set

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (running) return true
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) return false
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 4
                )
            } catch (e: Exception) {
                return false
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                try { r.release() } catch (e: Exception) { }
                return false
            }
            attachEffects(r.audioSessionId)
            try {
                r.startRecording()
            } catch (e: Exception) {
                try { r.release() } catch (e2: Exception) { }
                return false
            }
            rec = r
            running = true
            val t = Thread { loop() }
            t.priority = Thread.MAX_PRIORITY
            th = t
            t.start()
            return true
        }

        private fun attachEffects(sessionId: Int) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.enabled = true
                }
            } catch (e: Exception) { }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.enabled = true
                }
            } catch (e: Exception) { }
            try {
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(sessionId)?.enabled = true
                }
            } catch (e: Exception) { }
        }

        private fun loop() {
            val samples = rate / (1000 / Proto.FRAME_MS)
            val pcm = ShortArray(samples)
            val out = ByteArray(samples * 2)
            val r = rec
            while (running && r != null) {
                val n = try {
                    r.read(pcm, 0, samples)
                } catch (e: Exception) {
                    -1
                }
                if (n <= 0) continue

                var sum = 0.0
                var i = 0
                while (i < n) {
                    val v = pcm[i].toDouble()
                    sum += v * v
                    i++
                }
                val rms = sqrt(sum / n)
                level = min(100, (rms / 90.0).toInt())

                if (agcOn) {
                    val want = if (rms < 1.0) 1.0f else (2600.0 / rms).toFloat()
                    val clamped = when {
                        want < 0.6f -> 0.6f
                        want > 6.0f -> 6.0f
                        else -> want
                    }
                    gain += (clamped - gain) * 0.08f
                }

                i = 0
                while (i < n) {
                    var v = if (agcOn) (pcm[i] * gain).toInt() else pcm[i].toInt()
                    if (v > 32767) v = 32767
                    if (v < -32768) v = -32768
                    out[i * 2] = (v and 0xFF).toByte()
                    out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    i++
                }
                onFrame(out, n * 2)
            }
        }

        fun stop() {
            running = false
            val r = rec
            rec = null
            try { th?.join(400) } catch (e: Exception) { }
            th = null
            try { r?.stop() } catch (e: Exception) { }
            try { r?.release() } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- チャイム生成
    /** プログラム生成のチャイム。urgent=true で緊急用の警報パターン */
    fun chime(rate: Int, urgent: Boolean): ByteArray {
        val notes: Array<DoubleArray> = if (urgent) {
            arrayOf(
                doubleArrayOf(880.0, 0.28), doubleArrayOf(660.0, 0.28),
                doubleArrayOf(880.0, 0.28), doubleArrayOf(660.0, 0.28),
                doubleArrayOf(880.0, 0.28), doubleArrayOf(660.0, 0.40)
            )
        } else {
            arrayOf(
                doubleArrayOf(659.3, 0.42), doubleArrayOf(523.3, 0.42),
                doubleArrayOf(587.3, 0.42), doubleArrayOf(392.0, 0.75)
            )
        }
        var total = 0
        for (n in notes) total += (rate * n[1]).toInt()
        val buf = ByteArray(total * 2)
        var idx = 0
        for (n in notes) {
            val f = n[0]
            val len = (rate * n[1]).toInt()
            var i = 0
            while (i < len) {
                val t = i.toDouble() / rate
                val env = if (urgent) {
                    val phase = i.toDouble() / len
                    if (phase < 0.05) phase / 0.05 else if (phase > 0.9) (1.0 - phase) / 0.1 else 1.0
                } else {
                    Math.exp(-2.6 * t)
                }
                val s1 = Math.sin(2.0 * Math.PI * f * t)
                val s2 = Math.sin(4.0 * Math.PI * f * t) * 0.22
                var v = ((s1 + s2) * env * 10500).toInt()
                if (v > 32767) v = 32767
                if (v < -32768) v = -32768
                buf[idx * 2] = (v and 0xFF).toByte()
                buf[idx * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                idx++
                i++
            }
        }
        return buf
    }

    fun playBlob(rate: Int, data: ByteArray, ctx: android.content.Context? = null, mode: String = Routing.AUTO) {
        val p = Player(rate)
        p.routeCtx = ctx
        p.routeMode = mode
        p.start()
        var off = 0
        val chunk = 2048
        while (off < data.size) {
            val n = min(chunk, data.size - off)
            val part = ByteArray(n)
            System.arraycopy(data, off, part, 0, n)
            p.write(part, n)
            off += n
        }
        try { Thread.sleep(300) } catch (e: Exception) { }
        p.stop()
    }

    // ---------------------------------------------------------------- 送信（マイク -> 相手）
    class Sender(private val rate: Int, agcOn: Boolean) {
        private var sock: DatagramSocket? = null
        @Volatile private var targets: List<InetAddress> = emptyList()
        @Volatile private var port = Proto.PORT_AUDIO_DOWN
        private val recorder = Recorder(rate, agcOn) { data, len -> send(data, len) }

        @Volatile var active = false
            private set

        val level: Int get() = recorder.level

        fun start(ips: List<String>, dstPort: Int): Boolean {
            if (active) return true
            port = dstPort
            val list = ArrayList<InetAddress>()
            for (ip in ips) {
                try {
                    list.add(InetAddress.getByName(ip))
                } catch (e: Exception) { }
            }
            if (list.isEmpty()) return false
            targets = list
            try {
                sock = DatagramSocket()
            } catch (e: Exception) {
                return false
            }
            if (!recorder.start()) {
                try { sock?.close() } catch (e: Exception) { }
                sock = null
                return false
            }
            active = true
            return true
        }

        private fun send(data: ByteArray, len: Int) {
            val s = sock ?: return
            for (a in targets) {
                try {
                    s.send(DatagramPacket(data, len, a, port))
                } catch (e: Exception) { }
            }
        }

        fun stop() {
            if (!active) return
            active = false
            recorder.stop()
            try { sock?.close() } catch (e: Exception) { }
            sock = null
        }
    }

    // ---------------------------------------------------------------- 受信（相手 -> スピーカー）
    class Receiver(private val rate: Int, private val port: Int) {
        private var sock: DatagramSocket? = null
        private var th: Thread? = null
        private var player: Player? = null

        /** 出力先の固定（端末側の設定を反映する） */
        var routeCtx: android.content.Context? = null
        var routeMode: String = Routing.AUTO
        @Volatile private var running = false
        @Volatile var lastPacketAt = 0L
            private set

        fun start(): Boolean {
            if (running) return true
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(port))
                s.soTimeout = 1000
                sock = s
            } catch (e: Exception) {
                return false
            }
            val p = Player(rate)
            p.routeCtx = routeCtx
            p.routeMode = routeMode
            p.start()
            player = p
            running = true
            val t = Thread { loop() }
            t.priority = Thread.MAX_PRIORITY
            th = t
            t.start()
            return true
        }

        private fun loop() {
            val buf = ByteArray(4096)
            val pk = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    sock?.receive(pk)
                    lastPacketAt = System.currentTimeMillis()
                    player?.write(pk.data, pk.length)
                } catch (e: Exception) {
                    // timeout など
                }
            }
        }

        fun stop() {
            running = false
            try { sock?.close() } catch (e: Exception) { }
            sock = null
            try { th?.join(400) } catch (e: Exception) { }
            th = null
            player?.stop()
            player = null
        }
    }
}
