package com.appathy.housou

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Net {

    fun localIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "0.0.0.0"
    }

    fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) out.add(b)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        if (out.isEmpty()) {
            try {
                out.add(InetAddress.getByName("255.255.255.255"))
            } catch (e: Exception) {
                // ignore
            }
        }
        return out
    }

    /** 端末へ制御コマンドを1往復。失敗時はnull */
    fun ctrl(ip: String, req: JSONObject, timeoutMs: Int = 2500): JSONObject? {
        var sk: Socket? = null
        try {
            sk = Socket()
            sk.connect(InetSocketAddress(ip, Proto.PORT_CTRL), timeoutMs)
            sk.soTimeout = timeoutMs
            val w = OutputStreamWriter(sk.getOutputStream(), "UTF-8")
            w.write(req.toString())
            w.write("\n")
            w.flush()
            val r = BufferedReader(InputStreamReader(sk.getInputStream(), "UTF-8"))
            val line = r.readLine() ?: return null
            return JSONObject(line)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                sk?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun cmd(name: String): JSONObject {
        val o = JSONObject()
        o.put("cmd", name)
        return o
    }
}
