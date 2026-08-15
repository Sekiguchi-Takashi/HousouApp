package com.appathy.housou

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理コンソール本体。
 * タブ: ダッシュボード / 放送 / 通話 / 端末 / 予約 / ログ / 設定
 */
class ConsoleUi(private val act: MainActivity, private val store: Store) {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabRow: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var attached = false
    private var tab = 0

    private var targetSpec = "all"
    private var callTarget: Dev? = null
    private var alwaysTalk = false
    private var callHeld = false
    private var secondLang = ""
    private var secondText = ""
    private var noticeSec = 30
    private var assistantReply = ""

    private val refreshers = ArrayList<() -> Unit>()

    private val tabNames = arrayOf("状況", "放送", "通話", "端末", "音源", "予約", "手首", "ログ", "設定")
    private val tabIcons = arrayOf("📊", "📢", "🎙", "📱", "🎵", "⏰", "⌚", "📜", "⚙")

    // ------------------------------------------------------------ 基盤
    fun attach(container: FrameLayout) {
        root = container
        attached = true
        startService()
        ConsoleService.onUpdate = { refresh() }

        val col = Ui.col(act)
        col.setBackgroundColor(Ui.BG)
        col.addView(header(), Ui.lp(Ui.MP, Ui.WC))
        content = FrameLayout(act)
        col.addView(content, LinearLayout.LayoutParams(Ui.MP, 0, 1f))
        tabRow = buildTabs()
        col.addView(tabRow, Ui.lp(Ui.MP, Ui.WC))

        root.removeAllViews()
        root.addView(col)
        render()
        tick()
    }

    fun detach() {
        attached = false
        ConsoleService.onUpdate = null
    }

    fun onBack(): Boolean {
        if (tab != 0) {
            tab = 0
            render()
            return true
        }
        return false
    }

