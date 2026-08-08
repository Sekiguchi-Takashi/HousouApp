package com.appathy.housou

import org.json.JSONArray
import org.json.JSONObject

/**
 * 故障予兆検知。
 *
 * 端末ごとの観測値を5分刻みで蓄積し、単発の異常ではなく
 * 「悪化しつつある」状態を回帰の傾きから捉える。
 * Diag が現在値の評価なのに対し、Trend は時間変化を見る。
 */
object Trend {

    private const val INTERVAL_MS = 5 * 60 * 1000L
    private const val KEEP = 144            // 5分 × 144 = 12時間
    private const val WINDOW = 24           // 直近2時間で判定

    class Omen(val level: Int, val title: String, val detail: String, val devId: String)

    private var lastSample = 0L

    /** ポーリングごとに呼ぶ。実際に記録するのは5分に1回 */
    fun sample(store: Store, list: List<Dev>) {
        val now = System.currentTimeMillis()
        if (now - lastSample < INTERVAL_MS) return
        lastSample = now

        val root = read(store)
        for (d in list) {
            if (d.id.isEmpty()) continue
            val a = root.optJSONArray(d.id) ?: JSONArray()
            val o = JSONObject()
            o.put("at", now)
            o.put("s", Diag.score(d))
            o.put("b", d.battery)
            o.put("r", d.rssi)
            o.put("t", d.rtt)
            o.put("o", if (d.online) 1 else 0)
            a.put(o)
            root.put(d.id, tail(a, KEEP))
        }
        store.setRaw("trend", root.toString())
    }

    private fun read(store: Store): JSONObject = try {
        JSONObject(store.raw("trend", "{}"))
    } catch (e: Exception) {
        JSONObject()
    }

    private fun tail(a: JSONArray, n: Int): JSONArray {
        if (a.length() <= n) return a
        val out = JSONArray()
        var i = a.length() - n
        while (i < a.length()) {
            out.put(a.get(i)); i++
        }
        return out
    }

    /** グラフ用（直近48点のスコア推移） */
    fun series(store: Store, devId: String, key: String): List<Float> {
        val a = read(store).optJSONArray(devId) ?: return emptyList()
        val out = ArrayList<Float>()
        var i = maxOf(0, a.length() - 48)
        while (i < a.length()) {
            out.add(a.getJSONObject(i).optInt(key, 0).toFloat()); i++
        }
        return out
    }

    fun points(store: Store, devId: String): Int =
        read(store).optJSONArray(devId)?.length() ?: 0

    // -------------------------------------------------------------- 判定
    fun omens(store: Store, list: List<Dev>): List<Omen> {
        val root = read(store)
        val out = ArrayList<Omen>()
        for (d in list) {
            val a = root.optJSONArray(d.id) ?: continue
            val n = a.length()
            if (n < 6) continue
            val from = maxOf(0, n - WINDOW)
            val rows = ArrayList<JSONObject>()
            var i = from
            while (i < n) {
                rows.add(a.getJSONObject(i)); i++
            }
            val spanMin = (rows.last().optLong("at") - rows.first().optLong("at")) / 60000.0
            if (spanMin < 20) continue

            // 1) 電池の減り方から枯渇時刻を予測
            val bs = rows.filter { it.optInt("b", -1) >= 0 }
            if (bs.size >= 4) {
                val slope = slope(bs.map { it.optLong("at").toDouble() }, bs.map { it.optInt("b").toDouble() })
                val perHour = slope * 3600000.0
                val cur = bs.last().optInt("b")
                if (perHour < -1.0 && cur < 80) {
                    val hours = cur / -perHour
                    if (hours < 6.0) {
                        out.add(
                            Omen(
                                if (hours < 2.0) 3 else 2,
                                "${d.label()} は約${fmtHours(hours)}で電池切れ",
                                "毎時 ${"%.1f".format(-perHour)}% で減少中（現在 ${cur}%）。給電を手配してください。",
                                d.id
                            )
                        )
                    }
                }
            }

            // 2) 電波の低下傾向
            val rs = rows.filter { it.optInt("r", 0) != 0 }
            if (rs.size >= 6) {
                val slope = slope(rs.map { it.optLong("at").toDouble() }, rs.map { it.optInt("r").toDouble() })
                val perHour = slope * 3600000.0
                if (perHour <= -5.0) {
                    out.add(
                        Omen(
                            2, "${d.label()} の電波が低下傾向",
                            "毎時 ${"%.0f".format(perHour)}dBm で悪化（現在 ${rs.last().optInt("r")}dBm）。設置位置かAPを確認してください。",
                            d.id
                        )
                    )
                }
            }

            // 3) 応答遅延の悪化
            val ts = rows.filter { it.optInt("t", -1) >= 0 }
            if (ts.size >= 6) {
                val first = ts.take(ts.size / 2).map { it.optInt("t") }.average()
                val late = ts.drop(ts.size / 2).map { it.optInt("t") }.average()
                if (late - first >= 80 && late >= 150) {
                    out.add(
                        Omen(
                            2, "${d.label()} の応答が悪化",
                            "RTT ${"%.0f".format(first)}ms → ${"%.0f".format(late)}ms。低帯域モードを検討してください。",
                            d.id
                        )
                    )
                }
            }

            // 4) 断続的な切断（フラッピング）
            var flips = 0
            var prev = rows.first().optInt("o", 1)
            for (r in rows) {
                val v = r.optInt("o", 1)
                if (v != prev) flips++
                prev = v
            }
            if (flips >= 4) {
                out.add(
                    Omen(
                        3, "${d.label()} が断続的に切断",
                        "直近${spanMin.toInt()}分で ${flips}回 の接続変化。省電力設定かAPローミングが原因の可能性があります。",
                        d.id
                    )
                )
            }

            // 5) 総合リスクの上昇
            val ss = rows.map { it.optInt("s", 0) }
            if (ss.size >= 8) {
                val a1 = ss.take(ss.size / 2).average()
                val a2 = ss.drop(ss.size / 2).average()
                if (a2 - a1 >= 20 && a2 >= 40) {
                    out.add(
                        Omen(
                            1, "${d.label()} のリスクが上昇中",
                            "スコア ${"%.0f".format(a1)} → ${"%.0f".format(a2)}。早めの点検を推奨します。",
                            d.id
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.level }
    }

    private fun fmtHours(h: Double): String =
        if (h < 1.0) "${(h * 60).toInt()}分" else "${"%.1f".format(h)}時間"

    /** 最小二乗法の傾き */
    private fun slope(xs: List<Double>, ys: List<Double>): Double {
        val n = xs.size
        if (n < 2) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        var i = 0
        while (i < n) {
            val dx = xs[i] - mx
            num += dx * (ys[i] - my)
            den += dx * dx
            i++
        }
        if (den == 0.0) return 0.0
        return num / den
    }

    fun clear(store: Store) {
        store.setRaw("trend", "{}")
        lastSample = 0L
    }
}
