package com.appathy.housou

import org.json.JSONArray
import org.json.JSONObject

class Dev {
    var id: String = ""
    var name: String = ""
    var floor: Int = 1
    var group: String = "既定"
    var building: String = ""
    var ip: String = ""
    var battery: Int = -1
    var rssi: Int = 0
    var volume: Int = 50
    var rtt: Int = -1
    var playing: Boolean = false
    var talking: Boolean = false
    var micOn: Boolean = true
    var spkOn: Boolean = true
    var ver: String = ""
    var route: String = "auto"
    var routeName: String = ""
    var remote: Boolean = false
    var caption: Boolean = true
    var kiosk: Boolean = false
    var lastSeen: Long = 0L
    var loss: Int = 0

    val online: Boolean
        get() = System.currentTimeMillis() - lastSeen < Proto.OFFLINE_MS

    fun label(): String = "${floor}F ${name}"

    fun fullLabel(): String =
        if (building.isEmpty()) label() else "$building ${floor}F ${name}"

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("floor", floor)
        o.put("group", group); o.put("bldg", building)
        o.put("ip", ip); o.put("volume", volume)
        o.put("ver", ver)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Dev {
            val d = Dev()
            d.id = o.optString("id")
            d.name = o.optString("name", "端末")
            d.floor = o.optInt("floor", 1)
            d.group = o.optString("group", "既定")
            d.building = o.optString("bldg", "")
            d.ip = o.optString("ip", "")
            d.volume = o.optInt("volume", 50)
            d.ver = o.optString("ver", "")
            return d
        }
    }
}

object Registry {

    private val map = LinkedHashMap<String, Dev>()

    @Synchronized
    fun load(store: Store) {
        if (map.isNotEmpty()) return
        val a = store.devices()
        var i = 0
        while (i < a.length()) {
            val d = Dev.fromJson(a.getJSONObject(i))
            if (d.id.isNotEmpty()) map[d.id] = d
            i++
        }
    }

    @Synchronized
    fun save(store: Store) {
        val a = JSONArray()
        for (d in map.values) a.put(d.toJson())
        store.saveDevices(a)
    }

    @Synchronized
    fun all(): List<Dev> = map.values.sortedWith(
        compareBy({ it.floor }, { it.name })
    )

    @Synchronized
    fun online(): List<Dev> = all().filter { it.online }

    @Synchronized
    fun byId(id: String): Dev? = map[id]

    @Synchronized
    fun remove(id: String) {
        map.remove(id)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    /** アナウンス/ステータス応答を取り込む */
    @Synchronized
    fun upsert(o: JSONObject, ip: String): Dev {
        val id = o.optString("id")
        var d = map[id]
        if (d == null) {
            d = Dev()
            d.id = id
            d.group = o.optString("group", "既定")
            map[id] = d
        }
        d.name = o.optString("name", d.name)
        d.floor = o.optInt("floor", d.floor)
        if (o.has("group")) d.group = o.optString("group", d.group)
        if (o.has("bldg")) d.building = o.optString("bldg", d.building)
        d.ip = if (ip.isNotEmpty()) ip else o.optString("ip", d.ip)
        d.battery = o.optInt("batt", d.battery)
        d.rssi = o.optInt("rssi", d.rssi)
        d.volume = o.optInt("vol", d.volume)
        d.playing = o.optBoolean("playing", false)
        d.talking = o.optBoolean("talking", false)
        d.micOn = o.optBoolean("mic", true)
        d.spkOn = o.optBoolean("spk", true)
        d.ver = o.optString("ver", d.ver)
        d.route = o.optString("route", d.route)
        d.routeName = o.optString("route_name", d.routeName)
        if (o.has("caption")) d.caption = o.optBoolean("caption", d.caption)
        if (o.has("kiosk")) d.kiosk = o.optBoolean("kiosk", d.kiosk)
        d.lastSeen = System.currentTimeMillis()
        return d
    }

    @Synchronized
    fun groups(): List<String> {
        val set = LinkedHashSet<String>()
        for (d in scoped()) set.add(d.group)
        return set.toList()
    }

    @Synchronized
    fun floors(): List<Int> = scoped().map { it.floor }.distinct().sorted()

    /** 検出済みの建物名（空欄は除く） */
    @Synchronized
    fun buildings(): List<String> {
        val set = LinkedHashSet<String>()
        for (d in map.values) if (d.building.isNotEmpty()) set.add(d.building)
        return set.toList().sorted()
    }

    /** 現在の建物スコープに含まれる端末 */
    @Synchronized
    fun scoped(): List<Dev> {
        val sc = Targeting.scope
        if (sc.isEmpty()) return all()
        return all().filter { it.building == sc }
    }
}
