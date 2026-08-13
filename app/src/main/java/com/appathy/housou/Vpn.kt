package com.appathy.housou

import android.content.Context
import android.content.Intent
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * VPN連携支援。
 *
 * VPN自体はアプリに内蔵しない（WireGuard等のスタックを抱えると依存が大きく、
 * 鍵管理の責任も負うことになるため）。代わりに、
 *  - 端末に入っているVPNアプリの検出と起動
 *  - VPNインターフェースのIPアドレス検出
 *  - コンソールアドレスの設定支援と疎通テスト
 * を提供し、Tailscale / WireGuard 等と組み合わせて遠隔運用できるようにする。
 */
object Vpn {

    class App(val pkg: String, val name: String)

    /** 対応を確認済みのVPNアプリ（検出順） */
    val known: List<App> = listOf(
        App("com.tailscale.ipn", "Tailscale"),
        App("com.wireguard.android", "WireGuard"),
        App("net.openvpn.openvpn", "OpenVPN Connect"),
        App("de.blinkt.openvpn", "OpenVPN for Android")
    )

    /** インストール済みのVPNアプリ */
    fun installed(ctx: Context): List<App> {
        val out = ArrayList<App>()
        for (a in known) {
            try {
                ctx.packageManager.getPackageInfo(a.pkg, 0)
                out.add(a)
            } catch (e: Exception) { }
        }
        return out
    }

    fun launch(ctx: Context, app: App): Boolean {
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * VPNインターフェースのIPv4アドレス。
     * インターフェース名（tun*/wg*/tailscale*/utun*）と、
     * TailscaleのCGNAT帯（100.64.0.0/10）の両方で判定する。
     */
    fun vpnIp(): String? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val isVpnIf = name.startsWith("tun") || name.startsWith("wg") ||
                        name.startsWith("tailscale") || name.startsWith("utun") ||
                        name.startsWith("ppp")
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    val ip = a.hostAddress ?: continue
                    if (isVpnIf || isTailscaleRange(ip)) return ip
                }
            }
        } catch (e: Exception) { }
        return null
    }

    /** 100.64.0.0/10 (Tailscale が使う CGNAT 帯) */
    fun isTailscaleRange(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    /** VPNが有効か（インターフェースの存在で判定） */
    fun active(): Boolean = vpnIp() != null

    /**
     * 疎通テスト。指定ホストの制御ポートへTCP接続し、pingを1往復させる。
     * 結果は (成功, 所要ms または エラー説明)
     */
    fun probe(host: String, port: Int = Proto.PORT_CTRL): Pair<Boolean, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val res = Net.ctrl(host, Net.cmd("ping"), 4000, port)
            if (res != null) {
                Pair(true, "${System.currentTimeMillis() - t0}ms")
            } else {
                Pair(false, "応答なし（相手のアプリが起動しているか確認）")
            }
        } catch (e: Exception) {
            Pair(false, "接続不可（アドレスとVPNの状態を確認）")
        }
    }
}
