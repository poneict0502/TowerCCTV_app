package com.pone.towerccctv.controller

import android.util.Log
import com.pone.towerccctv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC") }
    private val rtsp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC") }

    suspend fun search(date: Date): List<RecordSegment> = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { time = date }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val from = cal.time
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        val to = cal.time

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<CMSearchDescription>
    <searchID>${UUID.randomUUID()}</searchID>
    <trackList><trackID>101</trackID></trackList>
    <timeSpanList><timeSpan>
        <startTime>${iso.format(from)}</startTime>
        <endTime>${iso.format(to)}</endTime>
    </timeSpan></timeSpanList>
    <maxResults>100</maxResults>
    <searchResultPostion>0</searchResultPostion>
    <metadataList><metadataDescriptor>//recordType.meta.std-cgi.com</metadataDescriptor></metadataList>
</CMSearchDescription>"""

        return@withContext try {
            val req = Request.Builder()
                .url("${ch.httpBase}/ISAPI/ContentMgmt/search")
                .header("Authorization", Credentials.basic(ch.username, ch.password))
                .post(xml.toRequestBody("application/xml".toMediaType())).build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
            parseXml(body)
        } catch (e: Exception) { Log.e("Playback", e.message ?: ""); emptyList() }
    }

    fun playbackUrl(seg: RecordSegment): String {
        val ip = ch.httpBase.removePrefix("http://").split(":")[0]
        return "rtsp://${ch.username}:${ch.password}@$ip:554/Streaming/tracks/101" +
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
        return result
    }
}
