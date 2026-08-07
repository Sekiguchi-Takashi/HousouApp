package com.appathy.housou

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Ui {
    const val BG = 0xFF0D1117.toInt()
    const val CARD = 0xFF161B22.toInt()
    const val CARD2 = 0xFF1E2733.toInt()
    const val LINE = 0xFF2A3441.toInt()
    const val FG = 0xFFE6EDF3.toInt()
    const val SUB = 0xFF8B98A5.toInt()
    const val ACC = 0xFFFFC24A.toInt()
    const val CYAN = 0xFF5AC8FA.toInt()
    const val RED = 0xFFFF5A5A.toInt()
    const val GREEN = 0xFF4CD964.toInt()
    const val DARKTXT = 0xFF101418.toInt()

    val MP = ViewGroup.LayoutParams.MATCH_PARENT
    val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun lp(w: Int, h: Int, top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = top
        return p
    }

    fun tv(
        c: Context,
        s: String,
        size: Float = 14f,
        color: Int = FG,
        bold: Boolean = false
    ): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    fun col(c: Context, padDp: Int = 0): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        if (padDp > 0) {
            val p = dp(c, padDp)
            l.setPadding(p, p, p, p)
        }
        return l
    }

    fun row(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun card(c: Context, bg: Int = CARD): LinearLayout {
        val l = col(c, 14)
        val g = GradientDrawable()
        g.cornerRadius = dp(c, 14).toFloat()
        g.setColor(bg)
        g.setStroke(dp(c, 1), LINE)
        l.background = g
        return l
    }

    fun pill(c: Context, text: String, bg: Int, fg: Int): TextView {
        val t = tv(c, text, 11f, fg, true)
        val g = GradientDrawable()
        g.cornerRadius = dp(c, 20).toFloat()
        g.setColor(bg)
        t.background = g
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4))
        return t
    }

    fun btn(
        c: Context,
        text: String,
        bg: Int = ACC,
        fg: Int = DARKTXT,
        size: Float = 15f,
        onClick: () -> Unit
    ): TextView {
        val t = tv(c, text, size, fg, true)
        t.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = dp(c, 12).toFloat()
        g.setColor(bg)
        t.background = g
        val pv = dp(c, 13)
        t.setPadding(dp(c, 16), pv, dp(c, 16), pv)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun ghost(c: Context, text: String, fg: Int = FG, onClick: () -> Unit): TextView {
        val t = tv(c, text, 14f, fg, true)
        t.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = dp(c, 12).toFloat()
        g.setColor(Color.TRANSPARENT)
        g.setStroke(dp(c, 1), LINE)
        t.background = g
        val pv = dp(c, 11)
        t.setPadding(dp(c, 14), pv, dp(c, 14), pv)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun edit(c: Context, hint: String, value: String = "", numeric: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setText(value)
        e.setTextColor(FG)
        e.setHintTextColor(SUB)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.setSingleLine(true)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER
        val g = GradientDrawable()
        g.cornerRadius = dp(c, 10).toFloat()
        g.setColor(CARD2)
        g.setStroke(dp(c, 1), LINE)
        e.background = g
        e.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11))
        return e
    }

    fun sep(c: Context): View {
        val v = View(c)
        v.setBackgroundColor(LINE)
        val p = LinearLayout.LayoutParams(MP, dp(c, 1))
        p.topMargin = dp(c, 10)
        p.bottomMargin = dp(c, 10)
        v.layoutParams = p
        return v
    }

    fun scroll(c: Context, inner: View): ScrollView {
        val s = ScrollView(c)
        s.isFillViewport = true
        s.addView(inner, ViewGroup.LayoutParams(MP, WC))
        return s
    }

    fun space(c: Context, hDp: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(MP, dp(c, hDp))
        return v
    }
}
