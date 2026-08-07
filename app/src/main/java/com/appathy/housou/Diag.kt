package com.appathy.housou

/**
 * AI推論ルール層。
 * 端末ごとの観測値（オフライン, RSSI, バッテリ, RTT, 損失率）から
 * リスクスコアと推奨アクションを導出する。
 */
object Diag {

    class Alert(val level: Int, val title: String, val detail: String)

    /** 0(健全) - 100(危険) */
    fun score(d: Dev): Int {
        var s = 0
        if (!d.online) return 100

        // Wi-Fi品質
        val r = d.rssi
        if (r != 0) {
            when {
                r <= -85 -> s += 40
                r <= -75 -> s += 25
                r <= -67 -> s += 12
            }
        }
        // バッテリー
        val b = d.battery
        if (b in 0..14) s += 30
        else if (b in 15..29) s += 15

        // 応答遅延
        val t = d.rtt
        when {
            t < 0 -> s += 20
            t > 400 -> s += 25
            t > 180 -> s += 12
        }
        // 推定損失
        s += (d.loss.coerceIn(0, 30))

        // 機能停止
        if (!d.spkOn) s += 20
        if (!d.micOn) s += 5

        return s.coerceIn(0, 100)
    }

    fun rank(s: Int): String = when {
        s >= 70 -> "危険"
        s >= 40 -> "注意"
        s >= 20 -> "軽微"
        else -> "良好"
    }

    fun color(s: Int): Int = when {
        s >= 70 -> Ui.RED
        s >= 40 -> Ui.ACC
        s >= 20 -> Ui.CYAN
        else -> Ui.GREEN
    }

    /** 端末群から通知リストを生成 */
    fun alerts(list: List<Dev>): List<Alert> {
        val out = ArrayList<Alert>()
        for (d in list) {
            if (!d.online) {
                out.add(
                    Alert(
                        3, "${d.label()} がオフライン",
                        "推定原因: " + guessCause(d) + " / 自動再接続を継続中"
                    )
                )
                continue
            }
            if (d.rssi != 0 && d.rssi <= -80) {
                out.add(Alert(2, "${d.label()} の電波が弱い", "RSSI ${d.rssi}dBm。設置位置かAP増設を検討"))
            }
            if (d.battery in 0..19) {
                out.add(Alert(2, "${d.label()} のバッテリー残量低下", "残り${d.battery}%。常時給電を推奨"))
            }
            if (d.rtt > 300) {
                out.add(Alert(1, "${d.label()} の応答が遅い", "RTT ${d.rtt}ms。低帯域モードを推奨"))
            }
            if (!d.spkOn) {
                out.add(Alert(2, "${d.label()} のスピーカーが無効", "端末詳細から再有効化してください"))
            }
        }
        return out.sortedByDescending { it.level }
    }

    private fun guessCause(d: Dev): String {
        if (d.battery in 0..9) return "バッテリー切れ"
        if (d.rssi != 0 && d.rssi <= -85) return "Wi-Fi圏外"
        if (d.rtt > 400) return "ネットワーク輻輳"
        return "端末スリープ / AP切断"
    }

    /** 帯域の推奨 */
    fun suggestQuality(list: List<Dev>): String {
        val on = list.filter { it.online && it.rtt >= 0 }
        if (on.isEmpty()) return "high"
        val avg = on.sumOf { it.rtt } / on.size
        val weak = on.count { it.rssi != 0 && it.rssi <= -78 }
        return if (avg > 180 || weak >= on.size / 2 && weak > 0) "low" else "high"
    }

    fun summary(list: List<Dev>): String {
        if (list.isEmpty()) return "端末が未登録です。フロア端末側でアプリを起動すると自動検出されます。"
        val on = list.count { it.online }
        val worst = list.maxOfOrNull { score(it) } ?: 0
        val q = suggestQuality(list)
        val qt = if (q == "low") "低帯域モード推奨" else "高音質モードで問題なし"
        return "オンライン ${on}/${list.size} 台・最大リスク ${rank(worst)}（${worst}）・${qt}"
    }
}
