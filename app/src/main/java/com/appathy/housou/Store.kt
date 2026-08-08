package com.appathy.housou

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Store(ctx: Context) {

    private val p: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("housou", Context.MODE_PRIVATE)

    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    /** 任意キーの読み書き（Trend など補助モジュール用） */
    fun raw(k: String, d: String): String = s(k, d)
    fun setRaw(k: String, v: String) = put(k, v)

    /** "" / "console" / "terminal" */
    var mode: String
        get() = s("mode", "")
        set(v) = put("mode", v)

    var pin: String
        get() = s("pin", "0000")
        set(v) = put("pin", v)

    var building: String
        get() = s("building", "本社ビル")
        set(v) = put("building", v)

    var floors: Int
        get() = p.getInt("floors", 5)
        set(v) = p.edit().putInt("floors", v).apply()

    /** 端末モードでの自分の名前 */
    var termName: String
        get() = s("term_name", "放送端末")
        set(v) = put("term_name", v)

    var termFloor: Int
        get() = p.getInt("term_floor", 1)
        set(v) = p.edit().putInt("term_floor", v).apply()

    var termGroup: String
        get() = s("term_group", "既定")
        set(v) = put("term_group", v)

    /** "high" / "low" */
    var quality: String
        get() = s("quality", "high")
        set(v) = put("quality", v)

    val rate: Int get() = if (quality == "low") Proto.RATE_LOW else Proto.RATE_HIGH

    var autoGain: Boolean
        get() = p.getBoolean("agc", true)
        set(v) = p.edit().putBoolean("agc", v).apply()

    // ---------- BGM / 音声ライブラリ ----------
    var bgmId: String
        get() = s("bgm_id", "")
        set(v) = put("bgm_id", v)

    var bgmVolume: Int
        get() = p.getInt("bgm_vol", 35)
        set(v) = p.edit().putInt("bgm_vol", v).apply()

    var bgmLoop: Boolean
        get() = p.getBoolean("bgm_loop", true)
        set(v) = p.edit().putBoolean("bgm_loop", v).apply()

    fun audioItems(): JSONArray = arr("audio")
    fun saveAudioItems(a: JSONArray) = put("audio", a.toString())

    var autoStart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()

    val deviceId: String
        get() {
            var id = s("dev_id", "")
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString().substring(0, 8)
                put("dev_id", id)
            }
            return id
        }

    // ---------- 端末台帳 ----------
    fun devices(): JSONArray = arr("devices")
    fun saveDevices(a: JSONArray) = put("devices", a.toString())

    // ---------- 定型文ライブラリ ----------
    fun templates(): JSONArray {
        val a = arr("templates")
        if (a.length() == 0) {
            val d = JSONArray()
            d.put(tpl("朝礼開始", "おはようございます。まもなく朝礼を開始します。各自ご準備ください。"))
            d.put(tpl("昼休み", "12時になりました。昼休みを開始します。"))
            d.put(tpl("終業", "本日の業務を終了します。戸締まりの確認をお願いします。"))
            d.put(tpl("避難誘導", "避難訓練を開始します。落ち着いて、係員の誘導に従ってください。"))
            saveTemplates(d)
            return d
        }
        return a
    }

    private fun tpl(title: String, body: String): JSONObject {
        val o = JSONObject()
        o.put("title", title)
        o.put("body", body)
        return o
    }

    fun saveTemplates(a: JSONArray) = put("templates", a.toString())

    // ---------- スケジュール ----------
    fun schedules(): JSONArray = arr("schedules")
    fun saveSchedules(a: JSONArray) = put("schedules", a.toString())

    // ---------- ログ ----------
    fun logs(): JSONArray = arr("logs")

    fun log(kind: String, text: String, target: String = "", tag: String = "") {
        val a = logs()
        val o = JSONObject()
        o.put("at", System.currentTimeMillis())
        o.put("kind", kind)
        o.put("text", text)
        if (target.isNotEmpty()) o.put("target", target)
        if (tag.isNotEmpty()) o.put("tag", tag)
        val out = JSONArray()
        out.put(o)
        var n = 0
        while (n < a.length() && n < 499) {
            out.put(a.getJSONObject(n))
            n++
        }
        put("logs", out.toString())
    }

    fun clearLogs() = put("logs", "[]")

    private fun arr(k: String): JSONArray {
        return try {
            JSONArray(s(k, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    companion object {
        private val HM = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
        private val HM2 = SimpleDateFormat("HH:mm", Locale.JAPAN)
        fun stamp(t: Long): String = HM.format(Date(t))
        fun hhmm(t: Long): String = HM2.format(Date(t))
        fun today(t: Long): Boolean {
            val f = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
            return f.format(Date(t)) == f.format(Date())
        }
    }
}
