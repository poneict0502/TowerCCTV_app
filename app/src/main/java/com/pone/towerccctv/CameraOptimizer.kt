package com.pone.towerccctv

import android.util.Log
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.pone.towerccctv.model.CameraBrand
import com.pone.towerccctv.model.Device
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 카메라 자동 최적화: 코덱 H.264 + GOP 30 + 시간동기화 (+한화 DIS).
 *  - Hikvision: ISAPI (검증됨)
 *  - 한화/다후아: best-effort + 로깅('CamOpt')
 */
object CameraOptimizer {

    data class Result(val name: String, val ip: String, val lines: List<String>)

    private fun client(user: String, pass: String): OkHttpClient {
        val cache = ConcurrentHashMap<String, CachingAuthenticator>()
        val creds = Credentials(user, pass)
        val auth = DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(creds))
            .with("basic", BasicAuthenticator(creds)).build()
        return OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .authenticator(CachingAuthenticatorDecorator(auth, cache))
            .addInterceptor(AuthenticationCacheInterceptor(cache))
            .trustSelfSignedCam()   // 한화 등 HTTPS 강제 카메라의 자체서명 인증서 신뢰
            .build()
    }

    fun optimize(dev: Device, powerSave: Boolean = false): Result {
        val c = client(dev.username.trim(), dev.password.trim())
        val ip = dev.ip
        val cal = Calendar.getInstance()
        val dateStr = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val timeStr = "%02d:%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
        val lines = ArrayList<String>()
        try {
            when (dev.brand) {
                CameraBrand.HIKVISION -> hik(c, ip, dateStr, timeStr, lines, powerSave)
                CameraBrand.HANWHA    -> hanwha(c, ip, dateStr, timeStr, lines, powerSave)
                CameraBrand.DAHUA     -> dahua(c, ip, dateStr, timeStr, lines)
                CameraBrand.IDIS      -> lines.add("ℹ️ IDIS 자동최적화 미지원")
            }
        } catch (e: Exception) { lines.add("❌ 통신 실패: ${e.message}") }
        return Result(dev.name, ip, lines)
    }

    //   절전 프로파일: 15fps·VBR·저비트레이트·긴 GOP → 인코딩+WiFi 송신량↓ = 카메라 배터리↑
    private fun hik(c: OkHttpClient, ip: String, dateStr: String, timeStr: String, out: MutableList<String>, powerSave: Boolean) {
        var codecOk = true; var gopOk = true; var psOk = true; var any = false
        val gov = if (powerSave) "50" else "30"
        for (ch in listOf(101, 102)) {
            val xml = httpGet(c, "http://$ip/ISAPI/Streaming/channels/$ch") ?: continue
            any = true
            var n = xml.replace(Regex("<videoCodecType>.*?</videoCodecType>"), "<videoCodecType>H.264</videoCodecType>")
            n = n.replace(Regex("<GovLength>\\d+</GovLength>"), "<GovLength>$gov</GovLength>")
            if (ch == 102) {   // ★서브스트림은 반드시 16:9(640x360). 4:3이면 4분할에서 세로 넘침이 위 셀에 번짐(실측 2026-07-09)
                n = n.replace(Regex("<videoResolutionWidth>\\d+</videoResolutionWidth>"), "<videoResolutionWidth>640</videoResolutionWidth>")
                n = n.replace(Regex("<videoResolutionHeight>\\d+</videoResolutionHeight>"), "<videoResolutionHeight>360</videoResolutionHeight>")
            }
            if (powerSave) {
                val fps = if (ch == 101) "1500" else "1200"
                n = n.replace(Regex("<maxFrameRate>\\d+</maxFrameRate>"), "<maxFrameRate>$fps</maxFrameRate>")
                n = n.replace(Regex("<videoQualityControlType>.*?</videoQualityControlType>"), "<videoQualityControlType>VBR</videoQualityControlType>")
                n = n.replace(Regex("<vbrUpperCap>\\d+</vbrUpperCap>"), "<vbrUpperCap>${if (ch == 101) "2048" else "512"}</vbrUpperCap>")
            }
            if (!httpPut(c, "http://$ip/ISAPI/Streaming/channels/$ch", n)) { codecOk = false; gopOk = false; psOk = false }
        }
        if (!any) { out.add("❌ 코덱/GOP — 응답 없음"); return }
        out.add(if (codecOk) "✅ 코덱 H.264 (메인·서브)" else "❌ 코덱")
        out.add(if (gopOk) "✅ GOP $gov" else "❌ GOP")
        if (powerSave) out.add(if (psOk) "✅ 절전: 15fps·VBR·저비트레이트" else "❌ 절전 프로파일")
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Time><timeMode>manual</timeMode>" +
                "<localTime>${dateStr}T${timeStr}+09:00</localTime><timeZone>CST-9:00:00</timeZone></Time>"
        out.add(if (httpPut(c, "http://$ip/ISAPI/System/time", xml)) "✅ 시간 동기화" else "❌ 시간")
    }

    private fun hanwha(c: OkHttpClient, ip: String, dateStr: String, timeStr: String, out: MutableList<String>, powerSave: Boolean) {
        // ※ 실기기(XNZ-L6320A) attributes.cgi 로 파라미터 확정(2026-07-01). 한화는 HTTPS 강제.
        val base = "https://$ip/stw-cgi"
        val gov = if (powerSave) "50" else "30"   // 코덱은 이미 H.264 → GOP만. 한화 GOP=H264.GOVLength
        val gop = httpOkLog(c, "$base/media.cgi?msubmenu=videoprofile&action=update&Channel=0&Profile=2&H264.GOVLength=$gov", "HANWHA gop")
        out.add(if (gop) "✅ 코덱 H.264·GOP$gov" else "⚠ GOP — 웹 확인 필요")
        if (powerSave) {
            val ps = httpOkLog(c, "$base/media.cgi?msubmenu=videoprofile&action=update&Channel=0&Profile=2&FrameRate=15", "HANWHA powersave")
            out.add(if (ps) "✅ 절전(15fps) 시도됨" else "⚠ 절전 — 웹에서 fps·WiseStream 설정")
        }
        // DIS(흔들림보정): imageenhancements(복수) + DISEnable=True
        val dis = httpOkLog(c, "$base/image.cgi?msubmenu=imageenhancements&action=set&Channel=0&DISEnable=True", "HANWHA DIS")
        out.add(if (dis) "✅ DIS(흔들림보정) ON" else "⚠ DIS — 웹 확인 필요")
        // 시간: Year/Month/Day/Hour/Minute/Second + SyncType=Manual
        val d = dateStr.split("-"); val tm = timeStr.split(":")
        val timeOk = if (d.size == 3 && tm.size == 3) runCatching {
            val q = "SyncType=Manual&Year=${d[0]}&Month=${d[1].toInt()}&Day=${d[2].toInt()}" +
                    "&Hour=${tm[0].toInt()}&Minute=${tm[1].toInt()}&Second=${tm[2].toInt()}"
            httpOkLog(c, "$base/system.cgi?msubmenu=date&action=set&$q", "HANWHA time")
        }.getOrDefault(false) else false
        out.add(if (timeOk) "✅ 시간 동기화" else "⚠ 시간 — 확인 필요")
    }

    private fun dahua(c: OkHttpClient, ip: String, dateStr: String, timeStr: String, out: MutableList<String>) {
        val enc = "http://$ip/cgi-bin/configManager.cgi?action=setConfig" +
                "&Encode[0].MainFormat[0].Video.Compression=H.264&Encode[0].MainFormat[0].Video.GOP=30" +
                "&Encode[0].ExtraFormat[0].Video.Compression=H.264"
        out.add(if (httpOkLog(c, enc, "DAHUA codec")) "✅ 코덱/GOP 시도됨" else "⚠ 코덱/GOP — 웹 확인 필요")
        val t = java.net.URLEncoder.encode("$dateStr $timeStr", "UTF-8")
        out.add(if (httpOkLog(c, "http://$ip/cgi-bin/global.cgi?action=setCurrentTime&time=$t", "DAHUA time")) "✅ 시간 동기화" else "⚠ 시간 — 확인 필요")
    }

    private fun httpGet(c: OkHttpClient, url: String): String? = try {
        c.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.code in 200..299) r.body?.string() else null
        }
    } catch (e: Exception) { Log.e("CamOpt", "GET fail $url: ${e.message}"); null }

    private fun httpPut(c: OkHttpClient, url: String, xml: String): Boolean = try {
        c.newCall(Request.Builder().url(url).put(xml.toRequestBody("application/xml".toMediaType())).build())
            .execute().use { r -> val b = r.body?.string() ?: ""; Log.d("CamOpt", "PUT $url -> ${r.code} ${b.take(80)}"); r.code in 200..299 }
    } catch (e: Exception) { Log.e("CamOpt", "PUT fail $url: ${e.message}"); false }

    private fun httpOkLog(c: OkHttpClient, url: String, tag: String): Boolean = try {
        c.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val b = (r.body?.string() ?: "").replace("\n", " ")
            Log.d("CamOpt", "$tag -> ${r.code} ${b.take(100)}")
            r.code in 200..299 && !b.contains("NG", true) && !b.contains("Error Code", true) && !b.contains("error", true)
        }
    } catch (e: Exception) { Log.e("CamOpt", "$tag fail: ${e.message}"); false }
}
