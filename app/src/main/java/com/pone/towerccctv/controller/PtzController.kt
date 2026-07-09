package com.pone.towerccctv.controller

import android.util.Log
import com.pone.towerccctv.model.CameraBrand
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.trustSelfSignedCam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.cos
import kotlin.math.sin

class PtzController(private val ch: Channel) {

    private val client = com.pone.towerccctv.camHttpClient(ch.username, ch.password, connectSec = 3, readSec = 5)
    private val scope = CoroutineScope(Dispatchers.IO)

    // 한화(Wisenet)는 CGI 를 HTTPS(443)로 강제 → 한화만 https 로 호출
    private val hanwhaBase: String =
        "https://" + ch.httpBase.substringAfter("//").substringBefore(":").substringBefore("/")

    // ── 공통 API (브랜드별 분기) ──────────────────
    fun move(pan: Int, tilt: Int) = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvMove(pan, tilt)
        CameraBrand.DAHUA     -> dahuaMove(pan, tilt)
        CameraBrand.IDIS      -> idisMove(pan, tilt)
        CameraBrand.HANWHA    -> hanwhaMove(pan, tilt)
    }

    fun stop() = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvStop()
        CameraBrand.DAHUA     -> dahuaStop()
        CameraBrand.IDIS      -> idisStop()
        CameraBrand.HANWHA    -> hanwhaStop()
    }

    fun zoom(zoomIn: Boolean) = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvZoom(zoomIn)
        CameraBrand.DAHUA     -> dahuaZoom(zoomIn)
        CameraBrand.IDIS      -> idisZoom(zoomIn)
        CameraBrand.HANWHA    -> hanwhaZoom(zoomIn)
    }

    fun zoomStop() = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvZoomStop()
        CameraBrand.DAHUA     -> dahuaZoomStop()
        CameraBrand.IDIS      -> idisZoomStop()
        CameraBrand.HANWHA    -> hanwhaZoomStop()
    }

    fun gotoPreset(id: Int) = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvGotoPreset(id)
        CameraBrand.DAHUA     -> dahuaGotoPreset(id)
        CameraBrand.IDIS      -> idisGotoPreset(id)
        CameraBrand.HANWHA    -> hanwhaGotoPreset(id)
    }

    fun setPreset(id: Int) = when (ch.brand) {
        CameraBrand.HIKVISION -> hikvSetPreset(id)
        CameraBrand.DAHUA     -> dahuaSetPreset(id)
        CameraBrand.IDIS      -> idisSetPreset(id)
        CameraBrand.HANWHA    -> hanwhaSetPreset(id)
    }

    fun moveByAngle(angle: Double, magnitude: Float) {
        if (magnitude < 0.08f) { stop(); return }
        val speed = (magnitude * 70).toInt().coerceIn(8, 70)
        move((cos(angle) * speed).toInt(), (sin(angle) * speed).toInt())
    }

    // ── Hikvision ISAPI ────────────────────────
    private val hikvPtzUrl get() = "${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/continuous"

    private fun hikvMove(pan: Int, tilt: Int) = hikvPut(hikvPtzUrl,
        "<PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>0</zoom></PTZData>")

    private fun hikvStop() = hikvPut(hikvPtzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>")

    private fun hikvZoom(zoomIn: Boolean) = hikvPut(hikvPtzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>${if (zoomIn) 50 else -50}</zoom></PTZData>")

    private fun hikvZoomStop() = hikvPut(hikvPtzUrl,
        "<PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>")

    private fun hikvGotoPreset(id: Int) = scope.launch {
        runCatching {
            val req = Request.Builder()
                .url("${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/presets/$id/goto")
                .put("".toRequestBody()).build()
            client.newCall(req).execute().use { Log.d("PTZ", "HIK Preset $id=${it.code}") }
        }.onFailure { Log.e("PTZ", "HIK Preset 실패", it) }
    }

    private fun hikvSetPreset(id: Int) = hikvPut(
        "${ch.httpBase}/ISAPI/PTZCtrl/channels/${ch.ptzChannel}/presets/$id",
        "<PTZPreset><id>$id</id><presetName>P$id</presetName></PTZPreset>")

    private fun hikvPut(url: String, xml: String) = scope.launch {
        runCatching {
            val req = Request.Builder().url(url)
                .put(xml.toRequestBody("application/xml".toMediaType())).build()
            client.newCall(req).execute().use { Log.d("PTZ", "HIK PUT $url=${it.code}") }
        }.onFailure { Log.e("PTZ", "HIK 실패", it) }
    }

    // ── Dahua CGI ──────────────────────────────
    private val DAHUA_SPEED = 4

    private fun dahuaMove(pan: Int, tilt: Int) {
        val code = when {
            pan > 0 && tilt > 0 -> "RightUp"
            pan > 0 && tilt < 0 -> "RightDown"
            pan < 0 && tilt > 0 -> "LeftUp"
            pan < 0 && tilt < 0 -> "LeftDown"
            pan > 0             -> "Right"
            pan < 0             -> "Left"
            tilt > 0            -> "Up"
            tilt < 0            -> "Down"
            else                -> return
        }
        dahuaGet("ptz.cgi?action=start&channel=1&code=$code&arg1=$DAHUA_SPEED&arg2=$DAHUA_SPEED&arg3=0")
    }

    private fun dahuaStop() {
        listOf("Left","Right","Up","Down","LeftUp","LeftDown","RightUp","RightDown").forEach { dir ->
            dahuaGet("ptz.cgi?action=stop&channel=1&code=$dir&arg1=0&arg2=0&arg3=0")
        }
    }

    private fun dahuaZoom(zoomIn: Boolean) =
        dahuaGet("ptz.cgi?action=start&channel=1&code=${if (zoomIn) "ZoomTele" else "ZoomWide"}&arg1=0&arg2=0&arg3=0")

    private fun dahuaZoomStop() {
        dahuaGet("ptz.cgi?action=stop&channel=1&code=ZoomTele&arg1=0&arg2=0&arg3=0")
        dahuaGet("ptz.cgi?action=stop&channel=1&code=ZoomWide&arg1=0&arg2=0&arg3=0")
    }

    private fun dahuaGotoPreset(id: Int) =
        dahuaGet("ptz.cgi?action=start&channel=1&code=GotoPreset&arg1=0&arg2=$id&arg3=0")

    private fun dahuaSetPreset(id: Int) =
        dahuaGet("ptz.cgi?action=start&channel=1&code=SetPreset&arg1=0&arg2=$id&arg3=0")

    private fun dahuaGet(cgi: String) = scope.launch {
        runCatching {
            val req = Request.Builder().url("${ch.httpBase}/cgi-bin/$cgi").get().build()
            client.newCall(req).execute().use { Log.d("PTZ", "DAHUA GET $cgi=${it.code}") }
        }.onFailure { Log.e("PTZ", "DAHUA 실패: $cgi", it) }
    }

    // ── IDIS CGI (기기 테스트 후 보정 필요) ─────
    private fun idisMove(pan: Int, tilt: Int) {
        val h = if (pan > 0) 1 else if (pan < 0) -1 else 0
        val v = if (tilt > 0) 1 else if (tilt < 0) -1 else 0
        idisGet("move.cgi?pan=$h&tilt=$v&speed=4")
    }
    private fun idisStop() = idisGet("move.cgi?pan=0&tilt=0&speed=0")
    private fun idisZoom(zoomIn: Boolean) = idisGet("zoom.cgi?zoom=${if (zoomIn) "tele" else "wide"}&speed=4")
    private fun idisZoomStop() = idisGet("zoom.cgi?zoom=stop&speed=0")
    private fun idisGotoPreset(id: Int) = idisGet("preset.cgi?action=goto&preset=$id")
    private fun idisSetPreset(id: Int) = idisGet("preset.cgi?action=set&preset=$id")
    private fun idisGet(cgi: String) = scope.launch {
        runCatching {
            val req = Request.Builder().url("${ch.httpBase}/cgi-bin/$cgi").get().build()
            client.newCall(req).execute().use { Log.d("PTZ", "IDIS GET $cgi=${it.code}") }
        }.onFailure { Log.e("PTZ", "IDIS 실패: $cgi", it) }
    }

    // ── 한화 SUNAPI (블록캠: 줌/프리셋 위주) ─────
    private val HANWHA_PT = 0.5
    private val HANWHA_CH = 0
    private fun hanwhaMove(pan: Int, tilt: Int) {
        val p = Integer.signum(pan) * HANWHA_PT
        val t = Integer.signum(tilt) * HANWHA_PT
        hanwhaPtz("msubmenu=continuous&action=control&Channel=$HANWHA_CH&Pan=$p&Tilt=$t&Zoom=0")
    }
    private fun hanwhaStop() =
        hanwhaPtz("msubmenu=continuous&action=control&Channel=$HANWHA_CH&Pan=0&Tilt=0&Zoom=0")
    private fun hanwhaZoom(zoomIn: Boolean) =
        hanwhaPtz("msubmenu=continuous&action=control&Channel=$HANWHA_CH&Pan=0&Tilt=0&Zoom=${if (zoomIn) 1 else -1}")
    private fun hanwhaZoomStop() =
        hanwhaPtz("msubmenu=continuous&action=control&Channel=$HANWHA_CH&Pan=0&Tilt=0&Zoom=0")
    private fun hanwhaGotoPreset(id: Int) =
        hanwhaPtz("msubmenu=preset&action=control&Channel=$HANWHA_CH&Preset=$id")
    private fun hanwhaSetPreset(id: Int) =
        hanwhaConfig("msubmenu=preset&action=add&Channel=$HANWHA_CH&Preset=$id&Name=$id")
    private fun hanwhaPtz(query: String) = scope.launch {
        runCatching {
            val req = Request.Builder().url("$hanwhaBase/stw-cgi/ptzcontrol.cgi?$query").get().build()
            client.newCall(req).execute().use { Log.d("PTZ", "HANWHA GET $query=${it.code}") }
        }.onFailure { Log.e("PTZ", "HANWHA 실패: $query", it) }
    }
    private fun hanwhaConfig(query: String) = scope.launch {
        runCatching {
            val req = Request.Builder().url("$hanwhaBase/stw-cgi/ptzconfig.cgi?$query").get().build()
            client.newCall(req).execute().use { Log.d("PTZ", "HANWHA CFG $query=${it.code}") }
        }.onFailure { Log.e("PTZ", "HANWHA CFG 실패: $query", it) }
    }

    // 카메라에 설정된 프리셋 ID 목록 (스와이프 순환용). 콜백은 IO 스레드.
    fun fetchPresets(onResult: (List<Int>) -> Unit) {
        when (ch.brand) {
            CameraBrand.HIKVISION -> scope.launch {
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
            CameraBrand.HANWHA -> scope.launch {
                val list = runCatching {
                    val req = Request.Builder()
                        .url("$hanwhaBase/stw-cgi/ptzconfig.cgi?msubmenu=preset&action=view&Channel=$HANWHA_CH")
                        .get().build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        Regex("Preset\\.(\\d+)\\.Name").findAll(body)
                            .map { it.groupValues[1].toInt() }.distinct().sorted().toList()
                    }
                }.getOrDefault(emptyList())
                onResult(if (list.isNotEmpty()) list else (1..9).toList())
            }
            CameraBrand.DAHUA -> scope.launch {
                val list = runCatching {
                    val req = Request.Builder()
                        .url("${ch.httpBase}/cgi-bin/ptz.cgi?action=getPresets&channel=1")
                        .get().build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        Regex("presets\\[\\d+\\]\\.Index=(\\d+)").findAll(body)
                            .map { it.groupValues[1].toInt() }.filter { it in 1..9 }.distinct().sorted().toList()
                    }
                }.getOrDefault(emptyList())
                onResult(if (list.isNotEmpty()) list else (1..9).toList())
            }
            else -> onResult((1..9).toList())   // IDIS: 1~9 기본
        }
    }
}
