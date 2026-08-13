package com.appathy.housou

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.ImageView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/** フロア端末の待機／放送中画面 */
class TerminalUi(private val act: MainActivity, private val store: Store) {

    private lateinit var root: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private var attached = false

    fun attach(container: FrameLayout) {
        root = container
        attached = true
        startService()
        TerminalService.onUpdate = { render() }
        resumeKiosk()
        render()
        tick()
    }

    fun detach() {
        attached = false
        TerminalService.onUpdate = null
    }

    private fun tick() {
        if (!attached) return
        if (TerminalService.kioskChanged) {
            TerminalService.kioskChanged = false
            if (store.kioskEnabled) startKiosk()
            else try { act.stopLockTask() } catch (e: Exception) { }
        }
        render()
        val wait = when {
            TerminalService.alertOn -> 700L
            TerminalService.noticeUntil > System.currentTimeMillis() -> 1000L
            TerminalService.captionText.isNotEmpty() -> 500L
            else -> 2000L
        }
        h.postDelayed({ tick() }, wait)
    }

    private fun startService() {
        val i = Intent(act, TerminalService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.startForegroundService(i)
            } else {
                act.startService(i)
            }
        } catch (e: Exception) { }
    }

    private fun volumePercent(): Int {
        return try {
            val a = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) 0 else a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        } catch (e: Exception) {
            0
        }
    }

    private fun render() {
        if (!attached) return
        if (TerminalService.alertOn) {
            renderAlert()
            return
        }
        if (TerminalService.noticeUntil > System.currentTimeMillis()) {
            renderNotice()
            return
        }
        if (TerminalService.captionText.isNotEmpty()) {
            renderCaption()
            return
        }
        val busy = TerminalService.playing || TerminalService.talking
        val bg = if (busy) 0xFF3A0E0E.toInt() else Ui.BG

        val l = Ui.col(act, 22)
        l.setBackgroundColor(bg)
        l.gravity = Gravity.CENTER_HORIZONTAL

        val state = when {
            TerminalService.talking -> "通 話 中"
            TerminalService.playing -> "放 送 中"
            TerminalService.running -> "待 機 中"
            else -> "停 止 中"
        }
        val stateColor = when {
            busy -> Ui.RED
            TerminalService.running -> Ui.GREEN
            else -> Ui.SUB
        }

        val icon = Ui.tv(act, if (busy) "🔊" else "🔈", 58f)
        icon.gravity = Gravity.CENTER
        l.addView(icon, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 18)))

        val st = Ui.tv(act, state, 34f, stateColor, true)
        st.gravity = Gravity.CENTER
        l.addView(st, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))

        val nm = Ui.tv(act, "${store.termFloor}F  ${store.termName}", 22f, Ui.FG, true)
        nm.gravity = Gravity.CENTER
        l.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 18)))

        val gp = Ui.tv(act, "グループ: ${store.termGroup}", 13f, Ui.SUB)
        gp.gravity = Gravity.CENTER
        l.addView(gp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        // 接続状態カード
        val c = Ui.card(act)
        c.addView(kv("自端末IP", Net.localIp()))
        c.addView(kv("制御ポート", Proto.PORT_CTRL.toString()))
        c.addView(
            kv(
                "コンソール",
                if (TerminalService.consoleIp.isEmpty()) "未接続" else TerminalService.consoleIp
            )
        )
        c.addView(kv("音量", "${volumePercent()}%"))
        c.addView(
            kv(
                "最終コマンド",
                if (TerminalService.lastCmdAt == 0L) "-"
                else "${TerminalService.lastCmd} (${Store.hhmm(TerminalService.lastCmdAt)})"
            )
        )
        c.addView(kv("マイク / スピーカー", ok(TerminalService.micOn) + " / " + ok(TerminalService.spkOn)))
        l.addView(c, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 22)))

        // 操作
        val r = Ui.row(act)
        val b1 = Ui.ghost(act, "🔔 テスト音", Ui.FG) {
            Thread { Audio.playBlob(Proto.RATE_HIGH, Audio.chime(Proto.RATE_HIGH, false)) }.start()
        }
        val b2 = Ui.ghost(act, "⚙ 設定", Ui.FG) { askPin() }
        val p1 = LinearLayout.LayoutParams(0, Ui.WC, 1f)
        p1.rightMargin = Ui.dp(act, 6)
        val p2 = LinearLayout.LayoutParams(0, Ui.WC, 1f)
        p2.leftMargin = Ui.dp(act, 6)
        r.addView(b1, p1)
        r.addView(b2, p2)
        l.addView(r, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 18)))

        l.addView(Ui.ghost(act, "📷 登録用QRコードを表示", Ui.ACC) { showQr() },
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        val rb = Ui.ghost(act, "🔈 出力先: " + Routing.status(act, store.route), Ui.FG) { pickRoute() }
        l.addView(rb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        val kb = Ui.ghost(act, "", if (store.kioskEnabled) Ui.ACC else Ui.FG) { }
        kb.text = if (store.kioskEnabled) "🔒 キオスクモード: ON" else "🔒 キオスクモード: OFF"
        kb.setOnClickListener {
            if (store.kioskEnabled) {
                // 解除はPIN必須
                askKioskOff()
            } else {
                confirmKioskOn()
            }
        }
        l.addView(kb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        val cb = Ui.ghost(act, "", Ui.FG) { }
        cb.text = if (store.captionEnabled) "💬 字幕表示: ON" else "💬 字幕表示: OFF"
        cb.setOnClickListener {
            store.captionEnabled = !store.captionEnabled
            if (!store.captionEnabled) TerminalService.clearCaption()
            cb.text = if (store.captionEnabled) "💬 字幕表示: ON" else "💬 字幕表示: OFF"
        }
        l.addView(cb, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        if (!TerminalService.running) {
            l.addView(Ui.btn(act, "サービスを開始", Ui.GREEN) {
                startService()
                h.postDelayed({ render() }, 600)
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        }

        val note = Ui.tv(act, "画面を消しても常駐します。電源に接続した状態での運用を推奨します。", 11f, Ui.SUB)
        note.gravity = Gravity.CENTER
        l.addView(note, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 22)))

        root.removeAllViews()
        root.addView(Ui.scroll(act, l))
    }

    /**
     * 読み上げ中の字幕。
     * 離れた位置からでも読めるよう、本文だけを大きく出す。
     * 文字数に応じて文字サイズを落とし、長文でも収まるようにしている。
     */
    private fun renderCaption() {
        val text = TerminalService.captionText
        val urgent = TerminalService.captionUrgent
        val l = Ui.col(act, 20)
        l.setBackgroundColor(if (urgent) 0xFF4A1010.toInt() else 0xFF0B1420.toInt())
        l.gravity = Gravity.CENTER_HORIZONTAL

        val head = Ui.tv(
            act,
            if (urgent) "🚨 緊急放送" else "📢 放送中",
            16f, if (urgent) 0xFFFF8A8A.toInt() else Ui.ACC, true
        )
        head.gravity = Gravity.CENTER
        l.addView(head, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))

        val size = when {
            text.length <= 20 -> 40f
            text.length <= 40 -> 32f
            text.length <= 80 -> 26f
            text.length <= 140 -> 21f
            else -> 17f
        }
        val body = Ui.tv(act, text, size, 0xFFFFFFFF.toInt(), true)
        body.gravity = Gravity.CENTER
        body.setLineSpacing(0f, 1.25f)
        l.addView(body, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 20)))

        val text2 = TerminalService.captionText2
        if (text2.isNotEmpty()) {
            l.addView(Ui.sep(act), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))
            val size2 = when {
                text2.length <= 30 -> 24f
                text2.length <= 70 -> 19f
                else -> 15f
            }
            val body2 = Ui.tv(act, text2, size2, 0xFFB8D4FF.toInt(), true)
            body2.gravity = Gravity.CENTER
            body2.setLineSpacing(0f, 1.2f)
            l.addView(body2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 12)))
        }

        val nm = Ui.tv(act, "${store.termFloor}F ${store.termName}", 13f, Ui.SUB)
        nm.gravity = Gravity.CENTER
        l.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 28)))

        root.removeAllViews()
        root.addView(Ui.scroll(act, l))
    }

    /** 放送予告のカウントダウン全画面 */
    private fun renderNotice() {
        val remain = ((TerminalService.noticeUntil - System.currentTimeMillis()) / 1000).toInt() + 1
        val l = Ui.col(act, 24)
        l.setBackgroundColor(0xFF102410.toInt())
        l.gravity = Gravity.CENTER_HORIZONTAL

        val head = Ui.tv(act, "📣 放送予告", 20f, Ui.GREEN, true)
        head.gravity = Gravity.CENTER
        l.addView(head, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 30)))

        val cnt = Ui.tv(act, "$remain", 96f, 0xFFFFFFFF.toInt(), true)
        cnt.gravity = Gravity.CENTER
        l.addView(cnt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))

        val unit = Ui.tv(act, "秒後に放送を開始します", 16f, Ui.SUB)
        unit.gravity = Gravity.CENTER
        l.addView(unit, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        val txt = Ui.tv(act, TerminalService.noticeText, 15f, 0xFFD8F0D8.toInt())
        txt.gravity = Gravity.CENTER
        l.addView(txt, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 24)))

        val nm = Ui.tv(act, "${store.termFloor}F ${store.termName}", 13f, Ui.SUB)
        nm.gravity = Gravity.CENTER
        l.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 28)))

        root.removeAllViews()
        root.addView(Ui.scroll(act, l))
    }

    /**
     * キオスクモード。
     * Androidの画面ピン留め（Lock Task）でこのアプリから出られなくする。
     * 端末管理者権限は使わないため、初回はOSの確認ダイアログが出る。
     * 解除はアプリ内トグル（PIN必須）か、OSの解除操作（戻る＋タスクキー長押し）。
     */
    private fun confirmKioskOn() {
        AlertDialog.Builder(act)
            .setTitle("キオスクモードを開始")
            .setMessage(
                "画面がこのアプリに固定され、ホームや他のアプリへ移動できなくなります。\n\n" +
                "解除するにはこのボタンをもう一度押してPINを入力するか、" +
                "「戻る」と「タスク一覧」を同時に長押しします。\n\n" +
                "開始時にAndroidの確認が表示されたら「はい」を選んでください。"
            )
            .setPositiveButton("開始") { _, _ ->
                store.kioskEnabled = true
                startKiosk()
                render()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun startKiosk() {
        try {
            act.startLockTask()
            store.log("security", "キオスクモードを開始")
        } catch (e: Exception) {
            act.toast("ピン留めを開始できませんでした")
        }
    }

    private fun askKioskOff() {
        val input = Ui.edit(act, "PIN", "")
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(act)
            .setTitle("キオスクモードを解除")
            .setView(input)
            .setPositiveButton("解除") { _, _ ->
                if (input.text.toString() == store.pin) {
                    store.kioskEnabled = false
                    try { act.stopLockTask() } catch (e: Exception) { }
                    store.log("security", "キオスクモードを解除")
                    render()
                } else {
                    act.toast("PINが違います")
                    store.log("security", "キオスク解除のPIN不一致")
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** サービス起動後に呼ばれる。キオスク設定が残っていれば再固定する */
    fun resumeKiosk() {
        if (store.kioskEnabled) startKiosk()
    }

    /** 音声出力先の切り替え（接続済みのものだけ出す） */
    private fun pickRoute() {
        val avail = Routing.available(act)
        val items = avail.map { Routing.label(it) }.toTypedArray()
        AlertDialog.Builder(act).setTitle("音声出力先")
            .setItems(items) { _, i ->
                store.route = avail[i]
                act.toast("出力先を " + Routing.label(avail[i]) + " にしました")
                render()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 災害放送を受信している間の全画面警報表示 */
    private var flash = false

    private fun renderAlert() {
        flash = !flash
        val l = Ui.col(act, 24)
        l.setBackgroundColor(if (flash) 0xFF7A0B0B.toInt() else 0xFF3A0E0E.toInt())
        l.gravity = Gravity.CENTER_HORIZONTAL

        val icon = Ui.tv(act, "⚠", 72f)
        icon.gravity = Gravity.CENTER
        l.addView(icon, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 24)))

        val ttl = Ui.tv(act, TerminalService.alertName, 40f, 0xFFFFFFFF.toInt(), true)
        ttl.gravity = Gravity.CENTER
        l.addView(ttl, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8)))

        val sub = Ui.tv(act, "緊 急 放 送", 20f, 0xFFFFD5D5.toInt(), true)
        sub.gravity = Gravity.CENTER
        l.addView(sub, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6)))

        if (TerminalService.alertText.isNotEmpty()) {
            val body = Ui.tv(act, TerminalService.alertText, 17f, 0xFFFFFFFF.toInt())
            body.gravity = Gravity.CENTER
            l.addView(body, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 24)))
        }

        val nm = Ui.tv(act, "${store.termFloor}F ${store.termName}", 14f, 0xFFFFC9C9.toInt())
        nm.gravity = Gravity.CENTER
        l.addView(nm, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 28)))

        root.removeAllViews()
        root.addView(Ui.scroll(act, l))
    }

    /** コンソールのカメラで読み取らせる端末登録QR */
    private fun showQr() {
        val box = Ui.col(act, 16)
        val px = Ui.dp(act, 240)
        val bmp = Qr.encode(Qr.payload(store), px)
        if (bmp == null) {
            act.toast("QRを生成できませんでした")
            return
        }
        val iv = ImageView(act)
        iv.setImageBitmap(bmp)
        iv.setBackgroundColor(0xFFFFFFFF.toInt())
        iv.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8))
        val lp = LinearLayout.LayoutParams(px, px)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(iv, lp)
        box.addView(
            Ui.tv(act, "管理コンソールの「端末」タブ →「QRで追加」で読み取ってください。", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
        )
        box.addView(
            Ui.tv(act, "${store.termFloor}F ${store.termName} / ${Net.localIp()}", 12f, Ui.FG),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 6))
        )
        AlertDialog.Builder(act)
            .setTitle("端末登録QR")
            .setView(Ui.scroll(act, box))
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun ok(b: Boolean) = if (b) "有効" else "無効"

    private fun kv(k: String, v: String): LinearLayout {
        val r = Ui.row(act)
        r.setPadding(0, Ui.dp(act, 5), 0, Ui.dp(act, 5))
        val a = Ui.tv(act, k, 13f, Ui.SUB)
        val b = Ui.tv(act, v, 13f, Ui.FG, true)
        b.gravity = Gravity.END
        r.addView(a, LinearLayout.LayoutParams(0, Ui.WC, 1f))
        r.addView(b, LinearLayout.LayoutParams(0, Ui.WC, 1.4f))
        return r
    }

    private fun askPin() {
        val e = Ui.edit(act, "管理PIN")
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val box = Ui.col(act, 18)
        box.addView(e)
        AlertDialog.Builder(act)
            .setTitle("管理者設定")
            .setView(box)
            .setNegativeButton("閉じる", null)
            .setPositiveButton("開く") { _, _ ->
                if (e.text.toString() == store.pin) settings()
                else act.toast("PINが違います")
            }
            .show()
    }

    private fun settings() {
        val box = Ui.col(act, 18)
        val name = Ui.edit(act, "端末名", store.termName)
        val floor = Ui.edit(act, "フロア", store.termFloor.toString(), true)
        val grp = Ui.edit(act, "グループ", store.termGroup)
        box.addView(Ui.tv(act, "端末名", 12f, Ui.SUB))
        box.addView(name, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "フロア", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(floor, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(Ui.tv(act, "グループ", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(grp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        val bldg = Ui.edit(act, "建物名", store.building)
        box.addView(Ui.tv(act, "建物", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 10)))
        box.addView(bldg, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        val host = Ui.edit(act, "例: 192.168.1.10 / console.example.net", store.consoleHost)
        box.addView(
            Ui.tv(act, "コンソールのアドレス（遠隔運用）", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 14))
        )
        box.addView(host, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        box.addView(
            Ui.tv(
                act,
                "同一Wi-Fi内なら空欄でよい（自動検出されます）。別サブネットやVPN越しの場合だけ、" +
                        "コンソールのアドレスを入れると10秒ごとに自分から登録しにいきます。" +
                        "現在: " + (if (TerminalService.remoteOk) "登録できています" else "未使用 / 未到達"),
                10f, Ui.SUB
            ), Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4))
        )

        // VPN連携
        val vpnIp = Vpn.vpnIp()
        val vpnApps = Vpn.installed(act)
        val vpnStatus = when {
            vpnIp != null -> "接続中（この端末のVPNアドレス: $vpnIp）"
            vpnApps.isNotEmpty() -> vpnApps.joinToString("・") { it.name } + " が入っていますが未接続です"
            else -> "VPNアプリが見つかりません（遠隔運用には Tailscale 等を入れてください）"
        }
        box.addView(
            Ui.tv(act, "VPN: $vpnStatus", 10f, if (vpnIp != null) Ui.GREEN else Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 8))
        )
        if (vpnApps.isNotEmpty() && vpnIp == null) {
            box.addView(Ui.ghost(act, "VPNアプリを開く", Ui.CYAN) {
                Vpn.launch(act, vpnApps[0])
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))
        }
        box.addView(Ui.ghost(act, "🔎 コンソールへの疎通テスト", Ui.ACC) {
            val h = host.text.toString().trim()
            if (h.isEmpty()) {
                act.toast("コンソールのアドレスを入力してください")
            } else {
                act.toast("テスト中…")
                Thread {
                    val hh = h.substringBefore(":")
                    val (ok, msg) = Vpn.probe(hh, Proto.PORT_REG)
                    act.runOnUiThread {
                        act.toast(if (ok) "到達できます（$msg）" else msg)
                    }
                }.start()
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 4)))

        box.addView(Ui.ghost(act, "この端末を管理コンソールに切替", Ui.ACC) {
            act.switchMode()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(act, 16)))

        AlertDialog.Builder(act)
            .setTitle("端末設定")
            .setView(Ui.scroll(act, box))
            .setNegativeButton("閉じる", null)
            .setPositiveButton("保存") { _, _ ->
                store.termName = name.text.toString().ifBlank { store.termName }
                store.termFloor = floor.text.toString().toIntOrNull() ?: store.termFloor
                store.termGroup = grp.text.toString().ifBlank { store.termGroup }
                store.building = bldg.text.toString().ifBlank { store.building }
                store.consoleHost = host.text.toString().trim()
                store.log("system", "端末設定を更新")
                render()
            }
            .show()
    }
}
