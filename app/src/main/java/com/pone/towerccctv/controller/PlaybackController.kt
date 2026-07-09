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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class RecordSegment(val start: Date, val end: Date, val rtspUrl: String) {
    val startStr get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(start)
    val endStr   get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(end)
    val durStr: String get() {
        val s = (end.time - start.time) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s/3600, (s%3600)/60, s%60)
        else "%02d:%02d".format(s/60, s%60)
    }
}

class PlaybackController(private val ch: Channel) {

    // Digest 인증 클라이언트
    private val client: OkHttpClient by lazy {
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        val creds = Credentials(ch.username, ch.password)
        val auth = DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(creds))
            .with("basic", BasicAuthenticator(creds))
            .build()
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .authenticator(CachingAuthenticatorDecorator(auth, authCache))
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            .build()
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC") }
    private val rtsp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC") }

    // 채널번호 → trackID (CH1=101, CH2=201, ..., CH16=1601)
    private val trackId get() = ch.ptzChannel * 100 + 1

    suspend fun search(date: Date): List<RecordSegment> = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { time = date }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val from = cal.time
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        val to = cal.time

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<CMSearchDescription>
    <searchID>${UUID.randomUUID()}</searchID>
    <trackList><trackID>$trackId</trackID></trackList>
    <timeSpanList><timeSpan>
        <startTime>${iso.format(from)}</startTime>
        <endTime>${iso.format(to)}</endTime>
    </timeSpan></timeSpanList>
    <maxResults>200</maxResults>
    <searchResultPostion>0</searchResultPostion>
    <metadataList><metadataDescriptor>//recordType.meta.std-cgi.com</metadataDescriptor></metadataList>
</CMSearchDescription>"""

        return@withContext try {
            val req = Request.Builder()
                .url("${ch.httpBase}/ISAPI/ContentMgmt/search")
                .post(xml.toRequestBody("application/xml".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.use { it.body?.string() ?: "" }
            Log.d("Playback", "검색 응답: ${resp.code} trackID=$trackId")
            parseXml(body)
        } catch (e: Exception) {
            Log.e("Playback", "검색 실패: ${e.message}")
            emptyList()
        }
    }

    fun playbackUrl(seg: RecordSegment): String {
        val ip = ch.httpBase.removePrefix("http://").split(":")[0]
        return "rtsp://${com.pone.towerccctv.model.rtspCred(ch.username, ch.password)}@$ip:554/Streaming/tracks/$trackId" +
                "?starttime=${rtsp.format(seg.start)}&endtime=${rtsp.format(seg.end)}"
    }

    private fun parseXml(xml: String): List<RecordSegment> {
        val result = mutableListOf<RecordSegment>()
        Regex("<searchMatchItem>(.*?)</searchMatchItem>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).forEach { m ->
                val s = Regex("<startTime>(.*?)</startTime>").find(m.value)?.groupValues?.get(1) ?: return@forEach
                val e = Regex("<endTime>(.*?)</endTime>").find(m.value)?.groupValues?.get(1) ?: return@forEach
                runCatching {
                    val seg = RecordSegment(iso.parse(s)!!, iso.parse(e)!!, "")
                    result.add(seg.copy(rtspUrl = playbackUrl(seg)))
                }
            }
        Log.d("Playback", "파싱 결과: ${result.size}개 구간")
        return result
    }
}