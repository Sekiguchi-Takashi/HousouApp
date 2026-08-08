package com.appathy.housou

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音声ファイル（mp3/m4a/wav/ogg など）を
 * モノラル 16bit PCM / 16kHz へデコードする。
 * ライブラリ取り込み時に一度だけ実行し、以降は生PCMを再利用する。
 */
object Decoder {

    private const val MAX_SECONDS = 900

    /** 失敗時は null */
    fun decode(ctx: Context, uri: Uri): ShortArray? {
        var ex: MediaExtractor? = null
        try {
            val e = MediaExtractor()
            e.setDataSource(ctx, uri, null)
            ex = e
            var track = -1
            var fmt: MediaFormat? = null
            var i = 0
            while (i < e.trackCount) {
                val f = e.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    track = i; fmt = f; break
                }
                i++
            }
            if (track < 0 || fmt == null) return null
            e.selectTrack(track)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate = safeInt(fmt, MediaFormat.KEY_SAMPLE_RATE, 44100)
            val ch = safeInt(fmt, MediaFormat.KEY_CHANNEL_COUNT, 2)

            val mono = if (mime == "audio/raw") readRaw(e, ch) else readCoded(e, fmt, mime, ch)
            if (mono == null || mono.isEmpty()) return null
            return resample(mono, srcRate, Proto.RATE_HIGH)
        } catch (t: Throwable) {
            return null
        } finally {
            try { ex?.release() } catch (t: Throwable) { }
        }
    }

    private fun safeInt(f: MediaFormat, key: String, def: Int): Int {
        return try {
            if (f.containsKey(key)) f.getInteger(key) else def
        } catch (t: Throwable) {
            def
        }
    }

    // ---------------------------------------------------------- 非圧縮
    private fun readRaw(ex: MediaExtractor, ch: Int): ShortArray? {
        val chunks = ArrayList<ShortArray>()
        var total = 0
        val buf = ByteBuffer.allocate(1 shl 16)
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n <= 0) break
            chunks.add(toMono(buf, n, ch))
            total += chunks[chunks.size - 1].size
            if (total > MAX_SECONDS * 48000) break
            ex.advance()
        }
        return concat(chunks, total)
    }

    // ---------------------------------------------------------- 圧縮
    private fun readCoded(ex: MediaExtractor, fmt: MediaFormat, mime: String, ch: Int): ShortArray? {
        var codec: MediaCodec? = null
        try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c

            val chunks = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var sawInEos = false
            var sawOutEos = false
            var outCh = ch
            var guard = 0

            while (!sawOutEos && guard < 2_000_000) {
                guard++
                if (!sawInEos) {
                    val idx = c.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val ib = c.getInputBuffer(idx)
                        val n = if (ib == null) -1 else ex.readSampleData(ib, 0)
                        if (n < 0) {
                            c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInEos = true
                        } else {
                            c.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oidx = c.dequeueOutputBuffer(info, 10000)
                if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outCh = safeInt(c.outputFormat, MediaFormat.KEY_CHANNEL_COUNT, outCh)
                } else if (oidx >= 0) {
                    val ob = c.getOutputBuffer(oidx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        val m = toMono(ob, info.size, outCh)
                        chunks.add(m)
                        total += m.size
                    }
                    c.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutEos = true
                    if (total > MAX_SECONDS * 48000) sawOutEos = true
                }
            }
            return concat(chunks, total)
        } catch (t: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (t: Throwable) { }
            try { codec?.release() } catch (t: Throwable) { }
        }
    }

    // ---------------------------------------------------------- 変換
    private fun toMono(b: ByteBuffer, bytes: Int, ch: Int): ShortArray {
        val dup = b.duplicate()
        dup.order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes / 2 / (if (ch < 1) 1 else ch)
        val out = ShortArray(frames)
        val cc = if (ch < 1) 1 else ch
        var i = 0
        while (i < frames) {
            var sum = 0
            var k = 0
            while (k < cc) {
                sum += dup.short.toInt()
                k++
            }
            out[i] = (sum / cc).toShort()
            i++
        }
        return out
    }

    private fun concat(chunks: List<ShortArray>, total: Int): ShortArray? {
        if (total <= 0) return null
        val out = ShortArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        return out
    }

    /** 線形補間リサンプル */
    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || from <= 0) return src
        val n = ((src.size.toLong() * to) / from).toInt()
        if (n <= 1) return src
        val out = ShortArray(n)
        val step = from.toDouble() / to.toDouble()
        var i = 0
        while (i < n) {
            val x = i * step
            val a = x.toInt()
            val bIdx = if (a + 1 < src.size) a + 1 else a
            val f = x - a
            if (a >= src.size) {
                out[i] = 0
            } else {
                out[i] = (src[a] + (src[bIdx] - src[a]) * f).toInt().toShort()
            }
            i++
        }
        return out
    }
}
