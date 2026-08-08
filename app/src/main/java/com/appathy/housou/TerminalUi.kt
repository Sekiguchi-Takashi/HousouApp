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
        render()
        tick()
    }

    fun detach() {
        attached = false
        TerminalService.onUpdate = null
    }

    private fun tick() {
        if (!attached) return
        render()
        h.postDelayed({ tick() }, if (TerminalService.alertOn) 700 else 2000)
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
