package com.appathy.housou

import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * 手首端末連携のHTTPサーバ（ポート45306）。
 *
 * HOUSOU_WRIST_API v0.3:
 *   GET  /wrist/poll?since=<seq>
 *   POST /wrist/ack     {"seq":N,"acked_at":"..."}
 *   POST /wrist/answer  {"qid":"...","cid":N,"answered_at":"..."}
 *
 * 認証は X-Housou-Device / X-Housou-Token ヘッダ。
 * 平文HTTPのためLAN内／VPN内での利用を前提とする（外部トリガーと同じ割り切り）。
 */
class WristServer(private val store: Store, private val onChange: () -> Unit) {

    @Volatile private var alive = false
    private var server: ServerSocket? = null

    fun start() {
        if (alive) return
        alive = true
        Thread {
            try {
                val ss = ServerSocket(Proto.PORT_WRIST)
                server = ss
                while (alive) {
                    val sock = ss.accept()
                    Thread { handle(sock) }.start()
                }
            } catch (e: Exception) { }
        }.start()
    }

    fun stop() {
        alive = false
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    // ------------------------------------------------------------ 受信
    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val ins = sock.getInputStream()
            val br = ins.bufferedReader()

            val request = br.readLine() ?: return
            val parts = request.split(" ")
            if (parts.size < 2) {
                respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // ヘッダ
            var devId = ""
            var token = ""
            var contentLength = 0
            while (true) {
                val line = br.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                val k = line.substring(0, i).trim().lowercase()
                val v = line.substring(i + 1).trim()
                when (k) {
                    "x-housou-device" -> devId = v
                    "x-housou-token" -> token = v
                    "content-length" -> contentLength = v.toIntOrNull() ?: 0
                }
            }

            if (!Wrist.auth(store, devId, token)) {
                store.log("security", "手首端末の認証失敗: ${if (devId.isEmpty()) "(no id)" else devId}")
                respond(sock, 401, "{\"ok\":false,\"reason\":\"auth\"}")
                return
            }
            Wrist.touch(store, devId)

            // ボディ
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = br.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }

            val p = path.substringBefore("?")
            when {
                method == "GET" && p == "/wrist/poll" -> doPoll(sock, devId, path)
                method == "POST" && p == "/wrist/ack" -> doAck(sock, devId, body)
                method == "POST" && p == "/wrist/answer" -> doAnswer(sock, devId, body)
                method == "POST" && p == "/wrist/reject" -> doReject(sock, devId, body)
                else -> respond(sock, 404, "{\"ok\":false,\"reason\":\"not_found\"}")
            }
        } catch (e: Exception) {
            try { respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}") } catch (x: Exception) { }
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ 各処理
    private fun doPoll(sock: Socket, devId: String, path: String) {
        var since = 0
        val q = path.substringAfter("?", "")
        for (kv in q.split("&")) {
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i) == "since") {
                since = kv.substring(i + 1).toIntOrNull() ?: 0
            }
        }
        val d = Wrist.find(store, devId)
        val o = JSONObject()
        o.put("server_time", Wrist.now())
        o.put("poll_interval_sec", store.wristPollSec)
        o.put("display_hint", d?.displayHint ?: "ascii")
        o.put("items", Wrist.poll(store, devId, since))
        respond(sock, 200, o.toString())
    }

    private fun doAck(sock: Socket, devId: String, body: String) {
        try {
            val j = JSONObject(body)
            val seq = j.optInt("seq", -1)
            if (seq < 0) {
                respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
                return
            }
            val ok = Wrist.ack(store, devId, seq, j.optString("acked_at"))
            if (ok) {
                store.log("wrist", "既読: ${labelOf(devId)} #$seq")
                onChange()
            }
            respond(sock, 200, "{\"ok\":true}")
        } catch (e: Exception) {
            respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
        }
    }

    private fun doAnswer(sock: Socket, devId: String, body: String) {
        try {
            val j = JSONObject(body)
            val qid = j.optString("qid")
            val cid = j.optInt("cid", -1)
            if (qid.isEmpty() || cid < 0) {
                respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
                return
            }
            val ok = Wrist.answer(store, devId, qid, cid, j.optString("answered_at"))
            if (!ok) {
                respond(sock, 200, "{\"ok\":false,\"reason\":\"not_found\"}")
                return
            }
            val item = Wrist.items(store, devId).firstOrNull { it.optString("qid") == qid }
            val ans = if (item != null) Wrist.choiceLabel(item, cid) else "$cid"
            val qtext = item?.optString("label_ja") ?: qid
            store.log("wrist", "回答: ${labelOf(devId)}「$qtext」→ $ans")
            onChange()
            respond(sock, 200, "{\"ok\":true}")
        } catch (e: Exception) {
            respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
        }
    }

    /**
     * 未達・未応答の通知。
     *  busy       … 先行1件が未応答のため受け取らなかった
     *  dismissed  … 表示されたが応答されずに閉じられた
     *  day_change … 日付が変わって失効した
     * 親機は記録するだけで、時計側の制御には介入しない。
     */
    private fun doReject(sock: Socket, devId: String, body: String) {
        try {
            val j = JSONObject(body)
            val seq = j.optInt("seq", 0)
            val qid = j.optString("qid")
            val reason = j.optString("reason", "dismissed")
            if (seq <= 0 && qid.isEmpty()) {
                respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
                return
            }
            val ok = Wrist.reject(store, devId, seq, qid, reason, j.optString("rejected_at"))
            if (ok) {
                val text = when (reason) {
                    "busy" -> "未達（応答待ちのため）"
                    "day_change" -> "日をまたいで失効"
                    else -> "未応答のまま閉じられた"
                }
                store.log("wrist", "$text: ${labelOf(devId)} #$seq")
                onChange()
            }
            respond(sock, 200, "{\"ok\":true}")
        } catch (e: Exception) {
            respond(sock, 400, "{\"ok\":false,\"reason\":\"bad_request\"}")
        }
    }

    private fun labelOf(devId: String): String =
        Wrist.find(store, devId)?.name ?: devId

    private fun respond(sock: Socket, code: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(code).append(
                when (code) {
                    200 -> " OK"
                    400 -> " Bad Request"
                    401 -> " Unauthorized"
                    404 -> " Not Found"
                    else -> " Error"
                }
            ).append("\r\n")
            head.append("Content-Type: application/json; charset=utf-8\r\n")
            head.append("Content-Length: ").append(bytes.size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) { }
    }
}
