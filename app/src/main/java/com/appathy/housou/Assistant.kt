package com.appathy.housou

/**
 * AIアシスタント（ローカル推論・オフライン動作）。
 * 日本語の指示文を放送室アプリの操作意図に写像する。
 */
object Assistant {

    const val A_NONE = 0
    const val A_BROADCAST = 1     // 放送画面を対象付きで開く
    const val A_EMERGENCY = 2     // 緊急放送
    const val A_CHIME = 3         // チャイム
    const val A_SPEAK = 4         // テキスト読み上げ送信
    const val A_LOG = 5           // ログ表示
    const val A_SCHEDULE = 6      // スケジュール登録
    const val A_DEVICES = 7       // 端末一覧
    const val A_CALL = 8          // 通話
    const val A_STATUS = 9        // 状態要約

    class Result {
        var action = A_NONE
        var floor = -1
        var group = ""
        var deviceName = ""
        var text = ""
        var hour = -1
        var minute = 0
        var daily = false
        var weekday = false
        var reply = ""
    }

    private val KANJI = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    private fun kanjiNum(s: String): Int {
        // 「十」「二十三」など簡易対応
        if (s.isEmpty()) return -1
        if (s.all { it.isDigit() }) return s.toInt()
        var total = 0
        var cur = 0
        var used = false
        for (ch in s) {
            when {
                KANJI.containsKey(ch) -> {
                    cur = KANJI[ch] ?: 0; used = true
                }
                ch == '十' -> {
                    total += (if (cur == 0) 1 else cur) * 10; cur = 0; used = true
                }
                else -> return -1
            }
        }
        if (!used) return -1
        return total + cur
    }

    fun parse(raw: String): Result {
        val r = Result()
        val t = raw.trim()
        if (t.isEmpty()) {
            r.reply = "指示を入力してください。"
            return r
        }
        val z = normalize(t)

        // ---- 対象フロア
        val mf = Regex("([0-9一二三四五六七八九十〇零]+)\\s*(階|F|f)").find(z)
        if (mf != null) {
            val n = kanjiNum(mf.groupValues[1])
            if (n > 0) r.floor = n
        }
        // ---- グループ
        val mg = Regex("(グループ|班)\\s*([\\p{L}\\p{N}]{1,10})").find(z)
        if (mg != null) r.group = mg.groupValues[2]

        // ---- 時刻
        val mt = Regex("([0-9]{1,2})\\s*[:：時]\\s*([0-9]{1,2})?").find(z)
        if (mt != null) {
            r.hour = mt.groupValues[1].toIntOrNull() ?: -1
            r.minute = mt.groupValues[2].toIntOrNull() ?: 0
        }
        r.daily = z.contains("毎日")
        r.weekday = z.contains("平日")

        val all = z.contains("全館") || z.contains("全端末") || z.contains("全部") ||
                z.contains("すべて") || z.contains("全て")

        // ---- 意図判定（優先度順）
        when {
            z.contains("緊急") || z.contains("避難") || z.contains("警報") -> {
                r.action = A_EMERGENCY
                r.text = extractQuoted(z)
                r.reply = "緊急放送を準備しました。" + targetWord(r, all)
            }

            (z.contains("登録") || z.contains("予約") || z.contains("セット")) && r.hour >= 0 -> {
                r.action = A_SCHEDULE
                r.text = scheduleBody(z)
                r.reply = String.format(
                    "%s %02d:%02d に「%s」を登録します。%s",
                    if (r.weekday) "平日" else if (r.daily) "毎日" else "本日",
                    r.hour, r.minute, r.text, targetWord(r, all)
                )
            }

            z.contains("履歴") || z.contains("ログ") -> {
                r.action = A_LOG
                r.reply = "放送履歴を表示します。"
            }

            z.contains("端末") && (z.contains("一覧") || z.contains("状態") || z.contains("確認")) -> {
                r.action = A_DEVICES
                r.reply = "端末一覧を表示します。"
            }

            z.contains("チャイム") || z.contains("鐘") -> {
                r.action = A_CHIME
                r.reply = "チャイムを鳴らします。" + targetWord(r, all)
            }

            z.contains("通話") || z.contains("呼び出") || z.contains("インターホン") -> {
                r.action = A_CALL
                r.reply = "通話画面を開きます。" + targetWord(r, all)
            }

            z.contains("読み上げ") || z.contains("読上") || z.contains("アナウンス") ||
                    z.contains("と伝え") || z.contains("と放送") -> {
                r.action = A_SPEAK
                r.text = extractQuoted(z).ifEmpty { stripCommand(z) }
                r.reply = "「${r.text}」を読み上げます。" + targetWord(r, all)
            }

            z.contains("放送") || z.contains("マイク") -> {
                r.action = A_BROADCAST
                r.reply = "放送画面を開きます。" + targetWord(r, all)
            }

            z.contains("状況") || z.contains("調子") || z.contains("問題") -> {
                r.action = A_STATUS
                r.reply = "システム状態を確認します。"
            }

            else -> {
                r.action = A_NONE
                r.reply = "解釈できませんでした。例:「全館放送を開始」「3階だけ放送」「今日の放送履歴を表示」「朝礼を毎日8:30に登録」"
            }
        }
        return r
    }

    private fun targetWord(r: Result, all: Boolean): String = when {
        r.floor > 0 -> "対象: ${r.floor}階"
        r.group.isNotEmpty() -> "対象: グループ「${r.group}」"
        all -> "対象: 全館"
        else -> "対象: 全館（既定）"
    }

    private fun scheduleBody(z: String): String {
        val q = extractQuoted(z)
        if (q.isNotEmpty()) return q
        val m = Regex("^(.{1,20}?)(を|は)").find(z)
        if (m != null) return m.groupValues[1]
        return "定時放送"
    }

    private fun extractQuoted(z: String): String {
        val m = Regex("[「『\"]([^」』\"]{1,200})[」』\"]").find(z)
        return if (m != null) m.groupValues[1] else ""
    }

    private fun stripCommand(z: String): String {
        return z.replace(Regex("(全館|全端末|全部|すべて|全て)"), "")
            .replace(Regex("[0-9一二三四五六七八九十]+\\s*(階|F|f)"), "")
            .replace(Regex("(で|に|へ|を|は|だけ|のみ)?\\s*(読み上げて|読み上げ|放送して|放送|アナウンスして|アナウンス|と伝えて|伝えて)\\s*$"), "")
            .trim()
    }

    /** 全角英数を半角に、記号を統一 */
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = when {
                ch.code in 0xFF10..0xFF19 -> (ch.code - 0xFF10 + '0'.code).toChar()
                ch.code in 0xFF21..0xFF3A -> (ch.code - 0xFF21 + 'A'.code).toChar()
                ch.code in 0xFF41..0xFF5A -> (ch.code - 0xFF41 + 'a'.code).toChar()
                ch == '　' -> ' '
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
