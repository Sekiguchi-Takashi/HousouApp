package com.appathy.housou

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 端末モードのアプリを再起動後に自動復旧させる */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store(ctx)
        if (store.mode != "terminal" || !store.autoStart) return
        val i = Intent(ctx, TerminalService::class.java)
        i.putExtra("boot", true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
