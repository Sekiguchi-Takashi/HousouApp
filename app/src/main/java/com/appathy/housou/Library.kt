package com.appathy.housou

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 音声ライブラリ。取り込み時に 16kHz モノラル PCM へ変換して保存し、
 * 放送・BGM の双方から同じ生データを使う。
 */
object Library {

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "audio")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.pcm")

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            val c = ctx.contentResolver.query(uri, null, null, null, null)
            if (c != null) {
                c.use {
                    val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && it.moveToFirst()) {
                        val n = it.getString(i)
                        if (!n.isNullOrEmpty()) return n
                    }
                }
            }
        } catch (t: Throwable) { }
        return "音源"
    }

    /** 取り込み。成功したら台帳エントリを返す */
    fun add(ctx: Context, store: Store, uri: Uri): JSONObject? {
        val pcm = Decoder.decode(ctx, uri) ?: return null
        val id = UUID.randomUUID().toString().substring(0, 8)
        try {
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            FileOutputStream(file(ctx, id)).use { it.write(bb.array()) }
        } catch (t: Throwable) {
            return null
        }
        val o = JSONObject()
        o.put("id", id)
        o.put("title", trimName(displayName(ctx, uri)))
        o.put("samples", pcm.size)
        o.put("sec", pcm.size / Proto.RATE_HIGH)
        o.put("at", System.currentTimeMillis())
        val a = store.audioItems()
        a.put(o)
        store.saveAudioItems(a)
        return o
    }

    private fun trimName(n: String): String {
        val i = n.lastIndexOf('.')
        val base = if (i > 0) n.substring(0, i) else n
        return if (base.length > 30) base.substring(0, 30) else base
    }

    fun pcm(ctx: Context, id: String): ShortArray? {
        try {
            val f = file(ctx, id)
            if (!f.exists()) return null
            val bytes = f.readBytes()
            val out = ShortArray(bytes.size / 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < out.size) {
                out[i] = bb.short
                i++
            }
            return out
        } catch (t: Throwable) {
            return null
        }
    }

    fun find(store: Store, id: String): JSONObject? {
        val a = store.audioItems()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) return o
            i++
        }
        return null
    }

    fun remove(ctx: Context, store: Store, id: String) {
        try { file(ctx, id).delete() } catch (t: Throwable) { }
        val a = store.audioItems()
        val out = JSONArray()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") != id) out.put(o)
            i++
        }
        store.saveAudioItems(out)
        if (store.bgmId == id) store.bgmId = ""
    }

    fun duration(o: JSONObject): String {
        val s = o.optInt("sec")
        return String.format("%d:%02d", s / 60, s % 60)
    }
}
