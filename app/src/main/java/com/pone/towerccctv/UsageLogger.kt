package com.pone.towerccctv

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 실사용/장비상태 로그 — 사양 역최적화용.
 *  - 이벤트를 JSONL 일자별 파일에 **비동기**로 누적 (라이브 영향 0)
 *  - 추출: /sdcard/Android/data/<pkg>/files/usage/usage-YYYYMMDD.jsonl
 *  - on-device 요약: summary()
 */
object UsageLogger {
    private var dir: File? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private var handler: Handler? = null
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.applicationContext.getExternalFilesDir(null), "usage").apply { mkdirs() }
        val ht = HandlerThread("usage-log").apply { start() }
        handler = Handler(ht.looper)
        scheduleFlush()
        log("app", "ev" to "start")
    }

    fun log(ev: String, vararg pairs: Pair<String, Any?>) {
        if (dir == null) return
        val o = JSONObject()
        try {
            o.put("t", tsFmt.format(Date()))
            o.put("ev", ev)
            for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        } catch (_: Exception) { return }
        queue.add(o.toString())
        if (queue.size > 200) handler?.post { flush() }
    }

    private fun scheduleFlush() {
        handler?.postDelayed({ flush(); scheduleFlush() }, 20_000L)
    }

    @Synchronized
    fun flush() {
        val d = dir ?: return
        if (queue.isEmpty()) return
        try {
            val f = File(d, "usage-${dayFmt.format(Date())}.jsonl")
            val sb = StringBuilder()
            while (true) { val line = queue.poll() ?: break; sb.append(line).append('\n') }
            if (sb.isNotEmpty()) f.appendText(sb.toString())
        } catch (_: Exception) {}
    }

    fun usageDirPath(): String = dir?.absolutePath ?: "(미초기화)"

    fun summary(days: Int = 7): String {
        flush()
        val d = dir ?: return "로그 없음"
        val files = (0 until days).mapNotNull { off ->
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, -off) }
            File(d, "usage-${dayFmt.format(cal.time)}.jsonl").takeIf { it.exists() }
        }
        if (files.isEmpty()) return "아직 기록된 사용 로그가 없습니다.\n\n파일 위치:\n${d.absolutePath}"

        var zin = 0; var zout = 0; var zms = 0L
        val presetHist = HashMap<Int, Int>()
        var moveN = 0
        var viewOpen = 0; var viewSec = 0L
        var playN = 0
        var sleepN = 0; var wakeN = 0; var disconnN = 0
        var minPct = 101
        val faultHist = HashMap<String, Int>(); val faultSec = HashMap<String, Long>()
        var wifiBad = 0

        files.forEach { f ->
            f.forEachLine { line ->
                val o = try { JSONObject(line) } catch (_: Exception) { return@forEachLine }
                when (o.optString("ev")) {
                    "zoom" -> { if (o.optString("dir") == "in") zin++ else zout++; zms += o.optLong("ms") }
                    "preset" -> { val id = o.optInt("id"); presetHist[id] = (presetHist[id] ?: 0) + 1 }
                    "ptz_move" -> moveN++
                    "view_open" -> viewOpen++
                    "view_close" -> viewSec += o.optLong("sec")
                    "playback" -> if (o.optString("k") == "play") playN++
                    "sleep" -> sleepN++
                    "wake" -> wakeN++
                    "battery", "snapshot" -> { val p = o.optInt("pct", 101); if (p in 0..100) minPct = minOf(minPct, p) }
                    "power" -> if (o.optString("msg").contains("분리")) disconnN++
                    "cam_down" -> { val c = o.optString("cam"); faultHist[c] = (faultHist[c] ?: 0) + 1 }
                    "cam_fault" -> { val c = o.optString("cam"); faultSec[c] = (faultSec[c] ?: 0) + o.optLong("sec") }
                    "wifi" -> if (o.optString("k") != "ok") wifiBad++
                }
            }
        }
        fun mmss(s: Long) = "${s / 60}분 ${s % 60}초"
        val presetStr = presetHist.entries.sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key}번 ${it.value}" }.ifBlank { "없음" }
        val faultStr = if (faultHist.isEmpty()) "없음" else faultHist.entries
            .joinToString(", ") { "${it.key} ${it.value}회(${(faultSec[it.key] ?: 0) / 60}분)" }

        return buildString {
            append("📈 사용 로그 요약 (최근 ${files.size}일)\n\n")
            append("● 카메라 조작\n")
            append("   줌: ${zin + zout}회 (인 $zin · 아웃 $zout), 누적 ${mmss(zms / 1000)}\n")
            append("   프리셋: ${presetHist.values.sum()}회  ($presetStr)\n")
            append("   팬/틸트: ${moveN}회\n\n")
            append("● 시청 / 재생\n")
            append("   단독화면: ${viewOpen}회, 누적 시청 ${mmss(viewSec)}\n")
            append("   녹화 재생: ${playN}회${if (playN == 0) "   ← 한 번도 안 씀" else ""}\n\n")
            append("● 전원 / 배터리\n")
            append("   절전 ${sleepN}회 · 복귀 ${wakeN}회 · 전원분리 ${disconnN}회\n")
            append("   최저 배터리: ${if (minPct <= 100) "$minPct%" else "기록없음"}\n\n")
            append("● 네트워크 / 카메라\n")
            append("   WiFi 끊김·오AP: ${wifiBad}회\n")
            append("   카메라 끊김: $faultStr\n\n")
            append("파일: ${d.absolutePath}\n(USB/MTP 로 추출해 PC 분석)")
        }
    }
}
