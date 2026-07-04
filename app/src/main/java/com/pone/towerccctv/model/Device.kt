package com.pone.towerccctv.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class DeviceType { CAMERA, NVR }
enum class CameraBrand { HIKVISION, DAHUA, IDIS, HANWHA }

data class Channel(
    val index: Int,
    val label: String,
    val rtspUrl: String,        // 메인스트림 (고화질) — 단독/전체화면용
    val httpBase: String,
    val ptzChannel: Int,
    val username: String,
    val password: String,
    val hasPtz: Boolean,
    val deviceType: DeviceType,
    // ↓ 신규 필드는 끝에 기본값으로 추가 (기존 9개 인자 호출 호환)
    val brand: CameraBrand = CameraBrand.HIKVISION,
    val subUrl: String = ""     // 서브스트림 (저화질) — 4분할용
)

data class Device(
    // ★ UUID 로 고유 ID 보장 (동시 생성 시 충돌 방지)
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: DeviceType,
    val brand: CameraBrand = CameraBrand.HIKVISION,
    val ip: String,
    val rtspPort: Int = 554,
    val httpPort: Int = 80,
    val username: String,
    val password: String,
    val channelCount: Int = 1,
    val hasPtz: Boolean = true,
    val enabled: Boolean = true
) {
    fun toChannels(): List<Channel> {
        // ★ 아이디/비번 앞뒤 공백·줄바꿈 제거 (복붙 %0A 로 인한 인증 실패 방지)
        val u = username.trim()
        val p = password.trim()
        val encUser = java.net.URLEncoder.encode(u, "UTF-8")
        val encPass = java.net.URLEncoder.encode(p, "UTF-8")
        val base = "rtsp://$encUser:$encPass@$ip:$rtspPort"
        val http = "http://$ip:$httpPort"
        return when (type) {
            DeviceType.CAMERA -> {
                val (main, sub) = streamUrls(base, 1)
                listOf(Channel(0, name, main, http, 1, u, p, hasPtz, DeviceType.CAMERA, brand, sub))
            }
            DeviceType.NVR -> (1..channelCount).map { ch ->
                val (main, sub) = streamUrls(base, ch)
                Channel(ch - 1, "$name-CH$ch", main, http, ch, u, p, hasPtz, DeviceType.NVR, brand, sub)
            }
        }
    }

    // 브랜드별 메인/서브 RTSP 경로
    private fun streamUrls(base: String, chn: Int): Pair<String, String> = when (brand) {
        CameraBrand.HIKVISION -> Pair("$base/Streaming/Channels/${chn * 100 + 1}",
                                      "$base/Streaming/Channels/${chn * 100 + 2}")
        CameraBrand.DAHUA     -> Pair("$base/cam/realmonitor?channel=$chn&subtype=0",
                                      "$base/cam/realmonitor?channel=$chn&subtype=1")
        CameraBrand.IDIS      -> Pair("$base/trackID=1", "$base/trackID=2")
        // 한화 Wisenet: profile2=H.264 메인. profile4 없는 기기 있음(실측) → 녹화도 profile2(메인)로 안전하게
        CameraBrand.HANWHA    -> Pair("$base/profile2/media.smp", "$base/profile2/media.smp")
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
                put("brand", d.brand.name)
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
            // ★ 손상된(중복) ID 자동 복구
            val seenIds = mutableSetOf<String>()
            var fixedAny = false
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                var loadedId = o.optString("id", "")
                if (loadedId.isBlank() || loadedId in seenIds) {
                    loadedId = java.util.UUID.randomUUID().toString()
                    fixedAny = true
                }
                seenIds.add(loadedId)
                Device(
                    id = loadedId,
                    name = o.getString("name"),
                    type = DeviceType.valueOf(o.getString("type")),
                    brand = CameraBrand.valueOf(o.optString("brand", "HIKVISION")),
                    ip = o.getString("ip").trim(),
                    rtspPort = o.optInt("rtspPort", 554),
                    httpPort = o.optInt("httpPort", 80),
                    // ★ 복붙 시 끼어드는 앞뒤 공백·줄바꿈(%0A) 제거 — 시간동기/최적화/PTZ 등 모든 인증 경로 공통 정상화
                    //   (영상 경로는 toChannels()에서 이미 trim. 여기서 소스 자체를 정리해 raw 사용처까지 커버)
                    username = o.getString("username").trim(),
                    password = o.getString("password").trim(),
                    channelCount = o.optInt("channelCount", 1),
                    hasPtz = o.optBoolean("hasPtz", true),
                    enabled = o.optBoolean("enabled", true)
                )
            }.toMutableList()
            // 복구된 경우 즉시 저장 (이후부터 정상화)
            if (fixedAny) save(context, list)
            list
        } catch (e: Exception) { mutableListOf() }
    }
}
