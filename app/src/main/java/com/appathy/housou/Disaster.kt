package com.appathy.housou

import org.json.JSONObject

/**
 * 災害時自動放送。
 *
 * シナリオを選ぶと、対象端末を最大音量にして警報チャイム＋読み上げを
 * 指定回数くり返す。端末側は警報表示に切り替わる。
 * 停止するまで繰り返すので、避難誘導のように継続が要る場面で使う。
 */
object Disaster {

    class Scenario(
        val id: String,
        val icon: String,
        val name: String,
        val text: String,
        val repeat: Int,
        val intervalSec: Int
    )

    val all: List<Scenario> = listOf(
        Scenario(
            "quake", "🌏", "地震",
            "地震が発生しました。落ち着いて身の安全を確保してください。机の下に入り、頭を守ってください。",
            5, 25
        ),
        Scenario(
            "fire", "🔥", "火災",
            "火災が発生しました。ただちに避難してください。エレベーターは使用しないでください。",
            8, 20
        ),
        Scenario(
            "evac", "🚪", "避難誘導",
            "避難を開始してください。係員の誘導に従い、落ち着いて最寄りの非常口へ向かってください。",
            10, 20
        ),
        Scenario(
            "intruder", "⚠", "不審者",
            "館内で緊急事態が発生しています。各自その場で待機し、施錠のうえ指示をお待ちください。",
            6, 25
        ),
        Scenario(
            "tsunami", "🌊", "津波",
            "津波警報が発表されました。ただちに高い場所へ避難してください。海岸に近づかないでください。",
            10, 20
        ),
        Scenario(
            "drill", "📣", "訓練",
            "これは訓練放送です。ただいまより避難訓練を実施します。係員の指示に従ってください。",
            3, 30
        )
    )

    fun byId(id: String): Scenario? = all.firstOrNull { it.id == id }

    fun toJson(s: Scenario): JSONObject {
        val o = JSONObject()
        o.put("id", s.id); o.put("name", s.name)
        o.put("text", s.text); o.put("repeat", s.repeat)
        o.put("interval", s.intervalSec)
        return o
    }
}
