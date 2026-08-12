package com.appathy.housou

import java.util.Locale

/** 多言語放送で使える第2言語 */
object Lang {

    class L(val code: String, val name: String, val locale: Locale)

    val all: List<L> = listOf(
        L("en", "英語", Locale.ENGLISH),
        L("zh", "中国語", Locale.SIMPLIFIED_CHINESE),
        L("ko", "韓国語", Locale.KOREAN),
        L("vi", "ベトナム語", Locale("vi", "VN")),
        L("pt", "ポルトガル語", Locale("pt", "BR")),
        L("es", "スペイン語", Locale("es", "ES")),
        L("id", "インドネシア語", Locale("in", "ID")),
        L("fr", "フランス語", Locale.FRENCH)
    )

    fun byCode(c: String): L? = all.firstOrNull { it.code == c }

    fun label(c: String): String = byCode(c)?.name ?: c
}
