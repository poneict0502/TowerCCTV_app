package com.pone.towerccctv.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class DeviceType { CAMERA, NVR }

data class Channel(
    val index: Int,
    val label: String,
    val rtspUrl: String,
    val httpBase: String,
    val ptzChannel: Int,
    val username: String,
    val password: String,
    val hasPtz: Boolean,
    val deviceType: DeviceType
)

data class Device(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val type: DeviceType,
    val ip: String,
    val rtspPort: Int = 554,
    val httpPort: Int = 80,
    val username: String,
    val password: String,
    val channelCount: Int = 1,
    val hasPtz: Boolean = true,
    val enabled: Boolean = true
) {
    fun toChannels(): List<Channel> = when (type) {
        DeviceType.CAMERA -> listOf(
            Channel(0, name,
                "rtsp://$username:$password@$ip:$rtspPort/Streaming/Channels/101",
                "http://$ip:$httpPort", 1, username, password, hasPtz, DeviceType.CAMERA)
        )
        DeviceType.NVR -> (1..channelCount).map { ch ->
            Channel(ch - 1, "$name-CH$ch",
                "rtsp://$username:$password@$ip:$rtspPort/Streaming/Channels/${ch * 100 + 1}",
                "http://$ip:$httpPort", ch, username, password, hasPtz, DeviceType.NVR)
        }
    }
}

object DeviceStore {
    private const val PREFS = "tower_cctv"
    private const val KEY = "devices"

    fun save(context: Context, devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("type", d.type.name)
                put("ip", d.ip); put("rtspPort", d.rtspPort); put("httpPort", d.httpPort)
                put("username", d.username); put("password", d.password)
                put("channelCount", d.channelCount); put("hasPtz", d.hasPtz)
                put("enabled", d.enabled)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): MutableList<Device> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(
                    id = o.optString("id", i.toString()),
                    name = o.getString("name"),
                    type = DeviceType.valueOf(o.getString("type")),
                    ip = o.getString("ip"),
                    rtspPort = o.optInt("rtspPort", 554),
                    httpPort = o.optInt("httpPort", 80),
                    username = o.getString("username"),
                    password = o.getString("password"),
                    channelCount = o.optInt("channelCount", 1),
                    hasPtz = o.optBoolean("hasPtz", true),
                    enabled = o.optBoolean("enabled", true)
                )
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }
}
