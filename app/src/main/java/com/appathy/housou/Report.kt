package com.appathy.housou

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 運用日報。
 *
 * その日のログと端末の観測値を集計して、放送実績・異常・稼働率を1枚にまとめる。
 * 手動でも作れるが、既定では毎日決まった時刻に自動生成して通知する。
 */
object Report {

    private val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN)
    private val TIME = SimpleDateFormat("HH:mm", Locale.JAPAN)

    /** 指定日の 00:00 〜 23:59 を集計する。dayOffset=0 で今日 */
    fun build(store: Store, devices: List<Dev>, dayOffset: Int = 0): JSONObject {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val from = cal.timeInMillis
        val to = from + 24 * 3600 * 1000L
        val dateLabel = DATE.format(Date(from))

        val logs = store.logs()
        var bc = 0
        var em = 0
        var call = 0
        var sched = 0
        var alertN = 0
        val byTarget = HashMap<String, Int>()
        val byTag = HashMap<String, Int>()
        val emergencies = ArrayList<String>()
        val alerts = ArrayList<String>()

        var i = 0
        while (i < logs.length()) {
            val o = logs.getJSONObject(i)
            i++
            val at = o.optLong("at")
            if (at < from || at >= to) continue
            val kind = o.optString("kind")
            val tgt = o.optString("target")
            val tag = o.optString("tag")
            when (kind) {
                "broadcast" -> bc++
                "emergency" -> {
                    em++
                    emergencies.add(TIME.format(Date(at)) + "  " + o.optString("text"))
                }
                "call" -> call++
                "schedule" -> sched++
                "alert" -> {
                    alertN++
                    alerts.add(TIME.format(Date(at)) + "  " + o.optString("text"))
                }
            }
            if (kind == "broadcast" || kind == "emergency") {
                if (tgt.isNotEmpty()) byTarget[tgt] = (byTarget[tgt] ?: 0) + 1
                if (tag.isNotEmpty()) byTag[tag] = (byTag[tag] ?: 0) + 1
            }
        }

        // 端末稼働率（観測サンプルのうちオンラインだった割合）
        val avail = ArrayList<String>()
        var worst = 100
        for (d in devices) {
            val pts = Trend.availability(store, d.id, from, to)
            if (pts.first == 0) continue
            val pct = pts.second * 100 / pts.first
            if (pct < worst) worst = pct
            avail.add("${d.label()}  ${pct}%（${pts.first}回中${pts.second}回オンライン）")
        }

        val sb = StringBuilder()
        sb.append("■ 放送室 日報  ").append(dateLabel).append('\n')
        sb.append("建物: ").append(store.building).append('\n')
        sb.append('\n')
        sb.append("【放送実績】\n")
        sb.append("通常放送 ").append(bc).append("件 / 緊急・災害 ").append(em).append("件 / ")
        sb.append("予約放送 ").append(sched).append("件 / 通話 ").append(call).append("件\n")

        if (byTarget.isNotEmpty()) {
            sb.append('\n').append("【放送先の内訳】\n")
            for ((k, v) in byTarget.entries.sortedByDescending { it.value }) {
                sb.append("・").append(Targeting.label(k)).append("  ").append(v).append("件\n")
            }
        }
        if (byTag.isNotEmpty()) {
            sb.append('\n').append("【使用した定型文】\n")
            for ((k, v) in byTag.entries.sortedByDescending { it.value }.take(8)) {
                sb.append("・").append(k).append("  ").append(v).append("回\n")
            }
        }
        if (emergencies.isNotEmpty()) {
            sb.append('\n').append("【緊急・災害放送】\n")
            for (e in emergencies) sb.append("・").append(e).append('\n')
        }
        if (avail.isNotEmpty()) {
            sb.append('\n').append("【端末稼働率】\n")
            for (a in avail) sb.append("・").append(a).append('\n')
        }
        if (alerts.isNotEmpty()) {
            sb.append('\n').append("【発生したアラート】\n")
            for (a in alerts.take(20)) sb.append("・").append(a).append('\n')
        }

        val omens = Trend.omens(store, devices)
        if (omens.isNotEmpty()) {
            sb.append('\n').append("【翌日への申し送り（故障予兆）】\n")
            for (o in omens.take(8)) {
                sb.append("・").append(o.title).append(" — ").append(o.detail).append('\n')
            }
        }
        if (em == 0 && alertN == 0 && omens.isEmpty()) {
            sb.append('\n').append("特記事項はありません。\n")
        }

        val o = JSONObject()
        o.put("date", dateLabel)
        o.put("at", System.currentTimeMillis())
        o.put("bc", bc)
        o.put("em", em)
        o.put("alerts", alertN)
        o.put("worst", if (avail.isEmpty()) -1 else worst)
        o.put("text", sb.toString())
        return o
    }

    /** 生成して保存する。同じ日付があれば置き換える */
    fun save(store: Store, o: JSONObject) {
        val a = store.reports()
        val out = JSONArray()
        var i = 0
        while (i < a.length()) {
            val e = a.getJSONObject(i)
            i++
            if (e.optString("date") != o.optString("date")) out.put(e)
        }
        out.put(o)
        // 直近30日分だけ残す
        val trimmed = JSONArray()
        var k = maxOf(0, out.length() - 30)
        while (k < out.length()) {
            trimmed.put(out.get(k)); k++
        }
        store.saveReports(trimmed)
    }

    fun headline(o: JSONObject): String {
        val w = o.optInt("worst", -1)
        val av = if (w < 0) "" else " / 最低稼働率 ${w}%"
        return "放送${o.optInt("bc")}件・緊急${o.optInt("em")}件・アラート${o.optInt("alerts")}件$av"
    }
}
