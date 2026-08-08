package com.appathy.housou

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack

/**
 * 端末の音声出力先を制御する。
 *
 * 既定は auto（Androidの自動選択。Bluetoothが繋がればそちらへ流れる）。
 * 館内スピーカーを有線やBluetoothで繋いだうえで、
 * 「内蔵スピーカーには絶対に出さない」といった固定運用のために明示指定できる。
 */
object Routing {

    const val AUTO = "auto"
    const val SPEAKER = "speaker"
    const val WIRED = "wired"
    const val BT = "bt"
    const val USB = "usb"

    val modes = listOf(AUTO, SPEAKER, WIRED, BT, USB)

    fun label(mode: String): String = when (mode) {
        SPEAKER -> "内蔵スピーカー"
        WIRED -> "有線（イヤホン端子）"
        BT -> "Bluetooth"
        USB -> "USB音声"
        else -> "自動（既定）"
    }

    private fun typesFor(mode: String): List<Int> = when (mode) {
        SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        WIRED -> listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_AUX_LINE
        )
        BT -> listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )
        USB -> listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        else -> emptyList()
    }

    private fun outputs(ctx: Context): Array<AudioDeviceInfo> = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    } catch (e: Exception) {
        emptyArray()
    }

    /** 指定モードに合う出力先。見つからなければ null（＝自動に任せる） */
    fun pick(ctx: Context, mode: String): AudioDeviceInfo? {
        if (mode == AUTO) return null
        val want = typesFor(mode)
        for (d in outputs(ctx)) {
            if (want.contains(d.type)) return d
        }
        return null
    }

    /** 実際に接続されているモードだけを返す */
    fun available(ctx: Context): List<String> {
        val out = ArrayList<String>()
        out.add(AUTO)
        for (m in modes) {
            if (m == AUTO) continue
            if (pick(ctx, m) != null) out.add(m)
        }
        return out
    }

    /** 現在の設定が実際に使えているか */
    fun status(ctx: Context, mode: String): String {
        if (mode == AUTO) return "自動（既定）"
        val d = pick(ctx, mode)
        return if (d == null) "${label(mode)}（未接続 → 自動）" else label(mode)
    }

    /** AudioTrack に出力先を固定する。失敗しても致命ではない */
    fun apply(ctx: Context, t: AudioTrack?, mode: String) {
        if (t == null) return
        try {
            t.preferredDevice = pick(ctx, mode)
        } catch (e: Exception) { }
    }
}
