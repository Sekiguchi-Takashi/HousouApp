package com.appathy.housou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 管理者通知。端末の状態が悪化／回復したタイミングだけを通知する
 * （同じ状態が続く間は再通知しない）。
 */
object Alerts {

    private const val CH_ID = "housou_alert"
    private val last = HashMap<String, String>()
    @Volatile private var ready = false

    private fun channel(ctx: Context) {
        if (ready) return
        ready = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, "端末アラート", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "放送端末のオフライン・電池切れなどを通知します"
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) { }
    }

    /** ポーリングごとに呼ぶ */
    fun evaluate(ctx: Context, store: Store, list: List<Dev>) {
        channel(ctx)
        for (d in list) {
            val state = stateOf(d)
            val prev = last[d.id]
            if (prev == state) continue
            last[d.id] = state
            if (prev == null) continue           // 初回は通知しない
            if (state == "ok") {
                notify(ctx, d, "${d.label()} が復旧しました", "通信が回復し、放送を受信できます。", false)
                store.log("system", "端末が復旧: ${d.label()}")
            } else {
                val (t, b) = describe(d, state)
                notify(ctx, d, t, b, true)
                store.log("alert", "$t / $b")
            }
        }
    }

    private fun stateOf(d: Dev): String {
        if (!d.online) return "offline"
        if (d.battery in 0..14) return "battery"
        if (!d.spkOn) return "muted"
        if (d.rssi != 0 && d.rssi <= -82) return "weak"
        return "ok"
    }

    private fun describe(d: Dev, state: String): Pair<String, String> = when (state) {
        "offline" -> Pair("${d.label()} がオフライン", "自動再接続を試行中です。電源とWi-Fiを確認してください。")
        "battery" -> Pair("${d.label()} の電池残量が僅少", "残り${d.battery}%。給電してください。")
        "muted" -> Pair("${d.label()} のスピーカーが無効", "放送が鳴りません。端末詳細から有効化してください。")
        else -> Pair("${d.label()} の電波が弱い", "RSSI ${d.rssi}dBm。音切れが発生する可能性があります。")
    }

    private fun notify(ctx: Context, d: Dev, title: String, body: String, warn: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(ctx, CH_ID)
            b.setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_noti)
                .setAutoCancel(true)
                .setContentIntent(pi)
            if (warn) b.setColor(0xFFFF5A5A.toInt())
            nm.notify(2000 + (d.id.hashCode() and 0xFFF), b.build())
        } catch (t: Throwable) { }
    }

    fun reset() = last.clear()
}
