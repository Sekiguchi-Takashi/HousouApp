package com.appathy.housou

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    lateinit var store: Store
    private lateinit var root: FrameLayout
    private var console: ConsoleUi? = null
    private var terminal: TerminalUi? = null
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Registry.load(store)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        askPermissions()
        showSplash()
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        need.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = need.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            try {
                requestPermissions(missing.toTypedArray(), 77)
            } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------- スプラッシュ
    private fun showSplash() {
        val l = Ui.col(this, 24)
        l.gravity = Gravity.CENTER
        l.setBackgroundColor(Ui.BG)

        val logo = Ui.tv(this, "📢", 64f)
        logo.gravity = Gravity.CENTER
        l.addView(logo)

        val t = Ui.tv(this, "放送室", 34f, Ui.FG, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, Ui.dp(this, 14), 0, 0)
        l.addView(t)

        val s = Ui.tv(this, "館内放送コントロールシステム  v" + Proto.APP_VER, 12f, Ui.SUB)
        s.gravity = Gravity.CENTER
        s.setPadding(0, Ui.dp(this, 8), 0, 0)
        l.addView(s)

        val b = Ui.tv(this, "Appathy  —  Less Motivation, More Automation", 10f, Ui.ACC)
        b.gravity = Gravity.CENTER
        b.setPadding(0, Ui.dp(this, 26), 0, 0)
        l.addView(b)

        root.removeAllViews()
        root.addView(l)
        h.postDelayed({ route() }, 1100)
    }

    fun route() {
        when (store.mode) {
            "console" -> showLogin()
            "terminal" -> showTerminal()
            else -> showModeSelect()
        }
    }

    // ------------------------------------------------------- モード選択
    private fun showModeSelect() {
        val l = Ui.col(this, 22)
        l.setBackgroundColor(Ui.BG)
        l.gravity = Gravity.CENTER_VERTICAL

        l.addView(Ui.tv(this, "この端末の役割を選択", 22f, Ui.FG, true))
        l.addView(
            Ui.tv(this, "同じWi-Fiに接続してください。コンソールは1台、フロア端末は各階に設置します。", 13f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 8))
        )

        val c1 = Ui.card(this)
        c1.addView(Ui.tv(this, "🎛  管理コンソール", 19f, Ui.ACC, true))
        c1.addView(
            Ui.tv(this, "放送・通話・端末管理・予約放送を行う親機", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 6))
        )
        c1.addView(
            Ui.btn(this, "コンソールとして使う") {
                store.mode = "console"
                store.log("system", "管理コンソールとして初期化")
                showConsole()
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 14))
        )
        l.addView(c1, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 22)))

        val c2 = Ui.card(this)
        c2.addView(Ui.tv(this, "🔊  フロア端末", 19f, Ui.CYAN, true))
        c2.addView(
            Ui.tv(this, "各フロアに置いて放送を鳴らす子機", 12f, Ui.SUB),
            Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 6))
        )
        c2.addView(
            Ui.btn(this, "フロア端末として使う", Ui.CYAN) {
                store.mode = "terminal"
                askTerminalInfo()
            }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 14))
        )
        l.addView(c2, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 14)))

        root.removeAllViews()
        root.addView(Ui.scroll(this, l))
    }

    private fun askTerminalInfo() {
        val box = Ui.col(this, 18)
        val name = Ui.edit(this, "端末名（例: 事務室）", store.termName)
        val floor = Ui.edit(this, "フロア番号", store.termFloor.toString(), true)
        val grp = Ui.edit(this, "放送グループ", store.termGroup)
        box.addView(Ui.tv(this, "端末名", 12f, Ui.SUB))
        box.addView(name, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 4)))
        box.addView(Ui.tv(this, "フロア", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 12)))
        box.addView(floor, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 4)))
        box.addView(Ui.tv(this, "グループ", 12f, Ui.SUB), Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 12)))
        box.addView(grp, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 4)))

        AlertDialog.Builder(this)
            .setTitle("フロア端末の登録")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("登録") { _, _ ->
                store.termName = name.text.toString().ifBlank { "放送端末" }
                store.termFloor = floor.text.toString().toIntOrNull() ?: 1
                store.termGroup = grp.text.toString().ifBlank { "既定" }
                showTerminal()
            }
            .show()
    }

    // ------------------------------------------------------- ログイン
    private fun showLogin() {
        val l = Ui.col(this, 24)
        l.gravity = Gravity.CENTER
        l.setBackgroundColor(Ui.BG)

        val t = Ui.tv(this, "🔐 管理者認証", 24f, Ui.FG, true)
        t.gravity = Gravity.CENTER
        l.addView(t)
        val s = Ui.tv(this, store.building, 13f, Ui.SUB)
        s.gravity = Gravity.CENTER
        l.addView(s, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 6)))

        val pin = Ui.edit(this, "PIN（初期値 0000）")
        pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pin.gravity = Gravity.CENTER
        pin.setTypeface(Typeface.MONOSPACE)
        l.addView(pin, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 26)))

        l.addView(Ui.btn(this, "ログイン") {
            if (pin.text.toString() == store.pin) {
                store.log("system", "管理者ログイン")
                showConsole()
            } else {
                toast("PINが違います")
                store.log("security", "ログイン失敗")
            }
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 16)))

        l.addView(Ui.ghost(this, "役割を選び直す", Ui.SUB) {
            store.mode = ""
            showModeSelect()
        }, Ui.lp(Ui.MP, Ui.WC, Ui.dp(this, 10)))

        root.removeAllViews()
        root.addView(Ui.scroll(this, l))
    }

    // ------------------------------------------------------- 画面切替
    fun showConsole() {
        terminal = null
        val c = ConsoleUi(this, store)
        console = c
        c.attach(root)
    }

    fun showTerminal() {
        console = null
        val t = TerminalUi(this, store)
        terminal = t
        t.attach(root)
    }

    fun switchMode() {
        stopService(Intent(this, ConsoleService::class.java))
        stopService(Intent(this, TerminalService::class.java))
        store.mode = ""
        console = null
        terminal = null
        showModeSelect()
    }

    // ------------------------------------------------------- 外部Activity連携
    var onScan: ((String) -> Unit)? = null
    var onPickAudio: ((android.net.Uri) -> Unit)? = null

    fun scanQr(cb: (String) -> Unit) {
        onScan = cb
        try {
            startActivityForResult(Intent(this, ScanActivity::class.java), 91)
        } catch (e: Exception) {
            toast("カメラを開けません")
        }
    }

    fun pickAudio(cb: (android.net.Uri) -> Unit) {
        onPickAudio = cb
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "audio/*"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(i, 92)
        } catch (e: Exception) {
            toast("ファイル選択を開けません")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 91) {
            val t = data.getStringExtra("text")
            if (!t.isNullOrEmpty()) onScan?.invoke(t)
        } else if (requestCode == 92) {
            val u = data.data
            if (u != null) onPickAudio?.invoke(u)
        }
    }

    fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    fun dialog(title: String, body: LinearLayout, okLabel: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("閉じる", null)
            .setPositiveButton(okLabel) { _, _ -> ok() }
            .show()
    }

    override fun onDestroy() {
        console?.detach()
        terminal?.detach()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = console
        if (c != null && c.onBack()) return
        moveTaskToBack(true)
    }
}
