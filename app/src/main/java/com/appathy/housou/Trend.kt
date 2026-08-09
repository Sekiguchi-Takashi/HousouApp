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

    /** 端末ごとの平常値 */
    class Base(val n: Int, val rssiMean: Double, val rssiSd: Double, val rttMean: Double, val rttSd: Double) {
        fun ready(): Boolean = n >= 20
        fun text(): String {
            if (!ready()) return "学習中（${n}/20サンプル）"
            return "電波 ${"%.0f".format(rssiMean)}±${"%.0f".format(rssiSd)}dBm / " +
                    "応答 ${"%.0f".format(rttMean)}±${"%.0f".format(rttSd)}ms"
        }
    }

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

    /** 期間内の (サンプル数, オンラインだった数) */
    fun availability(store: Store, devId: String, from: Long, to: Long): Pair<Int, Int> {
        val a = read(store).optJSONArray(devId) ?: return Pair(0, 0)
        var n = 0
        var ok = 0
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            val at = o.optLong("at")
            if (at < from || at >= to) continue
            n++
            if (o.optInt("o", 0) == 1) ok++
        }
        return Pair(n, ok)
    }

    fun points(store: Store, devId: String): Int =
        read(store).optJSONArray(devId)?.length() ?: 0

    // -------------------------------------------------------------- 平常値
    /**
     * 蓄積した観測値から、その端末にとっての平常値を求める。
     * 固定閾値だと設置環境の違いを吸収できないため、
     * 「その端末のいつも」からどれだけ外れたかで判定する。
     * 外れ値の影響を抑えるため中央値と四分位範囲を使う。
     */
    fun baseline(store: Store, devId: String): Base {
        val a = read(store).optJSONArray(devId) ?: return Base(0, 0.0, 0.0, 0.0, 0.0)
        val rs = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optInt("o", 0) != 1) continue
            val r = o.optInt("r", 0)
            if (r != 0) rs.add(r.toDouble())
            val t = o.optInt("t", -1)
            if (t >= 0) ts.add(t.toDouble())
        }
        val n = maxOf(rs.size, ts.size)
        return Base(n, med(rs), spread(rs), med(ts), spread(ts))
    }

    private fun med(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** 四分位範囲から標準偏差相当を出す。最低値を設けて過敏化を防ぐ */
    private fun spread(v: List<Double>): Double {
        if (v.size < 4) return 0.0
        val s = v.sorted()
        val q1 = s[s.size / 4]
        val q3 = s[s.size * 3 / 4]
        val sd = (q3 - q1) / 1.349
        return maxOf(sd, 1.0)
    }

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

            // 5) 平常値からの逸脱（学習済みの端末のみ）
            val base = baseline(store, d.id)
            if (base.ready() && d.online) {
                if (d.rssi != 0 && base.rssiSd > 0) {
                    val z = (base.rssiMean - d.rssi) / base.rssiSd
                    if (z >= 3.0) {
                        out.add(
                            Omen(
                                2, "${d.label()} の電波が平常より弱い",
                                "いつもは ${"%.0f".format(base.rssiMean)}dBm 前後ですが現在 ${d.rssi}dBm。" +
                                        "設置位置がずれたか、周囲の環境が変わった可能性があります。",
                                d.id
                            )
                        )
                    }
                }
                if (d.rtt >= 0 && base.rttSd > 0) {
                    val z = (d.rtt - base.rttMean) / base.rttSd
                    if (z >= 3.0 && d.rtt > base.rttMean + 40) {
                        out.add(
                            Omen(
                                2, "${d.label()} の応答が平常より遅い",
                                "いつもは ${"%.0f".format(base.rttMean)}ms 前後ですが現在 ${d.rtt}ms。" +
                                        "ネットワーク側の変化が疑われます。",
                                d.id
                            )
                        )
                    }
                }
            }

            // 6) 総合リスクの上昇
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
