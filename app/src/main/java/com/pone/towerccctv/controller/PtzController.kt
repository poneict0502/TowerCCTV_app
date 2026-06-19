package com.pone.towerccctv.controller

import android.util.Log
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.pone.towerccctv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class PtzController(private val ch: Channel) {

    private val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
    private val credentials = Credentials(ch.username, ch.password)
    private val authenticator = DispatchingAuthenticator.Builder()
        .with("digest", DigestAuthenticator(credentials))
        .with("basic", BasicAuthenticator(credentials))
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .authenticator(CachingAuthenticatorDecorator(authenticator, authCache))
        .addInterceptor(AuthenticationCacheInterceptor(authCache))
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val ptzUrl get() = "${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/continuous"

    fun move(pan: Int, tilt: Int) = put(ptzUrl,
        "<PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>0</zoom></PTZData>")

    fun stop() = put(ptzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>")

    fun zoom(zoomIn: Boolean) = put(ptzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>${if (zoomIn) 50 else -50}</zoom></PTZData>")

    fun zoomStop() = put(ptzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>")

    fun gotoPreset(id: Int) {
        scope.launch {
            runCatching {
                val req = Request.Builder()
                    .url("${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/presets/$id/goto")
                    .put("".toRequestBody()).build()
                client.newCall(req).execute().use { Log.d("PTZ", "Preset $id=${it.code}") }
            }.onFailure { Log.e("PTZ", "Preset failed", it) }
        }
    }

    fun setPreset(id: Int) = put(
        "${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/presets/$id",
        "<PTZPreset><id>$id</id><presetName>P$id</presetName></PTZPreset>")

    // 카메라에 실제 설정된 프리셋 ID 목록 조회 (스와이프 순환용).
    // Hikvision ISAPI: GET .../presets → <PTZPresetList> 안의 enabled≠false 인 <id> 만 수집.
    // 실패/없음이면 1~9 폴백. onResult 는 IO 스레드에서 호출됨(@Volatile 필드 대입만 권장).
    fun fetchPresets(onResult: (List<Int>) -> Unit) {
        scope.launch {
            val ids = runCatching {
                val req = Request.Builder()
                    .url("${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/presets")
                    .get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    Regex("<PTZPreset>(.*?)</PTZPreset>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(body)
                        .mapNotNull { m ->
                            val block = m.groupValues[1]
                            if (block.contains("<enabled>false</enabled>")) return@mapNotNull null
                            Regex("<id>(\\d+)</id>").find(block)?.groupValues?.get(1)?.toIntOrNull()
                        }
                        .distinct().sorted().toList()
                }
            }.getOrDefault(emptyList())
            onResult(if (ids.isNotEmpty()) ids else (1..9).toList())
        }
    }

    fun moveByAngle(angle: Double, magnitude: Float) {
        if (magnitude < 0.08f) { stop(); return }
        val speed = (magnitude * 70).toInt().coerceIn(8, 70)
        move((cos(angle) * speed).toInt(), (sin(angle) * speed).toInt())
    }

    private fun put(url: String, xml: String) {
        scope.launch {
            runCatching {
                val req = Request.Builder().url(url)
                    .put(xml.toRequestBody("application/xml".toMediaType())).build()
                client.newCall(req).execute().use {
                    Log.d("PTZ", "PUT $url => ${it.code}")
                }
            }.onFailure { Log.e("PTZ", "PUT failed", it) }
        }
    }
}
