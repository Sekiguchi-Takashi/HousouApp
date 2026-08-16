package com.appathy.housou

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 手首端末（Pebble）連携のデータ層。
 *
 * HOUSOU_WRIST_API v0.3 の契約に基づく。
 *  - 端末登録とトークン管理
 *  - メッセージ／質問のキュー（device_idごとに seq を採番）
 *  - 既読・回答の保持
 *
 * 文字数の切り詰めは親機側で確定させる（時計側の防御に頼らない）。
 */
object Wrist {

    // ---- 文字数上限（API仕様 §6）
    const val LIMIT_JA_BODY = 10
    const val LIMIT_ASCII_BODY = 20
    const val LIMIT_JA_CHOICE = 7
    const val LIMIT_ASCII_CHOICE = 14

    private const val KEEP = 200

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN)

    fun now(): String = ISO.format(Date())
    fun iso(t: Long): String = ISO.format(Date(t))

    fun parseIso(s: String): Long = try {
        ISO.parse(s)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ============================================================ 端末
    class WDev(
        val id: String,
        val name: String,
        val token: String,
        val group: String,
        val lastSeen: Long,
        val displayHint: String,
        /** この端末は管理者スマホ自身が中継する（同一端末内で完結） */
        val self: Boolean = false
    )

    fun devices(store: Store): List<WDev> {
        val a = store.wristDevices()
        val out = ArrayList<WDev>()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            out.add(
                WDev(
                    o.optString("id"), o.optString("name"), o.optString("token"),
                    o.optString("group", "既定"), o.optLong("seen"),
                    o.optString("hint", "ascii"), o.optBoolean("self", false)
                )
            )
        }
        return out
    }

    fun find(store: Store, id: String): WDev? = devices(store).firstOrNull { it.id == id }

    /** 管理者スマホ自身が中継する端末（1台まで） */
    fun selfDevice(store: Store): WDev? = devices(store).firstOrNull { it.self }

    fun auth(store: Store, id: String, token: String): Boolean {
        val d = find(store, id) ?: return false
        return d.token.isNotEmpty() && d.token == token
    }

    /** 新規登録。device_id と token を採番して返す */
    fun register(store: Store, name: String, group: String, self: Boolean = false): WDev {
        val id = "w-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6)
        val token = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val a = store.wristDevices()
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("token", token)
        o.put("group", group)
        o.put("seen", 0L)
        o.put("hint", "ascii")
        o.put("self", self)
        a.put(o)
        store.saveWristDevices(a)
        return WDev(id, name, token, group, 0L, "ascii", self)
    }

    fun reissue(store: Store, id: String): String {
        val token = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        update(store, id) { it.put("token", token) }
        return token
    }

    fun remove(store: Store, id: String) {
        val a = store.wristDevices()
        val out = JSONArray()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optString("id") != id) out.put(o)
        }
        store.saveWristDevices(out)
    }

    fun touch(store: Store, id: String) {
        update(store, id) { it.put("seen", System.currentTimeMillis()) }
    }

    fun rename(store: Store, id: String, name: String, group: String) {
        update(store, id) {
            it.put("name", name)
            it.put("group", group)
        }
    }

    private fun update(store: Store, id: String, fn: (JSONObject) -> Unit) {
        val a = store.wristDevices()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) {
                fn(o)
                store.saveWristDevices(a)
                return
            }
            i++
        }
    }

    fun groups(store: Store): List<String> {
        val set = LinkedHashSet<String>()
        for (d in devices(store)) set.add(d.group)
        return set.toList()
    }

    /** QRペイロード（既存の端末QRとは別スキーム） */
    fun qrPayload(d: WDev, host: String): String =
        "housou://wrist?host=$host&port=${Proto.PORT_WRIST}&dev=${d.id}&token=${d.token}"

    /**
     * 管理者スマホ自身が中継する場合の接続先。
     * 同一端末内のループバックへ繋ぐため、Wi-Fiが切れていても、
     * IPが変わっても設定を直す必要がない。
     */
    fun selfPayload(d: WDev): String =
        "housou://wrist?host=127.0.0.1&port=${Proto.PORT_WRIST}&dev=${d.id}&token=${d.token}"

    // ============================================================ キュー
    /** device_id ごとのキュー。JSON: { "<dev>": [ item, ... ] } */
    private fun queue(store: Store): JSONObject = try {
        JSONObject(store.raw("wrist_q", "{}"))
    } catch (e: Exception) {
        JSONObject()
    }

    private fun saveQueue(store: Store, o: JSONObject) = store.setRaw("wrist_q", o.toString())

    private fun nextSeq(a: JSONArray): Int {
        var max = 0
        var i = 0
        while (i < a.length()) {
            val s = a.getJSONObject(i).optInt("seq")
            if (s > max) max = s
            i++
        }
        return max + 1
    }

    /**
     * 項目を投入する。label_ja / label_ascii が両方揃っていない項目は
     * キューに入れない（時計側での破棄に頼らない）。
     */
    fun enqueue(store: Store, devIds: List<String>, item: JSONObject): Int {
        if (!valid(item)) return 0
        val q = queue(store)
        var n = 0
        for (id in devIds) {
            val a = q.optJSONArray(id) ?: JSONArray()
            val copy = JSONObject(item.toString())
            copy.put("seq", nextSeq(a))
            copy.put("at", System.currentTimeMillis())
            a.put(copy)
            q.put(id, tail(a))
            n++
        }
        saveQueue(store, q)
        return n
    }

    private fun valid(o: JSONObject): Boolean {
        if (o.optString("label_ja").isEmpty()) return false
        if (o.optString("label_ascii").isEmpty()) return false
        val ch = o.optJSONArray("choices") ?: return true
        var i = 0
        while (i < ch.length()) {
            val c = ch.getJSONObject(i)
            i++
            if (c.optString("label_ja").isEmpty()) return false
            if (c.optString("label_ascii").isEmpty()) return false
        }
        return ch.length() in 1..3
    }

    private fun tail(a: JSONArray): JSONArray {
        if (a.length() <= KEEP) return a
        val out = JSONArray()
        var i = a.length() - KEEP
        while (i < a.length()) {
            out.put(a.get(i)); i++
        }
        return out
    }

    /**
     * ポーリング応答。since より大きい項目を返す。
     *
     * 時間経過による失効は「日付が変わるまで」のみ（Pebble側と基準を揃える）。
     * 経過時間での自動消滅は行わない。前日以前に投入された未応答の項目は
     * 配信対象から外し、管理者画面に「失効」として残す。
     */
    fun poll(store: Store, devId: String, since: Int): JSONArray {
        val a = queue(store).optJSONArray(devId) ?: return JSONArray()
        val today = dayOf(System.currentTimeMillis())
        val out = JSONArray()
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optInt("seq") <= since) continue
            if (o.optString("type") != "cancel" && dayOf(o.optLong("at")) != today) continue
            val c = JSONObject(o.toString())
            c.remove("at")
            c.remove("acked_at")
            c.remove("answer")
            c.remove("answered_at")
            c.remove("rejected")
            c.remove("delivered")
            out.put(c)
        }
        // 配信済みとして印を付ける
        markDelivered(store, devId, out)
        return out
    }

    /** ローカル日付（yyyyMMdd） */
    fun dayOf(t: Long): Int {
        if (t == 0L) return 0
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = t
        return c.get(java.util.Calendar.YEAR) * 10000 +
                (c.get(java.util.Calendar.MONTH) + 1) * 100 +
                c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    private fun markDelivered(store: Store, devId: String, sent: JSONArray) {
        if (sent.length() == 0) return
        val seqs = HashSet<Int>()
        var i = 0
        while (i < sent.length()) {
            seqs.add(sent.getJSONObject(i).optInt("seq")); i++
        }
        val q = queue(store)
        val a = q.optJSONArray(devId) ?: return
        var changed = false
        i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (seqs.contains(o.optInt("seq")) && !o.has("delivered")) {
                o.put("delivered", System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) saveQueue(store, q)
    }

    fun items(store: Store, devId: String): List<JSONObject> {
        val a = queue(store).optJSONArray(devId) ?: return emptyList()
        val out = ArrayList<JSONObject>()
        var i = 0
        while (i < a.length()) {
            out.add(a.getJSONObject(i)); i++
        }
        return out.reversed()
    }

    /** 既読。2回目以降は無視して ok を返す（冪等） */
    fun ack(store: Store, devId: String, seq: Int, at: String): Boolean {
        val q = queue(store)
        val a = q.optJSONArray(devId) ?: return false
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optInt("seq") != seq) continue
            if (o.has("acked_at")) return true
            o.put("acked_at", if (at.isEmpty()) now() else at)
            saveQueue(store, q)
            return true
        }
        return false
    }

    /**
     * 回答。最後の回答で上書きする。
     *
     * 配信済みの項目は必ず受け取る（親機の期限判定で expired を返さない）。
     * 取り消したい場合は cancel を明示的に流す。
     */
    fun answer(store: Store, devId: String, qid: String, cid: Int, at: String): Boolean {
        val q = queue(store)
        val a = q.optJSONArray(devId) ?: return false
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optString("qid") != qid) continue
            o.put("answer", cid)
            o.put("answered_at", if (at.isEmpty()) now() else at)
            o.remove("rejected")
            saveQueue(store, q)
            return true
        }
        return false
    }

    /** 未達・未応答の記録（busy / dismissed / day_change） */
    fun reject(store: Store, devId: String, seq: Int, qid: String, reason: String, at: String): Boolean {
        val q = queue(store)
        val a = q.optJSONArray(devId) ?: return false
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            val hit = if (seq > 0) o.optInt("seq") == seq
            else qid.isNotEmpty() && o.optString("qid") == qid
            if (!hit) continue
            if (o.has("answer") || o.has("acked_at")) return true
            o.put("rejected", reason)
            o.put("rejected_at", if (at.isEmpty()) now() else at)
            saveQueue(store, q)
            return true
        }
        return false
    }

    /**
     * 取り消し。cancel 項目をキューに積み、対象を取り消し済みにする。
     * targetSeq が 0 なら、その端末の応答待ち項目を対象にする。
     */
    fun cancel(store: Store, devId: String, targetSeq: Int): Int {
        val q = queue(store)
        val a = q.optJSONArray(devId) ?: return 0
        val target = if (targetSeq > 0) targetSeq else pendingSeq(store, devId)
        if (target <= 0) return 0
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optInt("seq") != target) continue
            o.put("rejected", "cancelled")
            o.put("rejected_at", now())
            break
        }
        val c = JSONObject()
        c.put("type", "cancel")
        c.put("target_seq", target)
        c.put("seq", nextSeq(a))
        c.put("at", System.currentTimeMillis())
        // cancel はラベルを持たないため valid() を通さず直接積む
        a.put(c)
        q.put(devId, tail(a))
        saveQueue(store, q)
        return target
    }

    /**
     * 応答待ちの seq。配信済みかつ ack / answer / reject のいずれも
     * 受けていない項目のうち、最も古いもの。無ければ 0。
     */
    fun pendingSeq(store: Store, devId: String): Int {
        val a = queue(store).optJSONArray(devId) ?: return 0
        val today = dayOf(System.currentTimeMillis())
        var i = 0
        while (i < a.length()) {
            val o = a.getJSONObject(i)
            i++
            if (o.optString("type") == "cancel") continue
            if (!o.has("delivered")) continue
            if (o.has("acked_at") || o.has("answer") || o.has("rejected")) continue
            if (dayOf(o.optLong("at")) != today) continue
            return o.optInt("seq")
        }
        return 0
    }

    fun pendingItem(store: Store, devId: String): JSONObject? {
        val seq = pendingSeq(store, devId)
        if (seq <= 0) return null
        return items(store, devId).firstOrNull { it.optInt("seq") == seq }
    }

    /** 最終ポーリングから60秒以内なら接続中とみなす */
    fun online(d: WDev): Boolean =
        d.lastSeen > 0 && System.currentTimeMillis() - d.lastSeen < 60000

    /** 項目の状態表示 */
    fun stateOf(o: JSONObject): String = when {
        o.optString("type") == "cancel" -> "取り消し"
        o.has("answer") -> "回答: " + choiceLabel(o, o.optInt("answer"))
        o.has("acked_at") -> "既読"
        o.optString("rejected") == "busy" -> "未達（応答待ちのため）"
        o.optString("rejected") == "dismissed" -> "未応答のまま閉じられた"
        o.optString("rejected") == "day_change" -> "日をまたいで失効（未応答）"
        o.optString("rejected") == "cancelled" -> "取り消し済み"
        o.has("delivered") -> "応答待ち"
        else -> "未配信"
    }

    /** 選択肢の日本語ラベル（履歴表示用） */
    fun choiceLabel(o: JSONObject, cid: Int): String {
        val ch = o.optJSONArray("choices") ?: return "$cid"
        var i = 0
        while (i < ch.length()) {
            val c = ch.getJSONObject(i)
            i++
            if (c.optInt("cid") == cid) return c.optString("label_ja")
        }
        return "$cid"
    }

    // ============================================================ 作成補助
    /** 日本語からASCII候補をサジェストする辞書 */
    private val dict = linkedMapOf(
        "外食" to "Eat out", "家で" to "At home", "まだ" to "Not yet",
        "未定" to "Not yet", "後で" to "Later", "保留" to "Later",
        "はい" to "Yes", "いいえ" to "No", "待って" to "Wait",
        "欲しい" to "Want it", "いらない" to "No need",
        "行く" to "Go", "行かない" to "Skip", "任せる" to "You pick",
        "電話して" to "Call me", "戻ってきて" to "Come back",
        "今から向かう" to "On my way", "ありがとう" to "Thanks",
        "昼食" to "Lunch", "夕食" to "Dinner", "朝食" to "Breakfast",
        "了解" to "OK", "急ぎ" to "Urgent", "確認して" to "Check it",
        "終わった" to "Done", "手伝って" to "Help", "休憩" to "Break"
    )

    fun suggest(ja: String): String {
        val t = ja.trim()
        if (t.isEmpty()) return ""
        dict[t]?.let { return it }
        for ((k, v) in dict) {
            if (t.contains(k)) return v
        }
        return ""
    }

    fun cut(s: String, limit: Int): String =
        if (s.length <= limit) s else s.substring(0, limit)

    // ---- プリセット（API仕様 §7）
    class Preset(val id: String, val ja: String, val ascii: String)

    val messages: List<Preset> = listOf(
        Preset("CALL_ME", "電話して", "Call me"),
        Preset("CHECK_LINE", "LINE見て", "Check LINE"),
        Preset("COME_BACK", "戻ってきて", "Come back"),
        Preset("ON_MY_WAY", "今から向かう", "On my way"),
        Preset("THANKS", "ありがとう", "Thanks")
    )

    class Template(
        val key: String,
        val ja: String,
        val ascii: String,
        val choices: List<Pair<String, String>>
    )

    val templates: List<Template> = listOf(
        Template(
            "LUNCH", "昼食どうする?", "Lunch?",
            listOf("外食" to "Eat out", "家で" to "At home", "まだ" to "Not yet")
        ),
        Template(
            "DINNER", "夕食どうする?", "Dinner?",
            listOf("外食" to "Eat out", "家で" to "At home", "まだ" to "Not yet")
        ),
        Template(
            "WANT", "欲しいもの?", "Want it?",
            listOf("欲しい" to "Want it", "いらない" to "No need", "保留" to "Later")
        ),
        Template(
            "YESNO", "", "",
            listOf("はい" to "Yes", "いいえ" to "No", "待って" to "Wait")
        ),
        Template(
            "GO_WHERE", "行きたい所は?", "Go where?",
            listOf("" to "", "" to "", "任せる" to "You pick")
        )
    )

    // ---- 項目の組み立て
    fun buildMessage(ja: String, ascii: String, urgent: Boolean): JSONObject {
        val o = JSONObject()
        o.put("type", "message")
        o.put("id", "MSG")
        o.put("label_ja", cut(ja, LIMIT_JA_BODY))
        o.put("label_ascii", cut(ascii, LIMIT_ASCII_BODY))
        o.put("urgency", if (urgent) "high" else "normal")
        return o
    }

    fun buildQuestion(
        ja: String, ascii: String,
        choices: List<Pair<String, String>>
    ): JSONObject {
        val o = JSONObject()
        o.put("type", "question")
        o.put("qid", "q-" + UUID.randomUUID().toString().substring(0, 4))
        o.put("label_ja", cut(ja, LIMIT_JA_BODY))
        o.put("label_ascii", cut(ascii, LIMIT_ASCII_BODY))
        o.put("urgency", "normal")
        val a = JSONArray()
        var i = 0
        for ((cja, cas) in choices) {
            if (cja.isBlank() || cas.isBlank()) continue
            val c = JSONObject()
            c.put("cid", i)
            c.put("label_ja", cut(cja, LIMIT_JA_CHOICE))
            c.put("label_ascii", cut(cas, LIMIT_ASCII_CHOICE))
            a.put(c)
            i++
            if (i >= 3) break
        }
        o.put("choices", a)
        return o
    }
}