    private fun startService() {
        val i = Intent(act, ConsoleService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.startForegroundService(i)
            } else {
                act.startService(i)
            }
        } catch (e: Exception) { }
    }

    private fun tick() {
        if (!attached) return
        refresh()
        h.postDelayed({ tick() }, 1500)
    }

    private fun refresh() {
        if (!attached) return
        for (f in refreshers) {
            try { f() } catch (e: Exception) { }
        }
    }

    private fun bg(f: () -> Unit) {
        Thread {
            try { f() } catch (e: Exception) { }
        }.start()
    }

    private fun ui(f: () -> Unit) {
        h.post {
            if (attached) {
                try { f() } catch (e: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ ヘッダ/タブ
    private fun header(): View {
        val l = Ui.row(act)
        l.setBackgroundColor(Ui.CARD)
        l.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12))

        val left = Ui.col(act)
        left.addView(Ui.tv(act, store.building, 17f, Ui.FG, true))
        val sub = Ui.tv(act, "", 11f, Ui.SUB)
        left.addView(sub)
        l.addView(left, LinearLayout.LayoutParams(0, Ui.WC, 1f))

        val badge = Ui.pill(act, "—", Ui.CARD2, Ui.SUB)
        l.addView(badge)

        refreshers.add {
            val all = Registry.all()
            val on = all.count { it.online }
            sub.text = "コンソール ${Net.localIp()}　端末 ${on}/${all.size} オンライン"
            val worst = all.maxOfOrNull { Diag.score(it) } ?: 0
            badge.text = if (all.isEmpty()) "端末なし" else Diag.rank(worst)
            val g = GradientDrawable()
            g.cornerRadius = Ui.dp(act, 20).toFloat()
            g.setColor(if (all.isEmpty()) Ui.CARD2 else Diag.color(worst))
            badge.background = g
            badge.setTextColor(if (all.isEmpty()) Ui.SUB else Ui.DARKTXT)
        }
        return l
    }

    private fun buildTabs(): LinearLayout {
        val r = Ui.row(act)
        r.setBackgroundColor(Ui.CARD)
        r.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        var i = 0
        while (i < tabNames.size) {
            val idx = i
            val cell = Ui.col(act)
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
            val ic = Ui.tv(act, tabIcons[idx], 17f)
            ic.gravity = Gravity.CENTER
            val tx = Ui.tv(act, tabNames[idx], 9f, Ui.SUB)
            tx.gravity = Gravity.CENTER
            cell.addView(ic)
            cell.addView(tx)
            cell.isClickable = true
            cell.setOnClickListener {
                tab = idx
                render()
            }
            r.addView(cell, LinearLayout.LayoutParams(0, Ui.WC, 1f))
            i++
        }
        return r
    }

    private fun paintTabs() {
        var i = 0
        while (i < tabRow.childCount) {
            val cell = tabRow.getChildAt(i) as LinearLayout
            val tx = cell.getChildAt(1) as TextView
            tx.setTextColor(if (i == tab) Ui.ACC else Ui.SUB)
            cell.alpha = if (i == tab) 1.0f else 0.65f
            i++
        }
    }

    private fun render() {
        if (!attached) return
        refreshers.clear()
        refreshers.add { }
        val v = when (tab) {
            0 -> tabDash()
            1 -> tabBroadcast()
            2 -> tabCall()
            3 -> tabDevices()
            4 -> tabLibrary()
            5 -> tabSchedule()
            6 -> tabWrist()
            7 -> tabLog()
            else -> tabSettings()
        }
        content.removeAllViews()
        content.addView(v)
        // ヘッダの更新関数を再登録
        content.post { }
        paintTabs()
        rebuildHeaderRefresher()
        refresh()
    }

    private var headerSub: TextView? = null

    private fun rebuildHeaderRefresher() {
        // header() 内で登録済みの refresher は render() でクリアされるため再構築する
        val hdr = (root.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
        val left = hdr.getChildAt(0) as LinearLayout
        val sub = left.getChildAt(1) as TextView
        val badge = hdr.getChildAt(1) as TextView
        val title = left.getChildAt(0) as TextView
        headerSub = sub
        refreshers.add {
            title.text = store.building
            val all = Registry.all()
            val on = all.count { it.online }
            sub.text = "コンソール ${Net.localIp()}　端末 ${on}/${all.size} オンライン"
            val worst = all.maxOfOrNull { Diag.score(it) } ?: 0
            badge.text = if (all.isEmpty()) "端末なし" else Diag.rank(worst)
            val g = GradientDrawable()
            g.cornerRadius = Ui.dp(act, 20).toFloat()
            g.setColor(if (all.isEmpty()) Ui.CARD2 else Diag.color(worst))
            badge.background = g
            badge.setTextColor(if (all.isEmpty()) Ui.SUB else Ui.DARKTXT)
        }
    }

    // ============================================================ 0 ダッシュボード
    private fun tabDash(): View {
        val l = Ui.col(act, 14)

        // AIアシスタント
        val c0 = Ui.card(act, Ui.CARD2)
        c0.addView(Ui.tv(act, "🤖 AIアシスタント", 16f, Ui.ACC, true))
        c0.addView(
            Ui.tv(act, "例:「全館放送を開始」「3階だけ放送」「今日の放送履歴を表示」「朝礼を毎日8:30に登録」", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        val inp = Ui.edit(act, "指示を入力")
        c0.addView(inp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val reply = Ui.tv(act, assistantReply, 12f, Ui.CYAN)
        c0.addView(Ui.btn(act, "実行") {
            runAssistant(inp.text.toString(), reply)
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        c0.addView(reply, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c0)

        // 概況
        val c1 = Ui.card(act)
        c1.addView(Ui.tv(act, "📊 システム状況", 16f, Ui.FG, true))
        val sum = Ui.tv(act, "", 13f, Ui.SUB)
        c1.addView(sum, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        val stats = Ui.row(act)
        c1.addView(stats, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        val s1 = statCell("端末", "0")
        val s2 = statCell("オンライン", "0")
        val s3 = statCell("平均RTT", "-")
        stats.addView(s1, LinearLayout.LayoutParams(0, Ui.WC, 1f))
        stats.addView(s2, LinearLayout.LayoutParams(0, Ui.WC, 1f))
        stats.addView(s3, LinearLayout.LayoutParams(0, Ui.WC, 1f))
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 災害放送バナー
        val cD = Ui.card(act, 0xFF3A0E0E.toInt())
        val dText = Ui.tv(act, "", 15f, Ui.FG, true)
        cD.addView(dText)
        cD.addView(Ui.ghost(act, "■ 災害放送を停止", Ui.RED) {
            ConsoleService.instance?.stopDisaster()
            act.toast("停止しました")
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(cD, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 月次稼働率（SLA）
        val cA = Ui.card(act)
        cA.addView(Ui.tv(act, "📈 今月の稼働率", 16f, Ui.FG, true))
        val slaMain = Ui.tv(act, "", 32f, Ui.GREEN, true)
        cA.addView(slaMain, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        val slaSub = Ui.tv(act, "", 11f, Ui.SUB)
        cA.addView(slaSub, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        cA.addView(Ui.ghost(act, "内訳を見る", Ui.CYAN) { slaDialog() }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(cA, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 故障予兆
        val cO = Ui.card(act)
        cO.addView(Ui.tv(act, "📉 故障予兆", 16f, Ui.FG, true))
        val omens = Ui.col(act)
        cO.addView(omens, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(cO, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // AI放送支援
        val cS = Ui.card(act)
        cS.addView(Ui.tv(act, "💡 AIのおすすめ", 16f, Ui.FG, true))
        val sug = Ui.col(act)
        cS.addView(sug, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(cS, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 通知
        val c2 = Ui.card(act)
        c2.addView(Ui.tv(act, "🔔 通知 / AI推論", 16f, Ui.FG, true))
        val alerts = Ui.col(act)
        c2.addView(alerts, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // クイック
        val c3 = Ui.card(act)
        c3.addView(Ui.tv(act, "⚡ クイック操作", 16f, Ui.FG, true))
        c3.addView(Ui.btn(act, "🔔 全館チャイム", Ui.CYAN) {
            sendChime("all", false)
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        c3.addView(Ui.btn(act, "🚨 緊急放送", Ui.RED, Ui.FG) {
            emergencyDialog()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c3, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            val all = Registry.all()
            sum.text = Diag.summary(all)
            (s1.getChildAt(1) as TextView).text = all.size.toString()
            (s2.getChildAt(1) as TextView).text = all.count { it.online }.toString()
            val on = all.filter { it.online && it.rtt >= 0 }
            (s3.getChildAt(1) as TextView).text =
                if (on.isEmpty()) "-" else (on.sumOf { it.rtt } / on.size).toString() + "ms"

            cD.visibility = if (ConsoleService.disasterOn) View.VISIBLE else View.GONE
            if (ConsoleService.disasterOn) {
                dText.text = "🚨 ${ConsoleService.disasterName} を放送中" +
                        "（${ConsoleService.disasterRound}/${ConsoleService.disasterTotal}回目）"
            }

            val sla = Report.sla(store)
            if (sla.pct < 0) {
                slaMain.text = "—"
                slaMain.setTextColor(Ui.SUB)
                slaSub.text = "日報が貯まると集計されます（毎日の自動生成をONにしてください）。"
            } else {
                slaMain.text = String.format("%.1f%%", sla.pct)
                slaMain.setTextColor(
                    when {
                        sla.pct >= 99.0 -> Ui.GREEN
                        sla.pct >= 95.0 -> Ui.ACC
                        else -> Ui.RED
                    }
                )
                slaSub.text = "${sla.month} / ${sla.days}日分・${sla.samples}サンプルから算出"
            }

            omens.removeAllViews()
            val om = Trend.omens(store, all)
            if (om.isEmpty()) {
                val n = if (all.isEmpty()) 0 else Trend.points(store, all[0].id)
                omens.addView(
                    Ui.tv(
                        act,
                        if (n < 6) "観測データを蓄積中です（5分ごとに記録、判定には30分ほど必要）。"
                        else "悪化傾向のある端末はありません。",
                        12f, Ui.SUB
                    )
                )
            } else {
                for (o in om) {
                    val row = Ui.col(act)
                    row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
                    row.addView(
                        Ui.tv(act, o.title, 13f, if (o.level >= 3) Ui.RED else Ui.ACC, true)
                    )
                    row.addView(Ui.tv(act, o.detail, 11f, Ui.SUB))
                    omens.addView(row)
                }
            }

            sug.removeAllViews()
            val items = Suggest.build(store)
            if (items.isEmpty()) {
                sug.addView(
                    Ui.tv(act, "放送の実績が貯まると、放送先や定時放送を提案します。", 12f, Ui.SUB)
                )
            } else {
                for (sg in items) {
                    val row = Ui.col(act)
                    row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
                    row.addView(Ui.tv(act, sg.title, 13f, Ui.ACC, true))
                    row.addView(Ui.tv(act, sg.detail, 11f, Ui.SUB))
                    row.addView(Ui.ghost(act, applyLabel(sg), Ui.FG) {
                        applySuggestion(sg)
                    }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                    sug.addView(row)
                }
            }

            alerts.removeAllViews()
            val list = Diag.alerts(all)
            if (list.isEmpty()) {
                alerts.addView(Ui.tv(act, "異常はありません。全端末が正常に応答しています。", 12f, Ui.GREEN))
            } else {
                for (a in list.take(8)) {
                    val row = Ui.col(act)
                    row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
                    val c = if (a.level >= 3) Ui.RED else if (a.level == 2) Ui.ACC else Ui.CYAN
                    row.addView(Ui.tv(act, "● " + a.title, 13f, c, true))
                    row.addView(Ui.tv(act, "　 " + a.detail, 11f, Ui.SUB))
                    alerts.addView(row)
                }
            }
        }
        return Ui.scroll(act, l)
    }

    private fun applyLabel(sg: Suggest.Item): String = when (sg.kind) {
        Suggest.K_TARGET -> "この対象にする"
        Suggest.K_SCHEDULE -> "予約に登録する"
        else -> "いま送信する"
    }

    private fun applySuggestion(sg: Suggest.Item) {
        when (sg.kind) {
            Suggest.K_TARGET -> {
                targetSpec = sg.target
                tab = 1
                render()
            }
            Suggest.K_SCHEDULE -> {
                addSchedule(sg.hour, sg.minute, "daily", sg.target, sg.text, Suggest.bodyOf(store, sg.text))
                tab = 5
                render()
            }
            else -> {
                sendTts(targetSpec, Suggest.bodyOf(store, sg.text), false, sg.text)
            }
        }
    }

    private fun statCell(k: String, v: String): LinearLayout {
        val c = Ui.col(act)
        c.gravity = Gravity.CENTER
        c.addView(Ui.tv(act, k, 10f, Ui.SUB).also { it.gravity = Gravity.CENTER })
        c.addView(Ui.tv(act, v, 20f, Ui.ACC, true).also { it.gravity = Gravity.CENTER })
        return c
    }

    private fun runAssistant(text: String, reply: TextView) {
        val r = Assistant.parse(text)
        assistantReply = r.reply
        reply.text = r.reply
        if (r.floor > 0) targetSpec = "floor:${r.floor}"
        else if (r.group.isNotEmpty()) targetSpec = "group:${r.group}"
        else targetSpec = "all"

        when (r.action) {
            Assistant.A_BROADCAST -> { tab = 1; render() }
            Assistant.A_CALL -> { tab = 2; render() }
            Assistant.A_DEVICES -> { tab = 3; render() }
            Assistant.A_LOG -> { tab = 7; render() }
            Assistant.A_CHIME -> sendChime(targetSpec, false)
            Assistant.A_EMERGENCY -> {
                if (r.text.isNotEmpty()) sendTts(targetSpec, r.text, true)
                else emergencyDialog()
            }
            Assistant.A_SPEAK -> {
                if (r.text.isNotEmpty()) sendTts(targetSpec, r.text, false)
                else act.toast("読み上げる文章を「」で囲って指定してください")
            }
            Assistant.A_SCHEDULE -> {
                addSchedule(r.hour, r.minute, if (r.weekday) "weekday" else "daily", targetSpec, r.text, r.text)
                tab = 5
                render()
            }
            Assistant.A_STATUS -> {
                reply.text = Diag.summary(Registry.all())
            }
            else -> { }
        }
        store.log("assistant", "「$text」→ ${r.reply}")
    }

    // ============================================================ 1 放送
    private var pttBtn: TextView? = null

    private fun tabBroadcast(): View {
        val l = Ui.col(act, 14)

        // 対象選択
        val c0 = Ui.card(act)
        c0.addView(Ui.tv(act, "🎯 放送対象", 16f, Ui.FG, true))
        val label = Ui.tv(act, "", 20f, Ui.ACC, true)
        c0.addView(label, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        val count = Ui.tv(act, "", 12f, Ui.SUB)
        c0.addView(count)

        val r1 = Ui.row(act)
        r1.addView(chip("全館") { targetSpec = "all"; render() }, cw())
        r1.addView(chip("フロア") { pickFloor() }, cw())
        r1.addView(chip("グループ") { pickGroup() }, cw())
        r1.addView(chip("建物") { pickBuilding() }, cw())
        r1.addView(chip("個別") { pickDevice() }, cw())
        c0.addView(r1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(c0)

        // PTT
        val c1 = Ui.card(act, Ui.CARD2)
        val ptt = Ui.tv(act, "押している間\nマイク放送", 20f, Ui.DARKTXT, true)
        ptt.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = Ui.dp(act, 100).toFloat()
        g.setColor(Ui.ACC)
        ptt.background = g
        ptt.setPadding(0, Ui.dp(act, 52), 0, Ui.dp(act, 52))
        ptt.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startPtt(); v.alpha = 0.75f; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPtt(); v.alpha = 1f; v.performClick(); true
                }
                else -> false
            }
        }
        pttBtn = ptt
        c1.addView(ptt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val meter = Ui.tv(act, "入力レベル —", 12f, Ui.SUB)
        meter.gravity = Gravity.CENTER
        c1.addView(meter, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 補助
        val c2 = Ui.card(act)
        c2.addView(Ui.tv(act, "🔔 チャイム / 緊急", 16f, Ui.FG, true))
        val r2 = Ui.row(act)
        val b1 = Ui.ghost(act, "チャイム", Ui.CYAN) { sendChime(targetSpec, false) }
        val b2 = Ui.btn(act, "🚨 緊急放送", Ui.RED, Ui.FG) { emergencyDialog() }
        val lp1 = LinearLayout.LayoutParams(0, Ui.WC, 1f)
        lp1.rightMargin = Ui.dp(act, 6)
        val lp2 = LinearLayout.LayoutParams(0, Ui.WC, 1f)
        lp2.leftMargin = Ui.dp(act, 6)
        r2.addView(b1, lp1)
        r2.addView(b2, lp2)
        c2.addView(r2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // AI読み上げ
        val c3 = Ui.card(act)
        c3.addView(Ui.tv(act, "🗣 AIテキスト読み上げ", 16f, Ui.FG, true))
        val te = Ui.edit(act, "放送する文章")
        te.setSingleLine(false)
        c3.addView(te, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        // 第2言語（任意）
        val langBtn = Ui.ghost(act, "🌐 第2言語: " + (if (secondLang.isEmpty()) "なし" else Lang.label(secondLang)), Ui.SUB) { }
        langBtn.setOnClickListener {
            val items = ArrayList<String>()
            items.add("なし")
            items.addAll(Lang.all.map { it.name })
            AlertDialog.Builder(act).setTitle("第2言語")
                .setItems(items.toTypedArray()) { _, i ->
                    secondLang = if (i == 0) "" else Lang.all[i - 1].code
                    render()
                }.show()
        }
        c3.addView(langBtn, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        val te2 = Ui.edit(act, "第2言語の文章（翻訳した文を入力）")
        te2.setSingleLine(false)
        te2.setText(secondText)
        if (secondLang.isEmpty()) te2.visibility = View.GONE
        c3.addView(te2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        if (secondLang.isNotEmpty()) {
            c3.addView(
                Ui.tv(act, "日本語→${Lang.label(secondLang)}の順に読み上げ、字幕も2段で表示します。読み上げには端末側に該当言語の音声データが必要です。", 10f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
            )
        }

        val rb = Ui.row(act)
        rb.addView(Ui.btn(act, "読み上げて放送") {
            secondText = te2.text.toString()
            val t = te.text.toString()
            if (t.isBlank()) act.toast("文章を入力してください")
            else sendTts(targetSpec, t, false, "", secondText.trim(), secondLang)
        }, cw())
        rb.addView(Ui.btn(act, "📣 予告つき", Ui.CARD2, Ui.FG) {
            secondText = te2.text.toString()
            val t = te.text.toString()
            if (t.isBlank()) act.toast("文章を入力してください")
            else noticeDialog(t, secondText.trim())
        }, cw())
        c3.addView(rb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        val tpl = store.templates()
        if (tpl.length() > 0) {
            c3.addView(Ui.tv(act, "定型文", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))
            var i = 0
            while (i < tpl.length() && i < 6) {
                val o = tpl.getJSONObject(i)
                c3.addView(Ui.ghost(act, o.optString("title"), Ui.FG) {
                    sendTts(targetSpec, o.optString("body"), false, o.optString("title"))
                }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                i++
            }
        }
        l.addView(c3, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            label.text = Targeting.label(targetSpec)
            val n = Targeting.resolve(targetSpec).size
            count.text = "オンライン ${n} 台へ送信されます（音質: ${if (store.quality == "low") "低帯域" else "高音質"}）"
            meter.text = if (Mixer.micActive) {
                "🔴 放送中　入力レベル " + "▮".repeat((Mixer.micLevel / 12).coerceIn(0, 8))
            } else if (Mixer.bgmActive || Mixer.fileActive) {
                "♪ " + (if (Mixer.fileActive) Mixer.fileTitle else Mixer.bgmTitle) + " を配信中"
            } else {
                "入力レベル —"
            }
        }
        return Ui.scroll(act, l)
    }

    private fun cw(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, Ui.WC, 1f)
        p.rightMargin = Ui.dp(act, 4)
        return p
    }

    private fun chip(text: String, f: () -> Unit): TextView =
        Ui.ghost(act, text, Ui.FG) { f() }

    private fun pickFloor() {
        val fl = Registry.floors()
        if (fl.isEmpty()) {
            act.toast("端末が検出されていません")
            return
        }
        val items = fl.map { "${it}階" }.toTypedArray()
        AlertDialog.Builder(act).setTitle("フロアを選択")
            .setItems(items) { _, i ->
                targetSpec = "floor:${fl[i]}"
                render()
            }.show()
    }

    /** 建物スコープの切り替え。以降の全館/フロア/グループがこの建物に限定される */
    private fun pickBuilding() {
        val bs = Registry.buildings()
        if (bs.isEmpty()) {
            act.toast("建物名が設定された端末がありません")
            return
        }
        val items = ArrayList<String>()
        items.add("すべての建物")
        items.addAll(bs)
        AlertDialog.Builder(act).setTitle("建物を選択")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) {
                    Targeting.scope = ""
                    targetSpec = "all"
                } else {
                    Targeting.scope = items[i]
                    targetSpec = "bldg:" + items[i]
                }
                render()
            }.show()
    }

    private fun pickGroup() {
        val gs = Registry.groups()
        if (gs.isEmpty()) {
            act.toast("端末が検出されていません")
            return
        }
        AlertDialog.Builder(act).setTitle("グループを選択")
            .setItems(gs.toTypedArray()) { _, i ->
                targetSpec = "group:${gs[i]}"
                render()
            }.show()
    }

    private fun pickDevice() {
        val ds = Registry.all()
        if (ds.isEmpty()) {
            act.toast("端末が検出されていません")
            return
        }
        AlertDialog.Builder(act).setTitle("端末を選択")
            .setItems(ds.map { it.label() }.toTypedArray()) { _, i ->
                targetSpec = "dev:${ds[i].id}"
                render()
            }.show()
    }

    private fun startPtt() {
        if (ConsoleService.calling) {
            act.toast("通話中はマイク放送を開始できません")
            return
        }
        val targets = Targeting.resolve(targetSpec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        val rate = store.rate
        bg {
            val ok = Mixer.startMic(targets.map { it.ip }, rate, store.autoGain)
            if (!ok) ui { act.toast("マイクを開始できません（権限を確認）") }
        }
        store.log(
            "broadcast", "放送開始 → ${Targeting.label(targetSpec)} (${targets.size}台)",
            targetSpec, "マイク放送"
        )
    }

    private fun stopPtt() {
        if (!Mixer.micActive) return
        bg { Mixer.stopMic() }
        store.log("broadcast", "放送終了 → ${Targeting.label(targetSpec)}", targetSpec)
    }

    private fun sendChime(spec: String, urgent: Boolean) {
        val targets = Targeting.resolve(spec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        bg {
            val req = Net.cmd("chime")
            req.put("urgent", urgent)
            for (d in targets) Net.ctrl(d.ip, req, 3000)
            ui { act.toast("チャイムを送信しました (${targets.size}台)") }
        }
        store.log("broadcast", "チャイム → ${Targeting.label(spec)}", spec, "チャイム")
    }

    /**
     * 予告つき放送。全端末にカウントダウン字幕を出し、
     * 満了後に本文の読み上げをコンソール側のタイマーで送る。
     */
    private fun noticeDialog(text: String, text2: String) {
        val secs = listOf(10, 20, 30, 60, 120)
        AlertDialog.Builder(act).setTitle("何秒前から予告しますか")
            .setItems(secs.map { "${it}秒前" }.toTypedArray()) { _, i ->
                startNoticed(secs[i], text, text2)
            }.show()
    }

    private fun startNoticed(sec: Int, text: String, text2: String) {
        val targets = Targeting.resolve(targetSpec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        noticeSec = sec
        bg {
            val req = Net.cmd("notice")
            req.put("sec", sec)
            req.put("text", "この後、放送があります")
            req.put("chime", true)
            for (d in targets) Net.ctrl(d.ip, req, 3000)
            ui { act.toast("${sec}秒前の予告を開始しました") }
            try { Thread.sleep(sec * 1000L) } catch (e: Exception) { }
            ui { sendTts(targetSpec, text, false, "", text2, secondLang) }
        }
        store.log("broadcast", "予告つき放送（${sec}秒前）→ ${Targeting.label(targetSpec)}", targetSpec)
    }

    /** 災害シナリオを選んで自動反復放送を開始する */
    private fun disasterDialog() {
        if (ConsoleService.disasterOn) {
            AlertDialog.Builder(act)
                .setTitle("災害放送を停止しますか")
                .setMessage("${ConsoleService.disasterName} を放送中です（${ConsoleService.disasterRound}/${ConsoleService.disasterTotal}回目）。")
                .setPositiveButton("停止する") { _, _ ->
                    ConsoleService.instance?.stopDisaster()
                    act.toast("停止しました")
                }
                .setNegativeButton("続行", null)
                .show()
            return
        }

        val box = Ui.col(act, 4)
        box.addView(
            Ui.tv(act, "対象: ${Targeting.label(targetSpec)}　選ぶとすぐに放送を開始します。", 12f, Ui.SUB)
        )
        box.addView(
            Ui.tv(act, "対象端末は自動で最大音量になり、画面が警報表示に切り替わります。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        var dlg: AlertDialog? = null
        for (sc in Disaster.all) {
            val row = Ui.col(act)
            row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8))
            row.addView(Ui.tv(act, "${sc.icon} ${sc.name}", 16f, Ui.FG, true))
            row.addView(Ui.tv(act, sc.text, 11f, Ui.SUB))
            row.addView(
                Ui.tv(act, "${sc.intervalSec}秒間隔で最大${sc.repeat}回くり返し", 11f, Ui.ACC),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
            )
            row.addView(
                Ui.btn(act, "この内容で開始", 0xFF8E1111.toInt(), Ui.FG) {
                    ConsoleService.instance?.startDisaster(sc.id, targetSpec)
                    act.toast("${sc.name} の災害放送を開始しました")
                    dlg?.dismiss()
                    render()
                },
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
            row.addView(Ui.sep(act))
            box.addView(row)
        }

        dlg = AlertDialog.Builder(act)
            .setTitle("🌏 災害放送")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .create()
        dlg.show()
    }

    private fun sendTts(spec: String, text: String, urgent: Boolean, tag: String = "", text2: String = "", lang2: String = "") {
        val targets = Targeting.resolve(spec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        Mixer.duckFor(ConsoleService.estimateSpeechMs(text))
        bg {
            val req = Net.cmd("tts")
            req.put("text", text)
            req.put("urgent", urgent)
            req.put("chime", true)
            if (text2.isNotEmpty() && lang2.isNotEmpty()) {
                req.put("text2", text2)
                req.put("lang2", lang2)
            }
            for (d in targets) Net.ctrl(d.ip, req, 3500)
            ui { act.toast("読み上げを送信しました (${targets.size}台)") }
        }
        store.log(
            if (urgent) "emergency" else "broadcast",
            "読み上げ「$text」→ ${Targeting.label(spec)}", spec, tag
        )
    }

    private fun emergencyDialog() {
        val box = Ui.col(act, 16)
        box.addView(Ui.tv(act, "対象: " + Targeting.label(targetSpec), 13f, Ui.SUB))
        val e = Ui.edit(act, "緊急放送の文言", "緊急放送です。落ち着いて避難してください。")
        e.setSingleLine(false)
        box.addView(e, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(
            Ui.tv(act, "※ 対象端末の音量を最大にし、警報チャイムの後に2回読み上げます。", 11f, Ui.RED),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
        )
        AlertDialog.Builder(act)
            .setTitle("🚨 緊急放送")
            .setView(box)
            .setNegativeButton("中止", null)
            .setPositiveButton("実行") { _, _ ->
                sendTts(targetSpec, e.text.toString(), true)
            }
            .show()
    }

    // ============================================================ 2 通話
    private fun tabCall(): View {
        val l = Ui.col(act, 14)

        val c0 = Ui.card(act)
        c0.addView(Ui.tv(act, "🎙 通話相手", 16f, Ui.FG, true))
        val who = Ui.tv(act, "", 19f, Ui.CYAN, true)
        c0.addView(who, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        c0.addView(Ui.ghost(act, "端末を選択", Ui.FG) {
            val ds = Registry.online()
            if (ds.isEmpty()) {
                act.toast("オンライン端末がありません")
            } else {
                AlertDialog.Builder(act).setTitle("通話する端末")
                    .setItems(ds.map { it.label() }.toTypedArray()) { _, i ->
                        callTarget = ds[i]
                        render()
                    }.show()
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(c0)

        val c1 = Ui.card(act, Ui.CARD2)
        val status = Ui.tv(act, "", 14f, Ui.SUB)
        status.gravity = Gravity.CENTER
        c1.addView(status)

        val toggle = Ui.btn(act, "通話を開始", Ui.GREEN, Ui.DARKTXT) {
            if (ConsoleService.calling) endCall() else beginCall()
        }
        c1.addView(toggle, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val ptt = Ui.tv(act, "押して話す", 18f, Ui.DARKTXT, true)
        ptt.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = Ui.dp(act, 60).toFloat()
        g.setColor(Ui.CYAN)
        ptt.background = g
        ptt.setPadding(0, Ui.dp(act, 36), 0, Ui.dp(act, 36))
        ptt.setOnTouchListener { v, ev ->
            if (!ConsoleService.calling || callHeld) return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { talkOn(); v.alpha = 0.75f; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!alwaysTalk) talkOff()
                    v.alpha = 1f; v.performClick(); true
                }
                else -> false
            }
        }
        c1.addView(ptt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))

        val sw = Ui.ghost(act, "", Ui.FG) {
            alwaysTalk = !alwaysTalk
            if (ConsoleService.calling) {
                if (alwaysTalk) talkOn() else talkOff()
            }
            render()
        }
        sw.text = if (alwaysTalk) "🔁 双方向通話: ON（常時送話）" else "🔁 双方向通話: OFF（Push-to-Talk）"
        c1.addView(sw, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val r2 = Ui.row(act)
        val holdBtn = Ui.ghost(act, if (callHeld) "▶ 保留解除" else "⏸ 保留", Ui.ACC) { toggleHold() }
        r2.addView(holdBtn, cw())
        r2.addView(Ui.ghost(act, "⇄ 転送", Ui.CYAN) { transferCall() }, cw())
        c1.addView(r2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        c1.addView(
            Ui.tv(act, "保留中は相手側に保留音が流れます。転送は通話相手を別の端末へ切り替えます。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val c2 = Ui.card(act)
        c2.addView(Ui.tv(act, "ℹ 通話について", 15f, Ui.FG, true))
        c2.addView(
            Ui.tv(
                act,
                "通話開始で相手端末のマイクがコンソールへ、コンソールのマイクが相手端末へ流れます。" +
                        "Push-to-Talkでは押している間だけこちらの声を送ります。", 12f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            who.text = callTarget?.label() ?: "未選択"
            status.text = if (ConsoleService.calling) "🟢 通話中" else "待機中"
            toggle.text = if (ConsoleService.calling) "通話を終了" else "通話を開始"
            val gg = GradientDrawable()
            gg.cornerRadius = Ui.dp(act, 12).toFloat()
            gg.setColor(if (ConsoleService.calling) Ui.RED else Ui.GREEN)
            toggle.background = gg
        }
        return Ui.scroll(act, l)
    }

    private fun beginCall() {
        val d = callTarget
        if (d == null) {
            act.toast("端末を選択してください")
            return
        }
        if (Mixer.micActive) {
            act.toast("マイク放送中は通話を開始できません")
            return
        }
        val rate = store.rate
        ConsoleService.calling = true
        ConsoleService.callTargetId = d.id
        bg {
            ConsoleService.startCallRx(rate)
            val req = Net.cmd("talk_start")
            req.put("rate", rate)
            req.put("console_ip", Net.localIp())
            Net.ctrl(d.ip, req, 3000)
            if (alwaysTalk) ConsoleService.startTx(listOf(d.ip), rate, store.autoGain)
        }
        store.log("call", "通話開始 → ${d.label()}")
    }

    private fun endCall() {
        val d = callTarget
        callHeld = false
        ConsoleService.calling = false
        bg {
            ConsoleService.stopTx()
            ConsoleService.stopCallRx()
            if (d != null) Net.ctrl(d.ip, Net.cmd("talk_stop"), 3000)
        }
        store.log("call", "通話終了")
    }

    /** 保留。相手の送話を止めて保留音を流す。こちらの送話も止める */
    private fun toggleHold() {
        val d = callTarget
        if (!ConsoleService.calling || d == null) {
            act.toast("通話中ではありません")
            return
        }
        callHeld = !callHeld
        val on = callHeld
        bg {
            ConsoleService.stopTx()
            val q = Net.cmd("hold")
            q.put("on", on)
            q.put("console_ip", Net.localIp())
            Net.ctrl(d.ip, q, 3000)
            if (!on && alwaysTalk) {
                ConsoleService.startTx(listOf(d.ip), store.rate, store.autoGain)
            }
            ui { render() }
        }
        store.log("call", if (on) "保留: ${d.label()}" else "保留解除: ${d.label()}")
    }

    /**
     * 転送。現在の相手との通話を終了し、選んだ端末と通話を張り直す。
     * 旧相手には talk_stop、新相手には talk_start。保留状態は引き継がない。
     */
    private fun transferCall() {
        val cur = callTarget
        if (!ConsoleService.calling || cur == null) {
            act.toast("通話中ではありません")
            return
        }
        val ds = Registry.online().filter { it.id != cur.id }
        if (ds.isEmpty()) {
            act.toast("転送先の端末がありません")
            return
        }
        AlertDialog.Builder(act).setTitle("転送先を選択")
            .setItems(ds.map { it.label() }.toTypedArray()) { _, i ->
                val next = ds[i]
                bg {
                    // 旧相手を切る
                    ConsoleService.stopTx()
                    Net.ctrl(cur.ip, Net.cmd("talk_stop"), 3000)
                    // 新相手へ張り直す
                    val req = Net.cmd("talk_start")
                    req.put("rate", store.rate)
                    req.put("console_ip", Net.localIp())
                    val res = Net.ctrl(next.ip, req, 3000)
                    ui {
                        if (res == null) {
                            act.toast("${next.label()} に接続できませんでした")
                            endCall()
                        } else {
                            callTarget = next
                            callHeld = false
                            ConsoleService.callTargetId = next.id
                            if (alwaysTalk) {
                                bg { ConsoleService.startTx(listOf(next.ip), store.rate, store.autoGain) }
                            }
                            act.toast("${next.label()} へ転送しました")
                            render()
                        }
                    }
                }
                store.log("call", "転送: ${cur.label()} → ${next.label()}")
            }.show()
    }

    private fun talkOn() {
        val d = callTarget ?: return
        bg { ConsoleService.startTx(listOf(d.ip), store.rate, store.autoGain) }
    }

    private fun talkOff() {
        bg { ConsoleService.stopTx() }
    }

    // ============================================================ 3 端末
    private fun tabDevices(): View {
        val l = Ui.col(act, 14)
        val head = Ui.row(act)
        head.addView(Ui.tv(act, "📱 端末一覧", 17f, Ui.FG, true), LinearLayout.LayoutParams(0, Ui.WC, 1f))
        head.addView(Ui.ghost(act, "一括", Ui.GREEN) { bulkDialog() })
        head.addView(Ui.ghost(act, "QRで追加", Ui.ACC) { qrAdd() })
        head.addView(Ui.ghost(act, "手動", Ui.SUB) { manualAdd() })
        l.addView(head)

        val list = Ui.col(act)
        l.addView(list, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            list.removeAllViews()
            val ds = Registry.all()
            if (ds.isEmpty()) {
                val c = Ui.card(act)
                c.addView(Ui.tv(act, "端末が見つかりません", 14f, Ui.FG, true))
                c.addView(
                    Ui.tv(
                        act,
                        "各フロアの端末で本アプリを起動し「フロア端末」を選ぶと、同一Wi-Fi上で自動検出されます。",
                        12f, Ui.SUB
                    ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
                )
                list.addView(c)
                return@add
            }
            for (d in ds) {
                list.addView(deviceCard(d), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
            }
        }
        return Ui.scroll(act, l)
    }

    private fun deviceCard(d: Dev): View {
        val c = Ui.card(act)
        val top = Ui.row(act)
        val name = Ui.tv(act, d.label(), 16f, Ui.FG, true)
        top.addView(name, LinearLayout.LayoutParams(0, Ui.WC, 1f))
        val sc = Diag.score(d)
        top.addView(
            Ui.pill(
                act,
                if (d.online) Diag.rank(sc) else "オフライン",
                if (d.online) Diag.color(sc) else Ui.LINE,
                if (d.online) Ui.DARKTXT else Ui.SUB
            )
        )
        c.addView(top)

        val info = StringBuilder()
        info.append(d.ip.ifEmpty { "IP不明" })
        info.append("　グループ ").append(d.group)
        if (d.battery >= 0) info.append("　🔋").append(d.battery).append("%")
        if (d.rssi != 0) info.append("　📶").append(d.rssi).append("dBm")
        if (d.remote) info.append("　🌐遠隔")
        if (d.route != Routing.AUTO) info.append("　🔈").append(Routing.label(d.route))
        info.append("　🔊").append(d.volume).append("%")
        info.append("　RTT ").append(if (d.rtt >= 0) "${d.rtt}ms" else "-")
        c.addView(Ui.tv(act, info.toString(), 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))

        if (d.playing || d.talking) {
            c.addView(
                Ui.tv(act, if (d.talking) "🎙 通話中" else "🔊 放送受信中", 12f, Ui.RED, true),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
        }

        c.addView(Ui.ghost(act, "詳細・制御", Ui.ACC) { deviceDialog(d) }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        return c
    }

    private fun deviceDialog(d: Dev) {
        val box = Ui.col(act, 16)
        val name = Ui.edit(act, "端末名", d.name)
        val floor = Ui.edit(act, "フロア", d.floor.toString(), true)
        val grp = Ui.edit(act, "グループ", d.group)
        box.addView(Ui.tv(act, "端末名", 11f, Ui.SUB))
        box.addView(name, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "フロア", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        box.addView(floor, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "グループ", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        box.addView(grp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val bldg = Ui.edit(act, "建物名", d.building)
        box.addView(Ui.tv(act, "建物", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        box.addView(bldg, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        // 平常値
        val base = Trend.baseline(store, d.id)
        box.addView(
            Ui.tv(act, "この端末の平常値", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
        )
        box.addView(Ui.tv(act, base.text(), 12f, if (base.ready()) Ui.CYAN else Ui.SUB))
        if (base.ready()) {
            box.addView(
                Ui.tv(act, "この幅から大きく外れたときに予兆として通知します。", 10f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
            )
        }

        // 状態の推移
        val hist = Trend.series(store, d.id, "s")
        if (hist.size >= 2) {
            box.addView(
                Ui.tv(act, "リスクスコアの推移（直近${hist.size}点 / 5分間隔）", 11f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
            )
            box.addView(Ui.spark(act, hist, Diag.color(hist.last().toInt())))
            val rs = Trend.series(store, d.id, "r")
            if (rs.size >= 2) {
                box.addView(
                    Ui.tv(act, "電波強度の推移", 11f, Ui.SUB),
                    Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
                )
                box.addView(Ui.spark(act, rs, Ui.CYAN))
            }
            val bsr = Trend.series(store, d.id, "b")
            if (bsr.size >= 2) {
                box.addView(
                    Ui.tv(act, "バッテリー残量の推移", 11f, Ui.SUB),
                    Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
                )
                box.addView(Ui.spark(act, bsr, Ui.GREEN))
            }
        }

        box.addView(Ui.tv(act, "音量  ${d.volume}%", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        val sb = SeekBar(act)
        sb.max = 100
        sb.progress = d.volume
        box.addView(sb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) {
                val v = s?.progress ?: 50
                bg {
                    val q = Net.cmd("volume")
                    q.put("value", v)
                    Net.ctrl(d.ip, q, 2500)
                }
                d.volume = v
            }
        })

        // 音声出力先
        box.addView(
            Ui.tv(act, "音声出力先", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12))
        )
        val rb = Ui.ghost(act, "", Ui.FG) { }
        rb.text = if (d.routeName.isNotEmpty()) d.routeName else Routing.label(d.route)
        rb.setOnClickListener {
            val ms = Routing.modes
            val items = ms.map { Routing.label(it) }.toTypedArray()
            AlertDialog.Builder(act).setTitle("出力先を選択")
                .setItems(items) { _, i ->
                    val m = ms[i]
                    bg {
                        val q = Net.cmd("route")
                        q.put("mode", m)
                        Net.ctrl(d.ip, q, 3000)
                    }
                    d.route = m
                    d.routeName = Routing.label(m)
                    rb.text = Routing.label(m)
                    act.toast("出力先を " + Routing.label(m) + " にしました")
                }.show()
        }
        box.addView(rb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(
            Ui.tv(act, "端末に接続されていない出力先を選ぶと自動に戻ります。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        val cap = Ui.ghost(act, "", Ui.FG) { }
        var capOn = d.caption
        cap.text = if (capOn) "💬 字幕表示: ON" else "💬 字幕表示: OFF"
        cap.setOnClickListener {
            capOn = !capOn
            val v = capOn
            bg {
                val q = Net.cmd("caption")
                q.put("on", v)
                Net.ctrl(d.ip, q, 3000)
            }
            d.caption = v
            cap.text = if (v) "💬 字幕表示: ON" else "💬 字幕表示: OFF"
        }
        box.addView(cap, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(
            Ui.tv(act, "読み上げ中に本文を端末画面へ大きく表示します。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        val kio = Ui.ghost(act, "", Ui.FG) { }
        var kioOn = d.kiosk
        kio.text = if (kioOn) "🔒 キオスクモード: ON" else "🔒 キオスクモード: OFF"
        kio.setOnClickListener {
            kioOn = !kioOn
            val v = kioOn
            bg {
                val q = Net.cmd("kiosk")
                q.put("on", v)
                Net.ctrl(d.ip, q, 3000)
            }
            d.kiosk = v
            kio.text = if (v) "🔒 キオスクモード: ON" else "🔒 キオスクモード: OFF"
            act.toast(if (v) "キオスクを有効化しました" else "キオスクを解除しました")
        }
        box.addView(kio, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(
            Ui.tv(act, "端末を待機画面に固定し、他のアプリへ移動できなくします。端末側での解除にはPINが必要です。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        box.addView(Ui.ghost(act, "🔔 テスト放送", Ui.CYAN) {
            sendTts("dev:${d.id}", "こちらは${d.floor}階、${d.name}です。テスト放送を行っています。", false)
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))

        box.addView(Ui.ghost(act, if (d.spkOn) "🔇 スピーカーを無効化" else "🔊 スピーカーを有効化", Ui.FG) {
            bg {
                val q = Net.cmd("spk")
                q.put("on", !d.spkOn)
                Net.ctrl(d.ip, q, 2500)
            }
            d.spkOn = !d.spkOn
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        box.addView(Ui.ghost(act, if (d.micOn) "🎙 マイクを無効化" else "🎙 マイクを有効化", Ui.FG) {
            bg {
                val q = Net.cmd("mic")
                q.put("on", !d.micOn)
                Net.ctrl(d.ip, q, 2500)
            }
            d.micOn = !d.micOn
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        box.addView(Ui.ghost(act, "🗑 台帳から削除", Ui.RED) {
            Registry.remove(d.id)
            Registry.save(store)
            store.log("system", "端末を削除: ${d.label()}")
            render()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        val diag = Ui.tv(
            act,
            "AI診断: ${Diag.rank(Diag.score(d))}（スコア ${Diag.score(d)}）\n" +
                    "ver ${d.ver.ifEmpty { "-" }} / 最終応答 " +
                    (if (d.lastSeen == 0L) "-" else Store.stamp(d.lastSeen)),
            11f, Ui.SUB
        )
        box.addView(diag, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))

        AlertDialog.Builder(act)
            .setTitle(d.label())
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setPositiveButton("保存") { _, _ ->
                val nm = name.text.toString().ifBlank { d.name }
                val fl = floor.text.toString().toIntOrNull() ?: d.floor
                val gp = grp.text.toString().ifBlank { d.group }
                val bd = bldg.text.toString().trim()
                bg {
                    val q = Net.cmd("set_info")
                    q.put("name", nm)
                    q.put("floor", fl)
                    q.put("group", gp)
                    q.put("bldg", bd)
                    Net.ctrl(d.ip, q, 3000)
                }
                d.name = nm; d.floor = fl; d.group = gp; d.building = bd
                Registry.save(store)
                render()
            }
            .show()
    }

    /** 対象をまとめて設定変更する */
    private fun bulkDialog() {
        val targets = Targeting.resolve(targetSpec)
        val box = Ui.col(act, 8)
        box.addView(Ui.tv(act, "対象: ${Targeting.label(targetSpec)}（${targets.size}台）", 14f, Ui.ACC, true))
        box.addView(
            Ui.tv(act, "対象は放送タブの選択と連動します。オンラインの端末だけに届きます。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        if (targets.isEmpty()) {
            box.addView(
                Ui.tv(act, "対象端末がオンラインではありません。", 12f, Ui.RED),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
            )
        }

        // 音量
        box.addView(Ui.tv(act, "音量をまとめて変更", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))
        val vl = Ui.tv(act, "70%", 11f, Ui.FG)
        box.addView(vl, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val sb = SeekBar(act)
        sb.max = 100
        sb.progress = 70
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v: SeekBar?, p: Int, u: Boolean) { vl.text = "${p}%" }
            override fun onStartTrackingTouch(v: SeekBar?) { }
            override fun onStopTrackingTouch(v: SeekBar?) { }
        })
        box.addView(sb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.btn(act, "この音量を適用") {
            bulkSend(targets, "volume") { it.put("value", sb.progress) }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        // 出力先
        box.addView(Ui.tv(act, "音声出力先をまとめて変更", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))
        for (m in Routing.modes) {
            box.addView(Ui.ghost(act, Routing.label(m), Ui.FG) {
                bulkSend(targets, "route") { it.put("mode", m) }
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        }

        // 機能
        box.addView(Ui.tv(act, "機能", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))
        val r1 = Ui.row(act)
        r1.addView(Ui.ghost(act, "スピーカー有効", Ui.GREEN) {
            bulkSend(targets, "spk") { it.put("on", true) }
        }, cw())
        r1.addView(Ui.ghost(act, "スピーカー無効", Ui.RED) {
            bulkSend(targets, "spk") { it.put("on", false) }
        }, cw())
        box.addView(r1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val r2 = Ui.row(act)
        r2.addView(Ui.ghost(act, "マイク有効", Ui.GREEN) {
            bulkSend(targets, "mic") { it.put("on", true) }
        }, cw())
        r2.addView(Ui.ghost(act, "マイク無効", Ui.SUB) {
            bulkSend(targets, "mic") { it.put("on", false) }
        }, cw())
        box.addView(r2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val r4 = Ui.row(act)
        r4.addView(Ui.ghost(act, "キオスク ON", Ui.GREEN) {
            bulkSend(targets, "kiosk") { it.put("on", true) }
        }, cw())
        r4.addView(Ui.ghost(act, "キオスク OFF", Ui.SUB) {
            bulkSend(targets, "kiosk") { it.put("on", false) }
        }, cw())
        box.addView(r4, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        val r3 = Ui.row(act)
        r3.addView(Ui.ghost(act, "字幕表示 ON", Ui.GREEN) {
            bulkSend(targets, "caption") { it.put("on", true) }
        }, cw())
        r3.addView(Ui.ghost(act, "字幕表示 OFF", Ui.SUB) {
            bulkSend(targets, "caption") { it.put("on", false) }
        }, cw())
        box.addView(r3, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        box.addView(Ui.ghost(act, "🔔 一斉テストチャイム", Ui.CYAN) {
            bulkSend(targets, "chime") { it.put("urgent", false) }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        box.addView(Ui.ghost(act, "🔄 音声系を再起動", Ui.ACC) {
            bulkSend(targets, "reboot_audio") { }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        AlertDialog.Builder(act)
            .setTitle("⚙ 一括操作")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun bulkSend(targets: List<Dev>, cmd: String, fill: (org.json.JSONObject) -> Unit) {
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        bg {
            var ok = 0
            for (d in targets) {
                val q = Net.cmd(cmd)
                fill(q)
                if (Net.ctrl(d.ip, q, 3000) != null) ok++
            }
            ui {
                act.toast("$ok/${targets.size} 台に適用しました")
                render()
            }
        }
        store.log("system", "一括操作 $cmd → ${Targeting.label(targetSpec)} (${targets.size}台)", targetSpec)
    }

    /** 端末画面のQRを読み取って台帳へ登録 */
    private fun qrAdd() {
        act.scanQr { text ->
            val o = Qr.parse(text)
            if (o == null) {
                act.toast("放送室アプリのQRではありません")
                return@scanQr
            }
            val ip = o.optString("ip")
            if (ip.isEmpty()) {
                act.toast("IPを取得できませんでした")
                return@scanQr
            }
            bg {
                val res = Net.ctrl(ip, Net.cmd("status"), 3000)
                ui {
                    val d = if (res != null) Registry.upsert(res, ip) else Registry.upsert(o, ip)
                    Registry.save(store)
                    if (res == null) {
                        act.toast("登録しましたが応答がありません: ${d.label()}")
                    } else {
                        act.toast("登録しました: ${d.label()}")
                    }
                    store.log("system", "QRから端末を登録: ${d.label()} ($ip)")
                    render()
                }
            }
        }
    }

    private fun manualAdd() {
        val box = Ui.col(act, 16)
        val ip = Ui.edit(act, "端末のIPアドレス（例 192.168.1.42）")
        box.addView(Ui.tv(act, "自動検出できない場合に直接指定します。", 12f, Ui.SUB))
        box.addView(ip, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        AlertDialog.Builder(act)
            .setTitle("端末を手動追加")
            .setView(box)
            .setNegativeButton("閉じる", null)
            .setPositiveButton("接続") { _, _ ->
                val addr = ip.text.toString().trim()
                if (addr.isEmpty()) return@setPositiveButton
                bg {
                    val res = Net.ctrl(addr, Net.cmd("status"), 3000)
                    ui {
                        if (res == null) {
                            act.toast("応答がありません")
                        } else {
                            val d = Registry.upsert(res, addr)
                            Registry.save(store)
                            act.toast("追加しました: ${d.label()}")
                            render()
                        }
                    }
                }
            }
            .show()
    }


    // ============================================================ 4 音源ライブラリ / BGM
    private fun tabLibrary(): View {
        val l = Ui.col(act, 14)

        // 再生状況
        val c0 = Ui.card(act, Ui.CARD2)
        c0.addView(Ui.tv(act, "🎵 配信中", 16f, Ui.ACC, true))
        val now = Ui.tv(act, "", 14f, Ui.FG, true)
        c0.addView(now, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        val prog = Ui.tv(act, "", 11f, Ui.SUB)
        c0.addView(prog, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val stopRow = Ui.row(act)
        stopRow.addView(Ui.ghost(act, "⏹ 音声ファイル停止", Ui.RED) {
            bg { Mixer.stopFile() }
        }, cw())
        stopRow.addView(Ui.ghost(act, "⏹ BGM停止", Ui.RED) {
            bg { Mixer.stopBgm() }
            store.log("broadcast", "BGMを停止")
        }, cw())
        c0.addView(stopRow, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(c0)

        // BGM設定
        val c1 = Ui.card(act)
        c1.addView(Ui.tv(act, "🔁 BGM設定", 16f, Ui.FG, true))
        val vlabel = Ui.tv(act, "BGM音量 ${store.bgmVolume}%", 12f, Ui.SUB)
        c1.addView(vlabel, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val sb = SeekBar(act)
        sb.max = 100
        sb.progress = store.bgmVolume
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v: SeekBar?, p: Int, u: Boolean) {
                vlabel.text = "BGM音量 ${p}%"
                Mixer.bgmGain = p / 100f
            }
            override fun onStartTrackingTouch(v: SeekBar?) { }
            override fun onStopTrackingTouch(v: SeekBar?) {
                store.bgmVolume = v?.progress ?: 35
            }
        })
        c1.addView(sb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val loop = Ui.ghost(act, "", Ui.FG) { }
        loop.text = if (store.bgmLoop) "繰り返し: ON" else "繰り返し: OFF"
        loop.setOnClickListener {
            store.bgmLoop = !store.bgmLoop
            Mixer.bgmLoop = store.bgmLoop
            loop.text = if (store.bgmLoop) "繰り返し: ON" else "繰り返し: OFF"
        }
        c1.addView(loop, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        c1.addView(
            Ui.tv(act, "マイク放送・音声ファイル放送・読み上げの間は、BGMが自動で絞られます。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // ライブラリ
        val c2 = Ui.card(act)
        val head = Ui.row(act)
        head.addView(Ui.tv(act, "📚 音源ライブラリ", 16f, Ui.FG, true), LinearLayout.LayoutParams(0, Ui.WC, 1f))
        head.addView(Ui.ghost(act, "＋ 取り込み", Ui.CYAN) { importAudio() })
        c2.addView(head)
        c2.addView(
            Ui.tv(act, "取り込み時に16kHzモノラルへ変換して保存します。mp3 / m4a / wav / ogg に対応。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        val list = Ui.col(act)
        c2.addView(list, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            now.text = when {
                Mixer.fileActive -> "▶ " + Mixer.fileTitle
                Mixer.bgmActive -> "🔁 " + Mixer.bgmTitle
                else -> "停止中"
            }
            prog.text = when {
                Mixer.fileActive -> "進捗 ${Mixer.fileProgress()}%　対象: ${Targeting.label(targetSpec)}"
                Mixer.bgmActive -> "BGM再生中"
                else -> "音源を選んで放送またはBGMに設定してください。"
            }

            list.removeAllViews()
            val a = store.audioItems()
            if (a.length() == 0) {
                list.addView(Ui.tv(act, "音源がありません。「取り込み」から追加してください。", 12f, Ui.SUB))
            }
            var i = 0
            while (i < a.length()) {
                val o = a.getJSONObject(i)
                val id = o.optString("id")
                val row = Ui.col(act)
                row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8))
                val isBgm = store.bgmId == id
                row.addView(
                    Ui.tv(act, o.optString("title"), 14f, if (isBgm) Ui.ACC else Ui.FG, true)
                )
                row.addView(
                    Ui.tv(
                        act,
                        Library.duration(o) + (if (isBgm) "　🔁 BGMに設定中" else ""),
                        11f, Ui.SUB
                    )
                )
                val ops = Ui.row(act)
                ops.addView(Ui.ghost(act, "放送", Ui.ACC) { playFile(id, o.optString("title")) }, cw())
                ops.addView(Ui.ghost(act, "BGM", Ui.CYAN) { playBgm(id, o.optString("title")) }, cw())
                ops.addView(Ui.ghost(act, "削除", Ui.RED) {
                    Library.remove(act, store, id)
                    render()
                }, cw())
                row.addView(ops, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                row.addView(Ui.sep(act))
                list.addView(row)
                i++
            }
        }
        return Ui.scroll(act, l)
    }

    private fun importAudio() {
        act.pickAudio { uri ->
            act.toast("取り込み中…")
            bg {
                val o = Library.add(act, store, uri)
                ui {
                    if (o == null) {
                        act.toast("この形式は読み込めませんでした")
                    } else {
                        act.toast("追加しました: ${o.optString("title")}")
                        store.log("system", "音源を取り込み: ${o.optString("title")}")
                        render()
                    }
                }
            }
        }
    }

    private fun playFile(id: String, title: String) {
        val targets = Targeting.resolve(targetSpec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        bg {
            val pcm = Library.pcm(act, id)
            if (pcm == null) {
                ui { act.toast("音源を読み込めません") }
                return@bg
            }
            val ok = Mixer.startFile(pcm, title, targets.map { it.ip }, store.rate)
            ui { if (!ok) act.toast("再生を開始できません") }
        }
        store.log(
            "broadcast", "音声ファイル放送「$title」→ ${Targeting.label(targetSpec)}",
            targetSpec, title
        )
    }

    private fun playBgm(id: String, title: String) {
        val targets = Targeting.resolve(targetSpec)
        if (targets.isEmpty()) {
            act.toast("対象端末がオンラインではありません")
            return
        }
        store.bgmId = id
        Mixer.bgmGain = store.bgmVolume / 100f
        bg {
            val pcm = Library.pcm(act, id)
            if (pcm == null) {
                ui { act.toast("音源を読み込めません") }
                return@bg
            }
            val ok = Mixer.startBgm(pcm, title, targets.map { it.ip }, store.rate, store.bgmLoop)
            ui {
                if (ok) render() else act.toast("BGMを開始できません")
            }
        }
        store.log("broadcast", "BGM開始「$title」→ ${Targeting.label(targetSpec)}", targetSpec, title)
    }

    // ============================================================ 4 予約 / 定型文
    private fun tabSchedule(): View {
        val l = Ui.col(act, 14)

        l.addView(timeSignalCard(), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 0)))
        l.addView(Ui.space(act, 12))

        val c0 = Ui.card(act)
        c0.addView(Ui.tv(act, "⏰ スケジュール放送", 16f, Ui.FG, true))
        c0.addView(
            Ui.tv(act, "指定時刻に、選択した対象へAI読み上げを自動送信します（コンソール常駐中に有効）。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        c0.addView(Ui.btn(act, "＋ 予約を追加") { scheduleDialog(null) }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val slist = Ui.col(act)
        c0.addView(slist, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c0)

        val c1 = Ui.card(act)
        c1.addView(Ui.tv(act, "📚 定型文ライブラリ", 16f, Ui.FG, true))
        c1.addView(Ui.btn(act, "＋ 定型文を追加", Ui.CYAN) { templateDialog() }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val tlist = Ui.col(act)
        c1.addView(tlist, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            slist.removeAllViews()
            val a = store.schedules()
            if (a.length() == 0) {
                slist.addView(Ui.tv(act, "予約はありません。", 12f, Ui.SUB))
            }
            var i = 0
            while (i < a.length()) {
                val o = a.getJSONObject(i)
                val idx = i
                val row = Ui.col(act, 0)
                row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8))
                val head = Ui.row(act)
                val en = o.optBoolean("enabled", true)
                head.addView(
                    Ui.tv(
                        act,
                        String.format("%02d:%02d  %s", o.optInt("hour"), o.optInt("min"), o.optString("title")),
                        15f, if (en) Ui.ACC else Ui.SUB, true
                    ), LinearLayout.LayoutParams(0, Ui.WC, 1f)
                )
                head.addView(Ui.ghost(act, if (en) "有効" else "無効", if (en) Ui.GREEN else Ui.SUB) {
                    o.put("enabled", !en)
                    store.saveSchedules(a)
                    refresh()
                })
                row.addView(head)
                val mode = when (o.optString("mode")) {
                    "weekday" -> "平日"
                    else -> "毎日"
                }
                row.addView(
                    Ui.tv(
                        act,
                        "$mode ・ ${Targeting.label(o.optString("target"))} ・ ${o.optString("text")}",
                        11f, Ui.SUB
                    ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
                )
                val ops = Ui.row(act)
                ops.addView(Ui.ghost(act, "今すぐ実行", Ui.CYAN) {
                    sendTts(o.optString("target"), o.optString("text"), o.optBoolean("urgent", false))
                }, cw())
                ops.addView(Ui.ghost(act, "編集", Ui.FG) { scheduleDialog(idx) }, cw())
                ops.addView(Ui.ghost(act, "削除", Ui.RED) {
                    val out = JSONArray()
                    var k = 0
                    while (k < a.length()) {
                        if (k != idx) out.put(a.getJSONObject(k))
                        k++
                    }
                    store.saveSchedules(out)
                    refresh()
                }, cw())
                row.addView(ops, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                row.addView(Ui.sep(act))
                slist.addView(row)
                i++
            }

            tlist.removeAllViews()
            val t = store.templates()
            var j = 0
            while (j < t.length()) {
                val o = t.getJSONObject(j)
                val idx = j
                val row = Ui.col(act)
                row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
                row.addView(Ui.tv(act, o.optString("title"), 14f, Ui.FG, true))
                row.addView(Ui.tv(act, o.optString("body"), 11f, Ui.SUB))
                val ops = Ui.row(act)
                ops.addView(Ui.ghost(act, "送信", Ui.CYAN) {
                    sendTts(targetSpec, o.optString("body"), false, o.optString("title"))
                }, cw())
                ops.addView(Ui.ghost(act, "削除", Ui.RED) {
                    val out = JSONArray()
                    var k = 0
                    while (k < t.length()) {
                        if (k != idx) out.put(t.getJSONObject(k))
                        k++
                    }
                    store.saveTemplates(out)
                    refresh()
                }, cw())
                row.addView(ops, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                row.addView(Ui.sep(act))
                tlist.addView(row)
                j++
            }
        }
        return Ui.scroll(act, l)
    }

    private fun scheduleDialog(editIdx: Int?) {
        val arr = store.schedules()
        val cur = if (editIdx != null && editIdx < arr.length()) arr.getJSONObject(editIdx) else null

        val box = Ui.col(act, 16)
        val title = Ui.edit(act, "名称（例: 朝礼）", cur?.optString("title") ?: "")
        val hour = Ui.edit(act, "時", (cur?.optInt("hour") ?: 8).toString(), true)
        val min = Ui.edit(act, "分", (cur?.optInt("min") ?: 30).toString(), true)
        val body = Ui.edit(act, "読み上げ文", cur?.optString("text") ?: "")
        body.setSingleLine(false)

        box.addView(Ui.tv(act, "名称", 11f, Ui.SUB))
        box.addView(title, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        val hm = Ui.row(act)
        hm.addView(hour, cw())
        hm.addView(min, cw())
        box.addView(Ui.tv(act, "時刻（24時間制）", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(hm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "読み上げ文", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(body, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        var mode = cur?.optString("mode") ?: "daily"
        val modeBtn = Ui.ghost(act, "", Ui.FG) { }
        modeBtn.text = if (mode == "weekday") "繰り返し: 平日のみ" else "繰り返し: 毎日"
        modeBtn.setOnClickListener {
            mode = if (mode == "weekday") "daily" else "weekday"
            modeBtn.text = if (mode == "weekday") "繰り返し: 平日のみ" else "繰り返し: 毎日"
        }
        box.addView(modeBtn, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        var spec = cur?.optString("target") ?: "all"
        val tgtBtn = Ui.ghost(act, "", Ui.FG) { }
        tgtBtn.text = "対象: " + Targeting.label(spec)
        tgtBtn.setOnClickListener {
            val opts = ArrayList<String>()
            val specs = ArrayList<String>()
            opts.add("全館"); specs.add("all")
            for (f in Registry.floors()) { opts.add("${f}階"); specs.add("floor:$f") }
            for (g in Registry.groups()) { opts.add("グループ $g"); specs.add("group:$g") }
            AlertDialog.Builder(act).setTitle("対象")
                .setItems(opts.toTypedArray()) { _, i ->
                    spec = specs[i]
                    tgtBtn.text = "対象: " + Targeting.label(spec)
                }.show()
        }
        box.addView(tgtBtn, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        AlertDialog.Builder(act)
            .setTitle(if (cur == null) "予約を追加" else "予約を編集")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setPositiveButton("保存") { _, _ ->
                val o = cur ?: JSONObject()
                o.put("title", title.text.toString().ifBlank { "定時放送" })
                o.put("hour", (hour.text.toString().toIntOrNull() ?: 8).coerceIn(0, 23))
                o.put("min", (min.text.toString().toIntOrNull() ?: 0).coerceIn(0, 59))
                o.put("text", body.text.toString())
                o.put("mode", mode)
                o.put("target", spec)
                o.put("enabled", true)
                o.put("chime", true)
                if (cur == null) arr.put(o)
                store.saveSchedules(arr)
                store.log("schedule", "予約を保存: ${o.optString("title")}")
                render()
            }
            .show()
    }

    private fun addSchedule(
        hour: Int, minute: Int, mode: String, target: String, title: String, text: String
    ) {
        if (hour < 0) {
            act.toast("時刻を解釈できませんでした")
            return
        }
        val arr = store.schedules()
        val o = JSONObject()
        o.put("title", title.ifBlank { "定時放送" })
        o.put("hour", hour.coerceIn(0, 23))
        o.put("min", minute.coerceIn(0, 59))
        o.put("text", text.ifBlank { title })
        o.put("mode", mode)
        o.put("target", target)
        o.put("enabled", true)
        o.put("chime", true)
        arr.put(o)
        store.saveSchedules(arr)
        store.log("schedule", "AIアシスタントが予約を登録: $title")
    }

    private fun templateDialog() {
        val box = Ui.col(act, 16)
        val t = Ui.edit(act, "タイトル")
        val b = Ui.edit(act, "本文")
        b.setSingleLine(false)
        box.addView(t)
        box.addView(b, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        AlertDialog.Builder(act)
            .setTitle("定型文を追加")
            .setView(box)
            .setNegativeButton("閉じる", null)
            .setPositiveButton("保存") { _, _ ->
                val arr = store.templates()
                val o = JSONObject()
                o.put("title", t.text.toString().ifBlank { "定型文" })
                o.put("body", b.text.toString())
                arr.put(o)
                store.saveTemplates(arr)
                render()
            }
            .show()
    }

    // ============================================================ 5 ログ
    private var logFilterToday = false


    // ============================================================ 6 手首端末
    private var wristTarget = "all"

    private fun tabWrist(): View {
        val l = Ui.col(act, 14)

        // 送信先
        val c0 = Ui.card(act, Ui.CARD2)
        c0.addView(Ui.tv(act, "⌚ 送信先", 16f, Ui.ACC, true))
        val tgt = Ui.tv(act, "", 14f, Ui.FG, true)
        c0.addView(tgt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        val tr = Ui.row(act)
        tr.addView(chip("全員") { wristTarget = "all"; render() }, cw())
        tr.addView(chip("グループ") { pickWristGroup() }, cw())
        tr.addView(chip("個別") { pickWristDevice() }, cw())
        c0.addView(tr, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c0)

        // メッセージ
        val c1 = Ui.card(act)
        c1.addView(Ui.tv(act, "💬 短文メッセージ", 16f, Ui.FG, true))
        c1.addView(
            Ui.tv(act, "手首が振動し、本文が表示されます。既読が戻ります。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        for (m in Wrist.messages) {
            c1.addView(Ui.ghost(act, m.ja, Ui.FG) {
                sendWristMessage(m.ja, m.ascii, false)
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        }
        c1.addView(Ui.ghost(act, "✏ 自由入力で送る", Ui.CYAN) { freeMessageDialog() },
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // アンケート
        val c2 = Ui.card(act)
        c2.addView(Ui.tv(act, "❓ 選択式アンケート", 16f, Ui.FG, true))
        c2.addView(
            Ui.tv(act, "最大3択。相手はボタンで選ぶだけで回答できます。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        for (t in Wrist.templates) {
            val name = if (t.ja.isEmpty()) "${t.key}（質問を入力）" else t.ja
            c2.addView(Ui.ghost(act, name, Ui.FG) { templateDialog(t) },
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        }
        c2.addView(Ui.ghost(act, "✏ 一から作る", Ui.CYAN) { questionDialog(null) },
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        // 端末一覧
        val c3 = Ui.card(act)
        val head = Ui.row(act)
        head.addView(Ui.tv(act, "⌚ 手首端末", 16f, Ui.FG, true), LinearLayout.LayoutParams(0, Ui.WC, 1f))
        head.addView(Ui.ghost(act, "＋ 登録", Ui.ACC) { addWristDevice() })
        c3.addView(head)
        c3.addView(
            Ui.tv(
                act,
                "他の人の時計は「＋ 登録」でQRを配ります。自分の時計を使う場合は下の「この端末で使う」を押してください。",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        val selfRow = Ui.col(act)
        c3.addView(selfRow, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        val list = Ui.col(act)
        c3.addView(list, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c3, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            tgt.text = wristTargetLabel()

            selfRow.removeAllViews()
            val me = Wrist.selfDevice(store)
            if (me == null) {
                selfRow.addView(Ui.btn(act, "⌚ この端末で使う（自分のPebble）") { setupSelf() })
            } else {
                val ok = me.lastSeen > 0
                selfRow.addView(
                    Ui.tv(act, "⌚ この端末: ${me.name}", 14f, if (ok) Ui.GREEN else Ui.ACC, true)
                )
                selfRow.addView(
                    Ui.tv(
                        act,
                        if (ok) "時計アプリと接続済み（最終通信 ${ago(me.lastSeen)}）"
                        else "時計アプリの設定がまだです。「接続情報」から設定してください。",
                        11f, Ui.SUB
                    ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
                )
                val sops = Ui.row(act)
                sops.addView(Ui.ghost(act, "接続情報", Ui.CYAN) { showSelfInfo(me) }, cw())
                sops.addView(Ui.ghost(act, "自分に送信", Ui.FG) {
                    wristTarget = me.id
                    render()
                }, cw())
                selfRow.addView(sops, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
            }

            list.removeAllViews()
            val ds = Wrist.devices(store).filter { !it.self }
            if (ds.isEmpty()) {
                list.addView(
                    Ui.tv(act, "他の人の手首端末はまだありません。", 12f, Ui.SUB)
                )
            }
            for (d in ds) {
                val row = Ui.col(act)
                row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8))
                val seen = if (d.lastSeen == 0L) "未接続" else "最終通信 " + ago(d.lastSeen)
                row.addView(Ui.tv(act, d.name, 14f, Ui.FG, true))
                row.addView(Ui.tv(act, "${d.group} / ${d.id} / $seen", 11f, Ui.SUB))

                // 直近のやりとり
                val its = Wrist.items(store, d.id)
                if (its.isNotEmpty()) {
                    val o = its[0]
                    val st = when {
                        o.has("answer") -> "回答: " + Wrist.choiceLabel(o, o.optInt("answer"))
                        o.has("acked_at") -> "既読"
                        else -> "未読"
                    }
                    row.addView(
                        Ui.tv(act, "最新「${o.optString("label_ja")}」— $st", 11f,
                            if (o.has("answer") || o.has("acked_at")) Ui.GREEN else Ui.SUB),
                        Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
                    )
                }

                val ops = Ui.row(act)
                ops.addView(Ui.ghost(act, "履歴", Ui.FG) { wristHistory(d) }, cw())
                ops.addView(Ui.ghost(act, "QR", Ui.ACC) { showWristQr(d) }, cw())
                ops.addView(Ui.ghost(act, "設定", Ui.SUB) { wristDeviceDialog(d) }, cw())
                row.addView(ops, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
                row.addView(Ui.sep(act))
                list.addView(row)
            }
        }
        return Ui.scroll(act, l)
    }

    private fun ago(t: Long): String {
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> "${d}秒前"
            d < 3600 -> "${d / 60}分前"
            d < 86400 -> "${d / 3600}時間前"
            else -> "${d / 86400}日前"
        }
    }

    private fun wristTargetLabel(): String {
        val ds = Wrist.devices(store)
        return when {
            wristTarget == "all" -> "全員（${ds.size}台）"
            wristTarget.startsWith("g:") -> {
                val g = wristTarget.substring(2)
                "$g（${ds.count { it.group == g }}台）"
            }
            else -> Wrist.find(store, wristTarget)?.name ?: "未選択"
        }
    }

    private fun wristTargets(): List<String> {
        val ds = Wrist.devices(store)
        return when {
            wristTarget == "all" -> ds.map { it.id }
            wristTarget.startsWith("g:") -> {
                val g = wristTarget.substring(2)
                ds.filter { it.group == g }.map { it.id }
            }
            else -> ds.filter { it.id == wristTarget }.map { it.id }
        }
    }

    private fun pickWristGroup() {
        val gs = Wrist.groups(store)
        if (gs.isEmpty()) {
            act.toast("端末が登録されていません")
            return
        }
        AlertDialog.Builder(act).setTitle("グループを選択")
            .setItems(gs.toTypedArray()) { _, i ->
                wristTarget = "g:" + gs[i]
                render()
            }.show()
    }

    private fun pickWristDevice() {
        val ds = Wrist.devices(store)
        if (ds.isEmpty()) {
            act.toast("端末が登録されていません")
            return
        }
        AlertDialog.Builder(act).setTitle("端末を選択")
            .setItems(ds.map { it.name }.toTypedArray()) { _, i ->
                wristTarget = ds[i].id
                render()
            }.show()
    }

    // ---------------------------------------------------------- 送信
    private fun sendWristMessage(ja: String, ascii: String, urgent: Boolean) {
        val ids = wristTargets()
        if (ids.isEmpty()) {
            act.toast("送信先の端末がありません")
            return
        }
        val item = Wrist.buildMessage(ja, ascii, urgent, store.wristTtlMin)
        val n = Wrist.enqueue(store, ids, item)
        if (n == 0) {
            act.toast("ラベルが不足しているため送信できません")
            return
        }
        act.toast("$n 台へ送信しました")
        store.log("wrist", "メッセージ送信「$ja」→ ${wristTargetLabel()}")
        render()
    }

    private fun freeMessageDialog() {
        val box = Ui.col(act, 8)
        val ja = Ui.edit(act, "日本語（最大${Wrist.LIMIT_JA_BODY}文字）")
        val cnt = Ui.tv(act, "残り ${Wrist.LIMIT_JA_BODY} 文字", 11f, Ui.SUB)
        val asc = Ui.edit(act, "ASCII（最大${Wrist.LIMIT_ASCII_BODY}文字）")
        ja.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val n = Wrist.LIMIT_JA_BODY - (s?.length ?: 0)
                cnt.text = if (n >= 0) "残り $n 文字" else "${-n} 文字超過（切り詰められます）"
                cnt.setTextColor(if (n >= 0) Ui.SUB else Ui.RED)
                if (asc.text.isEmpty()) {
                    val sg = Wrist.suggest(s?.toString() ?: "")
                    if (sg.isNotEmpty()) asc.setHint(sg)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
        })
        box.addView(Ui.tv(act, "本文", 12f, Ui.SUB))
        box.addView(ja, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(cnt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(
            Ui.tv(act, "ASCII表記（言語パック未導入の時計はこちらが表示されます。必須）", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12))
        )
        box.addView(asc, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        AlertDialog.Builder(act).setTitle("メッセージを作る")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("やめる", null)
            .setPositiveButton("送信") { _, _ ->
                val j = ja.text.toString().trim()
                var a = asc.text.toString().trim()
                if (a.isEmpty()) a = Wrist.suggest(j)
                if (j.isEmpty() || a.isEmpty()) {
                    act.toast("日本語とASCIIの両方が必要です")
                } else {
                    sendWristMessage(j, a, false)
                }
            }.show()
    }

    private fun templateDialog(t: Wrist.Template) {
        if (t.ja.isEmpty() || t.choices.any { it.first.isEmpty() }) {
            questionDialog(t)
        } else {
            sendWristQuestion(t.ja, t.ascii, t.choices)
        }
    }

    /** 質問と3択を組み立てる。テンプレを渡すと初期値に入る */
    private fun questionDialog(t: Wrist.Template?) {
        val box = Ui.col(act, 8)
        val qja = Ui.edit(act, "質問（最大${Wrist.LIMIT_JA_BODY}文字）", t?.ja ?: "")
        val qas = Ui.edit(act, "質問のASCII", t?.ascii ?: "")
        val qcnt = Ui.tv(act, "", 11f, Ui.SUB)
        watch(qja, qcnt, Wrist.LIMIT_JA_BODY, qas)

        box.addView(Ui.tv(act, "質問", 12f, Ui.SUB))
        box.addView(qja, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(qcnt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(qas, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))

        box.addView(
            Ui.tv(act, "選択肢（最大3つ・日本語${Wrist.LIMIT_JA_CHOICE}文字まで）", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
        )
        val cja = ArrayList<android.widget.EditText>()
        val cas = ArrayList<android.widget.EditText>()
        var i = 0
        while (i < 3) {
            val pre = t?.choices?.getOrNull(i)
            val e1 = Ui.edit(act, "選択肢${i + 1}", pre?.first ?: "")
            val e2 = Ui.edit(act, "ASCII", pre?.second ?: "")
            val cc = Ui.tv(act, "", 11f, Ui.SUB)
            watch(e1, cc, Wrist.LIMIT_JA_CHOICE, e2)
            box.addView(e1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
            box.addView(cc, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 2)))
            box.addView(e2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
            cja.add(e1); cas.add(e2)
            i++
        }
        box.addView(
            Ui.tv(act, "空欄の選択肢は送られません。ASCIIは未入力なら候補が自動で入ります。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
        )

        AlertDialog.Builder(act).setTitle("アンケートを作る")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("やめる", null)
            .setPositiveButton("送信") { _, _ ->
                val j = qja.text.toString().trim()
                var a = qas.text.toString().trim()
                if (a.isEmpty()) a = Wrist.suggest(j)
                val cs = ArrayList<Pair<String, String>>()
                var k = 0
                while (k < 3) {
                    val x = cja[k].text.toString().trim()
                    var y = cas[k].text.toString().trim()
                    if (y.isEmpty()) y = Wrist.suggest(x)
                    if (x.isNotEmpty() && y.isNotEmpty()) cs.add(Pair(x, y))
                    k++
                }
                when {
                    j.isEmpty() || a.isEmpty() -> act.toast("質問の日本語とASCIIが必要です")
                    cs.isEmpty() -> act.toast("選択肢を1つ以上入力してください")
                    else -> sendWristQuestion(j, a, cs)
                }
            }.show()
    }

    /** 入力に応じて残り文字数を出し、ASCII欄に候補をヒント表示する */
    private fun watch(
        src: android.widget.EditText,
        label: TextView,
        limit: Int,
        ascii: android.widget.EditText
    ) {
        val upd = {
            val n = limit - src.text.length
            label.text = if (n >= 0) "残り $n 文字" else "${-n} 文字超過（切り詰められます）"
            label.setTextColor(if (n >= 0) Ui.SUB else Ui.RED)
            val sg = Wrist.suggest(src.text.toString())
            if (sg.isNotEmpty() && ascii.text.isEmpty()) ascii.hint = sg
        }
        upd()
        src.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { upd() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
        })
    }

    private fun sendWristQuestion(ja: String, ascii: String, choices: List<Pair<String, String>>) {
        val ids = wristTargets()
        if (ids.isEmpty()) {
            act.toast("送信先の端末がありません")
            return
        }
        val item = Wrist.buildQuestion(ja, ascii, choices, store.wristTtlMin)
        val n = Wrist.enqueue(store, ids, item)
        if (n == 0) {
            act.toast("ラベルが不足しているため送信できません")
            return
        }
        act.toast("$n 台へ送信しました")
        store.log("wrist", "アンケート送信「$ja」→ ${wristTargetLabel()}")
        render()
    }

    // ---------------------------------------------------------- 端末管理
    private fun addWristDevice() {
        val box = Ui.col(act, 8)
        val nm = Ui.edit(act, "装着者名（例: 田中）")
        val gp = Ui.edit(act, "グループ（例: 厨房班）", "既定")
        box.addView(Ui.tv(act, "装着者名", 12f, Ui.SUB))
        box.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "グループ", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(gp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(
            Ui.tv(act, "登録するとQRコードが表示されます。装着者のスマホで読み取って設定します。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
        )
        AlertDialog.Builder(act).setTitle("手首端末を登録")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("やめる", null)
            .setPositiveButton("登録") { _, _ ->
                val n = nm.text.toString().trim()
                if (n.isEmpty()) {
                    act.toast("装着者名を入力してください")
                } else {
                    val d = Wrist.register(store, n, gp.text.toString().trim().ifBlank { "既定" })
                    store.log("wrist", "端末を登録: ${d.name} (${d.id})")
                    render()
                    showWristQr(d)
                }
            }.show()
    }

    /**
     * 管理者スマホ自身のPebbleを使う設定。
     * 中継も同じ端末で行うため、接続先は 127.0.0.1 になる。
     * Wi-Fiが切れてもIPが変わっても設定を直す必要がない。
     */
    private fun setupSelf() {
        val box = Ui.col(act, 8)
        box.addView(
            Ui.tv(
                act,
                "この端末（管理者スマホ）に繋がっているPebbleを、手首端末として使えるようにします。" +
                        "他のスマホは要りません。",
                12f, Ui.FG
            )
        )
        val nm = Ui.edit(act, "表示名", "自分")
        box.addView(Ui.tv(act, "表示名", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        box.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(
            Ui.tv(
                act,
                "登録後、この端末のPebbleアプリで時計アプリ（放送室ウォッチ）の設定を開き、" +
                        "表示される接続情報を入力してください。接続先は 127.0.0.1 固定です。",
                10f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12))
        )
        AlertDialog.Builder(act).setTitle("この端末で使う")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("やめる", null)
            .setPositiveButton("登録") { _, _ ->
                val n = nm.text.toString().trim().ifBlank { "自分" }
                val d = Wrist.register(store, n, "管理者", true)
                store.log("wrist", "自分の手首端末を登録: ${d.name}")
                render()
                showSelfInfo(d)
            }.show()
    }

    private fun showSelfInfo(d: Wrist.WDev) {
        val box = Ui.col(act, 12)
        box.addView(
            Ui.tv(
                act,
                "この端末のPebbleアプリ →「放送室ウォッチ」の歯車 → 設定画面に、以下を入力してください。",
                12f, Ui.FG
            )
        )
        box.addView(Ui.tv(act, "接続先", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))
        box.addView(Ui.tv(act, "127.0.0.1", 16f, Ui.ACC, true))
        box.addView(Ui.tv(act, "ポート", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(Ui.tv(act, "${Proto.PORT_WRIST}", 16f, Ui.ACC, true))
        box.addView(Ui.tv(act, "端末ID", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(Ui.tv(act, d.id, 16f, Ui.ACC, true))
        box.addView(Ui.tv(act, "トークン", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(Ui.tv(act, d.token, 15f, Ui.ACC, true))
        box.addView(
            Ui.tv(
                act,
                "127.0.0.1 は「この端末自身」を指します。同じ端末の中で完結するため、" +
                        "Wi-Fiが切れても、IPアドレスが変わっても設定を直す必要はありません。",
                10f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16))
        )
        box.addView(Ui.ghost(act, "🔑 トークンを再発行", Ui.SUB) {
            Wrist.reissue(store, d.id)
            store.log("security", "自分の手首端末のトークンを再発行")
            act.toast("再発行しました。時計側の設定を入れ直してください")
            render()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))
        box.addView(Ui.ghost(act, "この端末での利用をやめる", Ui.RED) {
            Wrist.remove(store, d.id)
            store.log("wrist", "自分の手首端末を解除")
            act.toast("解除しました")
            render()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))

        AlertDialog.Builder(act).setTitle("${d.name} の接続情報")
            .setView(Ui.scroll(act, box))
            .setPositiveButton("閉じる", null)
            .setNeutralButton("共有") { _, _ ->
                shareText(
                    "放送室ウォッチ 接続情報\n接続先: 127.0.0.1\nポート: ${Proto.PORT_WRIST}\n端末ID: ${d.id}\nトークン: ${d.token}",
                    "放送室ウォッチ 接続情報"
                )
            }
            .show()
    }

    private fun showWristQr(d: Wrist.WDev) {
        val host = Vpn.vpnIp() ?: Net.localIp()
        val payload = Wrist.qrPayload(d, host)
        val box = Ui.col(act, 12)
        val px = Ui.dp(act, 230)
        val bmp = Qr.encode(payload, px)
        if (bmp != null) {
            val iv = android.widget.ImageView(act)
            iv.setImageBitmap(bmp)
            iv.setBackgroundColor(0xFFFFFFFF.toInt())
            iv.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8))
            val lp = LinearLayout.LayoutParams(px, px)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            box.addView(iv, lp)
        }
        box.addView(
            Ui.tv(act, "装着者のスマホで読み取ってください。手入力する場合は下記です。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12))
        )
        box.addView(Ui.tv(act, "接続先: $host:${Proto.PORT_WRIST}", 12f, Ui.FG),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        box.addView(Ui.tv(act, "端末ID: ${d.id}", 12f, Ui.FG), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "トークン: ${d.token}", 12f, Ui.ACC), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        AlertDialog.Builder(act).setTitle("${d.name} の接続情報")
            .setView(Ui.scroll(act, box))
            .setPositiveButton("閉じる", null)
            .setNeutralButton("共有") { _, _ ->
                shareText(
                    "放送室 手首端末の接続情報\n接続先: $host:${Proto.PORT_WRIST}\n端末ID: ${d.id}\nトークン: ${d.token}",
                    "手首端末の接続情報"
                )
            }
            .show()
    }

    private fun wristDeviceDialog(d: Wrist.WDev) {
        val box = Ui.col(act, 8)
        val nm = Ui.edit(act, "装着者名", d.name)
        val gp = Ui.edit(act, "グループ", d.group)
        box.addView(Ui.tv(act, "装着者名", 12f, Ui.SUB))
        box.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "グループ", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(gp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.ghost(act, "🔑 トークンを再発行", Ui.RED) {
            Wrist.reissue(store, d.id)
            store.log("security", "手首端末のトークンを再発行: ${d.name}")
            act.toast("再発行しました。QRを読み直してください")
            render()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14)))
        box.addView(
            Ui.tv(act, "再発行すると、いまの設定では接続できなくなります。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )
        AlertDialog.Builder(act).setTitle("${d.name} の設定")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setNeutralButton("削除") { _, _ ->
                Wrist.remove(store, d.id)
                store.log("wrist", "端末を削除: ${d.name}")
                render()
            }
            .setPositiveButton("保存") { _, _ ->
                Wrist.rename(
                    store, d.id,
                    nm.text.toString().trim().ifBlank { d.name },
                    gp.text.toString().trim().ifBlank { d.group }
                )
                render()
            }.show()
    }

    private fun wristHistory(d: Wrist.WDev) {
        val box = Ui.col(act, 8)
        val its = Wrist.items(store, d.id)
        if (its.isEmpty()) {
            box.addView(Ui.tv(act, "やりとりはまだありません。", 12f, Ui.SUB))
        }
        var i = 0
        while (i < its.size && i < 40) {
            val o = its[i]
            i++
            val row = Ui.col(act)
            row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
            val kind = if (o.optString("type") == "question") "❓" else "💬"
            row.addView(Ui.tv(act, "$kind ${o.optString("label_ja")}", 13f, Ui.FG, true))
            val st = when {
                o.has("answer") -> "回答: " + Wrist.choiceLabel(o, o.optInt("answer"))
                o.has("acked_at") -> "既読"
                else -> "未読"
            }
            row.addView(
                Ui.tv(act, "#${o.optInt("seq")}  $st", 11f,
                    if (o.has("answer") || o.has("acked_at")) Ui.GREEN else Ui.SUB)
            )
            row.addView(Ui.sep(act))
            box.addView(row)
        }
        AlertDialog.Builder(act).setTitle("${d.name} の履歴")
            .setView(Ui.scroll(act, box))
            .setPositiveButton("閉じる", null)
            .show()
    }

    // ============================================================ 7 ログ
    private fun tabLog(): View {
        val l = Ui.col(act, 14)
        val head = Ui.row(act)
        head.addView(Ui.tv(act, "📜 ログ", 17f, Ui.FG, true), LinearLayout.LayoutParams(0, Ui.WC, 1f))
        val fb = Ui.ghost(act, "", Ui.CYAN) { }
        fb.text = if (logFilterToday) "今日のみ" else "すべて"
        fb.setOnClickListener {
            logFilterToday = !logFilterToday
            render()
        }
        head.addView(fb)
        head.addView(Ui.ghost(act, "日報", Ui.GREEN) { reportDialog() })
        head.addView(Ui.ghost(act, "CSV", Ui.ACC) { exportCsv() })
        head.addView(Ui.ghost(act, "消去", Ui.RED) {
            store.clearLogs()
            render()
        })
        l.addView(head)

        val list = Ui.col(act)
        l.addView(list, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            list.removeAllViews()
            val a = store.logs()
            var shown = 0
            var i = 0
            while (i < a.length() && shown < 200) {
                val o = a.getJSONObject(i)
                i++
                val at = o.optLong("at")
                if (logFilterToday && !Store.today(at)) continue
                shown++
                val row = Ui.row(act)
                row.setPadding(0, Ui.dp(act, 5), 0, Ui.dp(act, 5))
                val kind = o.optString("kind")
                val col = when (kind) {
                    "emergency" -> Ui.RED
                    "broadcast" -> Ui.ACC
                    "call" -> Ui.CYAN
                    "schedule" -> Ui.GREEN
                    "security" -> Ui.RED
                    else -> Ui.SUB
                }
                row.addView(Ui.tv(act, Store.stamp(at), 10f, Ui.SUB), LinearLayout.LayoutParams(Ui.dp(act, 92), Ui.WC))
                val body = Ui.col(act)
                body.addView(Ui.tv(act, o.optString("text"), 12f, Ui.FG))
                body.addView(Ui.tv(act, kindLabel(kind), 9f, col))
                row.addView(body, LinearLayout.LayoutParams(0, Ui.WC, 1f))
                list.addView(row)
            }
            if (shown == 0) list.addView(Ui.tv(act, "ログはありません。", 12f, Ui.SUB))
        }
        return Ui.scroll(act, l)
    }

    // ============================================================ 6 設定
    /** 外部トリガーの使い方 */
    private fun triggerCard(): LinearLayout {
        val c = Ui.card(act)
        c.addView(Ui.tv(act, "🔗 外部トリガー", 16f, Ui.FG, true))
        c.addView(
            Ui.tv(
                act,
                "IFTTT・Termux・cron などから下記を叩くと災害放送を開始できます。同一LAN内からのみ受け付けます。",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        val url = "http://${Net.localIp()}:${Proto.PORT_TRIGGER}/fire?s=quake&pin=${store.pin}"
        c.addView(Ui.tv(act, url, 11f, Ui.ACC), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        c.addView(
            Ui.tv(
                act,
                "s に指定できる値: " + Disaster.all.joinToString(" / ") { it.id } +
                        "　停止は s=stop。target=floor:3 のように対象も指定できます。",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        c.addView(Ui.sep(act), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        c.addView(
            Ui.tv(act, "🌐 遠隔端末の登録受付", 14f, Ui.FG, true),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10))
        )
        c.addView(
            Ui.tv(
                act,
                "別サブネットやVPN越しの端末は、端末設定の「コンソールのアドレス」に " +
                        "${Net.localIp()}:${Proto.PORT_REG} を入れると自分から登録しにきます。" +
                        "制御は登録されたアドレスへ直接届く必要があるため、双方向に経路が通っていることが前提です。",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )

        // VPN状態
        val vpnIp = Vpn.vpnIp()
        val vpnApps = Vpn.installed(act)
        c.addView(Ui.sep(act), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        c.addView(Ui.tv(act, "🔐 VPN連携", 14f, Ui.FG, true), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        if (vpnIp != null) {
            c.addView(
                Ui.tv(act, "VPN接続中。端末側にはこのアドレスを設定してください:", 11f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
            c.addView(Ui.tv(act, "$vpnIp:${Proto.PORT_REG}", 15f, Ui.GREEN, true), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
            c.addView(
                Ui.tv(
                    act,
                    "コンソールをモバイル回線（4G/5G）で運用する場合も、全端末が同じVPNに入っていれば放送・通話とも届きます。音が途切れる場合は音質を8kHzに落としてください。",
                    10f, Ui.SUB
                ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
        } else if (vpnApps.isNotEmpty()) {
            c.addView(
                Ui.tv(act, vpnApps.joinToString("・") { it.name } + " が入っていますが、未接続です。", 11f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
            c.addView(Ui.ghost(act, "VPNアプリを開く", Ui.CYAN) {
                Vpn.launch(act, vpnApps[0])
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        } else {
            c.addView(
                Ui.tv(
                    act,
                    "VPNアプリは見つかりませんでした。別ネットワークの端末と繋ぐ場合は、全端末に Tailscale 等を導入してください（このアプリはVPNを内蔵しません）。",
                    11f, Ui.SUB
                ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
            )
        }
        return c
    }

    /** 時報の設定 */
    private fun timeSignalCard(): LinearLayout {
        val c = Ui.card(act)
        c.addView(Ui.tv(act, "🕐 時報", 16f, Ui.FG, true))
        c.addView(
            Ui.tv(act, "毎正時に自動でチャイムや時刻の読み上げを流します。", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )

        val on = Ui.ghost(act, "", Ui.FG) { }
        on.text = if (store.timeSignalEnabled) "時報: ON" else "時報: OFF"
        on.setOnClickListener {
            store.timeSignalEnabled = !store.timeSignalEnabled
            render()
        }
        c.addView(on, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        if (!store.timeSignalEnabled) return c

        // 時間帯
        val r1 = Ui.row(act)
        val fb = Ui.ghost(act, "開始 ${store.timeSignalFrom}時", Ui.FG) { }
        fb.setOnClickListener {
            pickHour("時報を始める時刻") { h ->
                store.timeSignalFrom = h
                render()
            }
        }
        val tb = Ui.ghost(act, "終了 ${store.timeSignalTo}時", Ui.FG) { }
        tb.setOnClickListener {
            pickHour("時報を終える時刻") { h ->
                store.timeSignalTo = h
                render()
            }
        }
        r1.addView(fb, cw())
        r1.addView(tb, cw())
        c.addView(r1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        c.addView(
            Ui.tv(act, "終了時刻の正時も鳴ります（例: 8〜18 なら 18:00 まで）。", 10f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        // 30分
        val half = Ui.ghost(act, "", Ui.FG) { }
        half.text = if (store.timeSignalHalf) "30分にも鳴らす: ON" else "30分にも鳴らす: OFF"
        half.setOnClickListener {
            store.timeSignalHalf = !store.timeSignalHalf
            half.text = if (store.timeSignalHalf) "30分にも鳴らす: ON" else "30分にも鳴らす: OFF"
        }
        c.addView(half, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        // 内容
        c.addView(Ui.tv(act, "鳴らす内容", 11f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        val r2 = Ui.row(act)
        val modes = listOf("chime" to "チャイム", "voice" to "読み上げ", "both" to "両方")
        for ((k, v) in modes) {
            val b = Ui.ghost(act, v, if (store.timeSignalMode == k) Ui.ACC else Ui.SUB) {
                store.timeSignalMode = k
                render()
            }
            r2.addView(b, cw())
        }
        c.addView(r2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        // 対象
        val tg = Ui.ghost(act, "対象: " + Targeting.label(store.timeSignalTarget), Ui.FG) { }
        tg.setOnClickListener {
            val opts = arrayOf("全館", "現在の放送対象を使う")
            AlertDialog.Builder(act).setTitle("時報の対象")
                .setItems(opts) { _, i ->
                    store.timeSignalTarget = if (i == 0) "all" else targetSpec
                    render()
                }.show()
        }
        c.addView(tg, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        c.addView(Ui.ghost(act, "▶ いま試す", Ui.CYAN) {
            val t = Targeting.resolve(store.timeSignalTarget)
            if (t.isEmpty()) {
                act.toast("対象端末がオンラインではありません")
            } else {
                val cal = java.util.Calendar.getInstance()
                val hh = cal.get(java.util.Calendar.HOUR_OF_DAY)
                if (store.timeSignalMode == "chime") {
                    sendChime(store.timeSignalTarget, false)
                } else {
                    sendTts(
                        store.timeSignalTarget,
                        "ただいま、${hh}時をお知らせします。", false, "時報"
                    )
                }
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        return c
    }

    private fun pickHour(title: String, cb: (Int) -> Unit) {
        val hours = (0..23).map { "${it}時" }.toTypedArray()
        AlertDialog.Builder(act).setTitle(title)
            .setItems(hours) { _, i -> cb(i) }
            .show()
    }

    /** 月次稼働率の内訳 */
    private fun slaDialog() {
        val box = Ui.col(act, 8)
        for (off in 0 downTo -1) {
            val sla = Report.sla(store, off)
            box.addView(
                Ui.tv(act, sla.month + if (off == 0) "（今月）" else "（先月）", 15f, Ui.ACC, true),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, if (off == 0) 0 else 16))
            )
            if (sla.pct < 0) {
                box.addView(Ui.tv(act, "データがありません。", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
                continue
            }
            box.addView(
                Ui.tv(act, String.format("全体 %.1f%%（%d日分）", sla.pct, sla.days), 13f, Ui.FG, true),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
            )
            for ((label, pct) in sla.perDev) {
                val col = when {
                    pct >= 99.0 -> Ui.GREEN
                    pct >= 95.0 -> Ui.ACC
                    else -> Ui.RED
                }
                box.addView(
                    Ui.tv(act, String.format("・%s  %.1f%%", label, pct), 12f, col),
                    Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 3))
                )
            }
        }
        box.addView(
            Ui.tv(
                act,
                "日報の稼働サンプル（5分間隔の観測）を月単位で積算しています。コンソールが停止していた日は母数に含まれません。",
                10f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
        )
        AlertDialog.Builder(act)
            .setTitle("📈 稼働率の内訳")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setPositiveButton("共有") { _, _ ->
                val sla = Report.sla(store)
                val sb = StringBuilder()
                sb.append("放送室 稼働率レポート ").append(sla.month).append('\n')
                sb.append(String.format("全体 %.1f%%（%d日分・%dサンプル）", sla.pct, sla.days, sla.samples)).append('\n')
                for ((label, pct) in sla.perDev) {
                    sb.append(String.format("%s  %.1f%%", label, pct)).append('\n')
                }
                shareText(sb.toString(), "放送室 稼働率 " + sla.month)
            }
            .show()
    }

    /** 日報の一覧・閲覧・共有 */
    private fun reportDialog() {
        val box = Ui.col(act, 8)
        box.addView(
            Ui.tv(
                act,
                if (store.reportEnabled) "毎日 ${store.reportHour}:00 に自動生成します。"
                else "自動生成はオフです（設定タブで変更できます）。",
                11f, Ui.SUB
            )
        )
        box.addView(Ui.btn(act, "いまの内容で今日の日報を作る") {
            val o = ConsoleService.instance?.makeReport(false)
            if (o == null) {
                act.toast("コンソールが起動していません")
            } else {
                showReport(o)
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        val a = store.reports()
        if (a.length() == 0) {
            box.addView(
                Ui.tv(act, "保存された日報はまだありません。", 12f, Ui.SUB),
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
            )
        }
        var i = a.length() - 1
        while (i >= 0) {
            val o = a.getJSONObject(i)
            i--
            val row = Ui.col(act)
            row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8))
            row.addView(Ui.tv(act, o.optString("date"), 14f, Ui.ACC, true))
            row.addView(Ui.tv(act, Report.headline(o), 11f, Ui.SUB))
            row.addView(Ui.ghost(act, "開く", Ui.FG) { showReport(o) },
                Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
            row.addView(Ui.sep(act))
            box.addView(row)
        }

        AlertDialog.Builder(act)
            .setTitle("📋 日報")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showReport(o: org.json.JSONObject) {
        val text = o.optString("text")
        val box = Ui.col(act, 8)
        box.addView(Ui.tv(act, text, 12f, Ui.FG))
        AlertDialog.Builder(act)
            .setTitle("日報 " + o.optString("date"))
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setPositiveButton("共有") { _, _ -> shareText(text, "放送室 日報 " + o.optString("date")) }
            .show()
    }

    private fun shareText(text: String, subject: String) {
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_SUBJECT, subject)
            send.putExtra(Intent.EXTRA_TEXT, text)
            act.startActivity(Intent.createChooser(send, "日報を共有"))
        } catch (e: Exception) {
            act.toast("共有先が見つかりません")
        }
    }

    private fun kindLabel(k: String): String = when (k) {
        "emergency" -> "緊急"
        "broadcast" -> "放送"
        "call" -> "通話"
        "schedule" -> "予約"
        "security" -> "セキュリティ"
        "alert" -> "アラート"
        "system" -> "システム"
        else -> k
    }

    /** ログをCSVに書き出して共有する */
    private fun exportCsv() {
        bg {
            try {
                val dir = java.io.File(act.filesDir, "exports")
                if (!dir.exists()) dir.mkdirs()
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.JAPAN)
                    .format(java.util.Date())
                val f = java.io.File(dir, "housou_log_$stamp.csv")
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.JAPAN)
                val sb = StringBuilder()
                sb.append("日時,種別,対象,定型文,内容\n")
                val a = store.logs()
                var i = a.length() - 1
                while (i >= 0) {
                    val o = a.getJSONObject(i)
                    i--
                    sb.append(csv(fmt.format(java.util.Date(o.optLong("at"))))).append(',')
                    sb.append(csv(kindLabel(o.optString("kind")))).append(',')
                    val tgt = o.optString("target")
                    sb.append(csv(if (tgt.isEmpty()) "" else Targeting.label(tgt))).append(',')
                    sb.append(csv(o.optString("tag"))).append(',')
                    sb.append(csv(o.optString("text"))).append('\n')
                }
                f.writeText("\uFEFF" + sb.toString(), Charsets.UTF_8)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    act, act.packageName + ".files", f
                )
                ui {
                    val send = Intent(Intent.ACTION_SEND)
                    send.type = "text/csv"
                    send.putExtra(Intent.EXTRA_STREAM, uri)
                    send.putExtra(Intent.EXTRA_SUBJECT, "放送室 ログ $stamp")
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        act.startActivity(Intent.createChooser(send, "ログを書き出す"))
                    } catch (e: Exception) {
                        act.toast("共有先が見つかりません")
                    }
                }
            } catch (e: Exception) {
                ui { act.toast("書き出しに失敗しました") }
            }
        }
    }

    private fun csv(v: String): String {
        val t = v.replace("\"", "\"\"")
        return "\"" + t + "\""
    }

    private fun tabSettings(): View {
        val l = Ui.col(act, 14)

        val c0 = Ui.card(act)
        c0.addView(Ui.tv(act, "🏢 建物", 16f, Ui.FG, true))
        c0.addView(
            Ui.tv(
                act,
                "この名前が端末に配られ、複数建物の切り分けに使われます。現在のスコープ: " +
                        (if (Targeting.scope.isEmpty()) "すべての建物" else Targeting.scope),
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        val bn = Ui.edit(act, "建物名", store.building)
        c0.addView(bn, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val fl = Ui.edit(act, "フロア数", store.floors.toString(), true)
        c0.addView(fl, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        c0.addView(Ui.btn(act, "保存") {
            store.building = bn.text.toString().ifBlank { store.building }
            store.floors = fl.text.toString().toIntOrNull() ?: store.floors
            act.toast("保存しました")
            refresh()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        l.addView(c0)

        val cr = Ui.card(act)
        cr.addView(Ui.tv(act, "📋 日報", 16f, Ui.FG, true))
        val rp = Ui.ghost(act, "", Ui.FG) { }
        rp.text = if (store.reportEnabled) "自動生成: ON" else "自動生成: OFF"
        rp.setOnClickListener {
            store.reportEnabled = !store.reportEnabled
            rp.text = if (store.reportEnabled) "自動生成: ON" else "自動生成: OFF"
        }
        cr.addView(rp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val rh = Ui.ghost(act, "生成時刻: ${store.reportHour}:00", Ui.FG) { }
        rh.setOnClickListener {
            val hours = (0..23).map { "${it}:00" }.toTypedArray()
            AlertDialog.Builder(act).setTitle("日報の生成時刻")
                .setItems(hours) { _, i ->
                    store.reportHour = i
                    rh.text = "生成時刻: ${i}:00"
                }.show()
        }
        cr.addView(rh, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        cr.addView(
            Ui.tv(
                act,
                "その日の放送実績・緊急放送・端末稼働率・故障予兆をまとめます。ログタブの「日報」から閲覧・共有できます。",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        l.addView(cr, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val cw2 = Ui.card(act)
        cw2.addView(Ui.tv(act, "⌚ 手首端末連携", 16f, Ui.FG, true))
        cw2.addView(
            Ui.tv(act, "Pebbleスマートウォッチへ短文と3択アンケートを送ります。接続先:", 11f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        cw2.addView(
            Ui.tv(act, "${Vpn.vpnIp() ?: Net.localIp()}:${Proto.PORT_WRIST}", 14f, Ui.ACC, true),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )
        val pi = Ui.ghost(act, "ポーリング間隔: ${store.wristPollSec}秒", Ui.FG) { }
        pi.setOnClickListener {
            val opts = listOf(10, 20, 30, 60)
            AlertDialog.Builder(act).setTitle("ポーリング間隔")
                .setItems(opts.map { "${it}秒" }.toTypedArray()) { _, i ->
                    store.wristPollSec = opts[i]
                    pi.text = "ポーリング間隔: ${opts[i]}秒"
                }.show()
        }
        cw2.addView(pi, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        val tl = Ui.ghost(act, "有効期限: ${store.wristTtlMin}分", Ui.FG) { }
        tl.setOnClickListener {
            val opts = listOf(15, 30, 60, 180, 720)
            AlertDialog.Builder(act).setTitle("送信内容の有効期限")
                .setItems(opts.map { "${it}分" }.toTypedArray()) { _, i ->
                    store.wristTtlMin = opts[i]
                    tl.text = "有効期限: ${opts[i]}分"
                }.show()
        }
        cw2.addView(tl, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))
        cw2.addView(
            Ui.tv(
                act,
                "期限を過ぎた項目は配信されません。緊急用途には使えません（最大でポーリング間隔ぶん遅延します）。",
                10f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        l.addView(cw2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        l.addView(triggerCard(), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val c1 = Ui.card(act)
        c1.addView(Ui.tv(act, "🎚 音声", 16f, Ui.FG, true))
        val q = Ui.ghost(act, "", Ui.FG) { }
        q.text = if (store.quality == "low") "音質: 低帯域（8kHz・弱電波向け）" else "音質: 高音質（16kHz）"
        q.setOnClickListener {
            store.quality = if (store.quality == "low") "high" else "low"
            q.text = if (store.quality == "low") "音質: 低帯域（8kHz・弱電波向け）" else "音質: 高音質（16kHz）"
        }
        c1.addView(q, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        val ag = Ui.ghost(act, "", Ui.FG) { }
        ag.text = if (store.autoGain) "自動音量調整(AGC): ON" else "自動音量調整(AGC): OFF"
        ag.setOnClickListener {
            store.autoGain = !store.autoGain
            ag.text = if (store.autoGain) "自動音量調整(AGC): ON" else "自動音量調整(AGC): OFF"
        }
        c1.addView(ag, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        val hint = Ui.tv(act, "", 11f, Ui.CYAN)
        c1.addView(hint, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val c2 = Ui.card(act)
        c2.addView(Ui.tv(act, "🔐 セキュリティ", 16f, Ui.FG, true))
        val p1 = Ui.edit(act, "新しいPIN", "", true)
        c2.addView(p1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        c2.addView(Ui.btn(act, "PINを変更") {
            val v = p1.text.toString()
            if (v.length < 4) act.toast("4桁以上で設定してください")
            else {
                store.pin = v
                store.log("security", "管理PINを変更")
                act.toast("変更しました")
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))

        val c3 = Ui.card(act)
        c3.addView(Ui.tv(act, "🛠 システム", 16f, Ui.FG, true))
        c3.addView(
            Ui.tv(
                act,
                "コンソールIP ${Net.localIp()}\n制御 TCP ${Proto.PORT_CTRL} / 探索 UDP ${Proto.PORT_ANNOUNCE}\n" +
                        "音声 下り UDP ${Proto.PORT_AUDIO_DOWN} / 上り UDP ${Proto.PORT_AUDIO_UP}\nバージョン ${Proto.APP_VER}",
                11f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        c3.addView(Ui.ghost(act, "端末台帳をリセット", Ui.RED) {
            Registry.clear()
            Registry.save(store)
            act.toast("台帳を消去しました")
            render()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        c3.addView(Ui.ghost(act, "この端末の役割を変更", Ui.ACC) {
            act.switchMode()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))
        l.addView(c3, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        l.addView(Ui.space(act, 20))

        refreshers.add {
            val s = Diag.suggestQuality(Registry.all())
            hint.text = if (s == "low") "AI推奨: 遅延/電波状況から低帯域モードを推奨します。"
            else "AI推奨: 現在の回線品質なら高音質で問題ありません。"
        }
        return Ui.scroll(act, l)
    }
}
