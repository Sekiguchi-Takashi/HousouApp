package com.appathy.housou

import org.json.JSONObject
import java.util.Calendar

/**
 * AI放送支援。蓄積されたログから利用パターンを抽出し、
 * 放送先・スケジュール・お気に入りを提案する。すべてローカル計算。
 */
object Suggest {

    class Item(
        val kind: Int,
        val title: String,
        val detail: String,
        val target: String = "",
        val hour: Int = -1,
        val minute: Int = 0,
        val text: String = ""
    )

    const val K_TARGET = 1
    const val K_SCHEDULE = 2
    const val K_FAVORITE = 3

    /** 直近ログから提案を生成（最大3件） */
    fun build(store: Store): List<Item> {
        val out = ArrayList<Item>()
        val logs = store.logs()
        if (logs.length() < 4) return out

        val cal = Calendar.getInstance()
        val nowHour = cal.get(Calendar.HOUR_OF_DAY)

        // 収集
        val targetByHour = HashMap<String, Int>()
        val targetAll = HashMap<String, Int>()
        val slot = HashMap<String, ArrayList<String>>()   // "hh:mm" -> 日付リスト
        val slotTarget = HashMap<String, String>()
        val slotText = HashMap<String, String>()
        val tagCount = HashMap<String, Int>()

        var i = 0
        while (i < logs.length() && i < 400) {
            val o = logs.getJSONObject(i)
            i++
            val kind = o.optString("kind")
            if (kind != "broadcast" && kind != "emergency") continue
            val tgt = o.optString("target")
            val at = o.optLong("at")
            if (at == 0L) continue
            val c = Calendar.getInstance()
            c.timeInMillis = at
            val h = c.get(Calendar.HOUR_OF_DAY)
            val m = c.get(Calendar.MINUTE)
            val day = "" + c.get(Calendar.YEAR) + c.get(Calendar.DAY_OF_YEAR)

            if (tgt.isNotEmpty()) {
                targetAll[tgt] = (targetAll[tgt] ?: 0) + 1
                if (kotlin.math.abs(h - nowHour) <= 1) {
                    targetByHour[tgt] = (targetByHour[tgt] ?: 0) + 1
                }
            }
            val key = String.format("%02d:%02d", h, m / 10 * 10)
            val days = slot.getOrPut(key) { ArrayList() }
            if (!days.contains(day)) days.add(day)
            if (tgt.isNotEmpty()) slotTarget[key] = tgt
            val tg = o.optString("tag")
            if (tg.isNotEmpty()) {
                tagCount[tg] = (tagCount[tg] ?: 0) + 1
                slotText[key] = tg
            }
        }

        // 1) 放送先提案
        val best = pickTop(targetByHour) ?: pickTop(targetAll)
        if (best != null && (targetByHour[best] ?: 0) + (targetAll[best] ?: 0) >= 3) {
            out.add(
                Item(
                    K_TARGET,
                    "この時間帯は「${Targeting.label(best)}」への放送が多いです",
                    "過去 ${targetAll[best]} 回。タップで放送対象に設定します。",
                    target = best
                )
            )
        }

        // 2) スケジュール提案（同じ時間帯に3日以上）
        val existing = HashSet<String>()
        val sc = store.schedules()
        var k = 0
        while (k < sc.length()) {
            val o = sc.getJSONObject(k)
            existing.add(String.format("%02d:%02d", o.optInt("hour"), o.optInt("min") / 10 * 10))
            k++
        }
        var bestSlot: String? = null
        var bestDays = 0
        for ((key, days) in slot) {
            if (existing.contains(key)) continue
            if (days.size > bestDays) {
                bestDays = days.size
                bestSlot = key
            }
        }
        if (bestSlot != null && bestDays >= 3) {
            val hh = bestSlot.substring(0, 2).toIntOrNull() ?: 0
            val mm = bestSlot.substring(3, 5).toIntOrNull() ?: 0
            out.add(
                Item(
                    K_SCHEDULE,
                    "$bestSlot 前後の放送が ${bestDays}日 繰り返されています",
                    "定時放送として予約に登録できます。",
                    target = slotTarget[bestSlot] ?: "all",
                    hour = hh,
                    minute = mm,
                    text = slotText[bestSlot] ?: "定時放送"
                )
            )
        }

        // 3) お気に入り定型文
        val fav = pickTop(tagCount)
        if (fav != null && (tagCount[fav] ?: 0) >= 3) {
            out.add(
                Item(
                    K_FAVORITE,
                    "よく使う定型文: 「$fav」",
                    "${tagCount[fav]} 回使用。タップで現在の対象へ送信します。",
                    text = fav
                )
            )
        }

        return out
    }

    private fun pickTop(m: Map<String, Int>): String? {
        var best: String? = null
        var n = 0
        for ((key, v) in m) {
            if (v > n) {
                n = v; best = key
            }
        }
        return best
    }

    /** 定型文の本文をタイトルから引く */
    fun bodyOf(store: Store, title: String): String {
        val a = store.templates()
        var i = 0
        while (i < a.length()) {
            val o: JSONObject = a.getJSONObject(i)
            if (o.optString("title") == title) return o.optString("body")
            i++
        }
        return title
    }
}
