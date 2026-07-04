package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.pone.towerccctv.PowerMonitor
import com.pone.towerccctv.UsageLogger
import com.pone.towerccctv.BatteryProfile
import com.pone.towerccctv.controller.CameraPowerController
import com.pone.towerccctv.trustSelfSignedCam
import com.pone.towerccctv.Watchdog
import android.speech.tts.TextToSpeech
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pone.towerccctv.R
import com.pone.towerccctv.databinding.ActivityMainBinding
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType
import com.pone.towerccctv.data.AlertDatabase
import okhttp3.MediaType.Companion.toMediaType
import com.pone.towerccctv.data.AlertRecord
import com.pone.towerccctv.ocr.OcrEngine
import com.pone.towerccctv.ocr.OcrSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var channels = listOf<Channel>()
    private var libVLC: LibVLC? = null
    private val players = arrayOfNulls<MediaPlayer>(4)

    private lateinit var vlcList:   List<VLCVideoLayout>
    private lateinit var dotList:   List<View>
    private lateinit var lblList:   List<TextView>
    private lateinit var ipList:    List<TextView>
    private lateinit var touchList: List<View>

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var alertDb: AlertDatabase
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = java.util.Calendar.getInstance()
            val h = "%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY))
            val m = "%02d".format(now.get(java.util.Calendar.MINUTE))
            if (::b.isInitialized && !isSleeping()) { b.tvClock.text = "$h:$m"; updateSignalBadge(); updateBitrates() }
            handler.postDelayed(this, 1000L)
        }
    }
    private val reconnectJobs = arrayOfNulls<Runnable>(4)
    private var tts: TextToSpeech? = null

    // ── 전원/배터리 + 절전(시동 OFF)·복귀(시동 ON) ──
    private var powerMonitor: PowerMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val powerBannerToken = Any()
    private fun isSleeping() = powerMonitor?.sleeping == true
    // 사용 로그 전이 추적
    private val camDown = BooleanArray(4)
    private val camDownSince = LongArray(4)
    private var lastBatteryLogged = -1
    private var wifiBadLogged = false
    private var lastSnapshotMs = 0L
    private val sleepOverlay: TextView by lazy {
        TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.parseColor("#666666"))
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            text = "🌙  절전 모드\n\n시동(전원)을 켜면 자동으로 깨어납니다"
            visibility = View.GONE
            isClickable = true
        }
    }

    private var ocrEngine: OcrEngine? = null
    private var ocrChannelIndex = -1
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL = 10_000L
    private var lastWeight: Float? = null
    private var lastWeightAlert = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 시동 ON 복귀 시 화면을 켤 수 있도록 (절전 후 자동 깨우기)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setFullscreen()
        addContentView(sleepOverlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        startPowerMonitor()
        alertDb = AlertDatabase(this)

        vlcList   = listOf(b.vlc0, b.vlc1, b.vlc2, b.vlc3)
        dotList   = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        lblList   = listOf(b.lbl0, b.lbl1, b.lbl2, b.lbl3)
        ipList    = listOf(b.ip0,  b.ip1,  b.ip2,  b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        libVLC = com.pone.towerccctv.VlcProvider.get(this)   // 전역 공유 (재생성 비용 제거)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(java.util.Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(java.util.Locale.getDefault())
                }
                tts?.setSpeechRate(0.85f)
            }
        }
        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "⚡  기본 장치 자동 등록")
            popup.menu.add(0, 3, 2, "📡  OCR / 계측 설정")
            popup.menu.add(0, 7, 3, "📸  OCR 캡처(30초 저장)")
            popup.menu.add(0, 5, 4, "🕐  카메라 시간 동기화")
            popup.menu.add(0, 9, 5, "🛠  카메라 자동 최적화")
            popup.menu.add(0, 12, 5, "🔋  카메라 전원 (무선)")
            popup.menu.add(0, 6, 6, "📊  경보 이력 조회")
            popup.menu.add(0, 11, 7, "📈  사용 로그 보기")
            popup.menu.add(0, 10, 8, "🌙  지금 절전 (시동 OFF 모의)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                    2 -> showAutoRegisterDialog()
                    3 -> {
                        ocrEngine?.stopLoop()
                        OcrSettingsDialog(this) {
                            ocrEngine?.release(); ocrEngine = null
                            if (OcrSettings.isOcrEnabled(this)) {
                                startOcrLoop()
                            } else {
                                // OCR OFF: 카메라 OSD 비우고 화면 OSD 숨김
                                sendEmptyPosOsd()
                                b.osdOverlay.visibility = View.GONE
                                b.tvOcrBadge.visibility = View.GONE
                                lastWeight = null
                                lastWeightAlert = false
                                OcrSettings.clearLatest(this)
                            }
                        }.show()
                    }
                    5 -> syncCameraTime()
                    9 -> optimizeCameras()
                    12 -> showCameraPower()
                    11 -> showUsageLog()
                    10 -> powerMonitor?.forceSleep()
                    6 -> startActivity(Intent(this, AlertHistoryActivity::class.java))
                    7 -> {
                        if (ocrEngine != null && OcrSettings.isOcrEnabled(this)) {
                            ocrEngine?.startDebugCapture()
                            Toast.makeText(this,
                                "OCR 캡처 30초 저장 시작 (다운로드/AIVION/ocr)",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "먼저 OCR를 켜주세요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            popup.show()
        }
        b.btnExit.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("종료")
                .setMessage("앱을 종료하시겠습니까?")
                .setPositiveButton("종료") { _, _ ->
                    if (BatteryProfile.isEnabled(this)) {
                        CameraPowerController.setOutput(false)   // 배터리 OFF 신호 전송
                        AlertDialog.Builder(this)
                            .setTitle("카메라 배터리 OFF")
                            .setMessage("카메라 배터리 OFF 신호를 보냈습니다.\n앱을 종료합니다.")
                            .setCancelable(false)
                            .setPositiveButton("확인") { _, _ -> CameraPowerController.stop(); finishAffinity() }
                            .show()
                    } else finishAffinity()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        b.tvSignal.setOnClickListener { showNetworkDialog() }
        b.faultBanner.setOnClickListener { showNetworkDialog() }
    }

    // ── 무선 신호 표시 / 네트워크 진단 ──
    private fun currentRssi(): Int? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = wm.connectionInfo
        if (info == null || info.rssi == -127 || info.rssi == Int.MIN_VALUE) null else info.rssi
    } catch (e: Exception) { null }

    private fun rssiPct(rssi: Int) = ((rssi + 90).coerceIn(0, 50) * 2).coerceIn(0, 100)

    private fun updateSignalBadge() {
        val rssi = currentRssi()
        if (rssi == null) {
            b.tvSignal.text = "📶 --"; b.tvSignal.setTextColor(Color.parseColor("#888888")); return
        }
        val color = when { rssi >= -60 -> "#4CAF50"; rssi >= -72 -> "#FFC107"; else -> "#FF5252" }
        b.tvSignal.text = "📶 ${rssiPct(rssi)}%"
        b.tvSignal.setTextColor(Color.parseColor(color))
    }

    private fun netHeaderText(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = try { wm.connectionInfo } catch (e: Exception) { null }
        val rssi = info?.rssi
        val ssidRaw = info?.ssid?.replace("\"", "") ?: ""
        val ssid = if (ssidRaw.isBlank() || ssidRaw.contains("unknown", true)) "연결됨" else ssidRaw
        val q = if (rssi == null) "-" else when { rssi >= -60 -> "좋음"; rssi >= -72 -> "보통"; else -> "약함" }
        val pct = if (rssi != null) rssiPct(rssi) else 0
        val good = ssidPrefs().getString("goodSsid", "") ?: ""
        val warn = if (ssidRaw.isNotEmpty() && good.isNotEmpty() && ssidRaw != good) "  ⚠ 전용 AP 아님!" else ""
        return "AP: $ssid$warn\n신호: ${rssi ?: "-"} dBm  (${pct}%, $q)\n링크속도: ${info?.linkSpeed ?: "-"} Mbps"
    }

    private fun optimizeCameras() {
        val devices = DeviceStore.load(this).filter { it.enabled }
        if (devices.isEmpty()) { Toast.makeText(this, "등록된 카메라가 없습니다", Toast.LENGTH_SHORT).show(); return }
        val hasHanwha = devices.any { it.brand == com.pone.towerccctv.model.CameraBrand.HANWHA }
        AlertDialog.Builder(this).setTitle("🛠 카메라 자동 최적화")
            .setMessage("${devices.size}대에 적용:\n\n[표준] 코덱 H.264·GOP30·시간동기화" +
                    (if (hasHanwha) "·DIS" else "") + "  (화질 우선)\n\n" +
                    "[절전] 표준 + 15fps·VBR·저비트레이트·긴GOP\n  → 카메라 배터리 절약 (화질↓)")
            .setPositiveButton("표준") { _, _ -> runOptimize(devices, false) }
            .setNeutralButton("절전") { _, _ -> runOptimize(devices, true) }
            .setNegativeButton("취소", null).show()
    }

    private fun runOptimize(devices: List<com.pone.towerccctv.model.Device>, powerSave: Boolean) {
        val tv = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(40, 28, 40, 16)
            text = "적용 중... (${devices.size}대)\n잠시만 기다려 주세요"
        }
        AlertDialog.Builder(this).setTitle("🛠 카메라 최적화 결과 ${if (powerSave) "(절전)" else "(표준)"}").setView(tv).setPositiveButton("닫기", null).show()
        Thread {
            val results = devices.map { com.pone.towerccctv.CameraOptimizer.optimize(it, powerSave) }
            val sb = StringBuilder()
            results.forEach { r ->
                sb.append("● ${r.name} (${r.ip})\n")
                r.lines.forEach { sb.append("     $it\n") }
                sb.append("\n")
            }
            runOnUiThread { tv.text = sb.toString().trimEnd() }
        }.start()
    }

    private fun showNetworkDialog() {
        val tv = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(48, 32, 48, 16)
            text = netHeaderText() + "\n\n카메라 응답:\n  측정 중..."
        }
        AlertDialog.Builder(this)
            .setTitle("📶 네트워크 상태")
            .setView(tv)
            .setPositiveButton("닫기", null)
            .show()
        val ipRate = HashMap<String, Float>()
        val ipFault = HashMap<String, String>()
        val nowNs = System.nanoTime()
        for (i in 0 until 4) {
            val ip = slotIp(i); if (ip.isEmpty()) continue
            if (rateMbps[i] >= 0f) ipRate[ip] = rateMbps[i]
            if (channels.getOrNull(i) != null && players[i] != null && (nowNs - lastHealthyNs[i]) / 1_000_000 > DOWN_ALARM_MS)
                ipFault[ip] = faultReason[i] ?: "연결 안 됨"
        }
        Thread {
            val devices = DeviceStore.load(this).filter { it.enabled }
            val sb = StringBuilder()
            if (devices.isEmpty()) sb.append("  등록된 카메라 없음")
            devices.forEach { d ->
                val fault = ipFault[d.ip]
                if (fault != null) {
                    sb.append("• ${d.name} (${d.ip}): ⚠ $fault\n")
                } else {
                    val ms = tcpPing(d.ip, d.rtspPort, 1500)
                    val st = when { ms < 0 -> "❌ 끊김"; ms < 80 -> "🟢 양호"; ms < 250 -> "🟡 보통"; else -> "🟠 지연" }
                    val r = ipRate[d.ip]
                    val rateStr = if (r != null) "  ·  %.1fMbps".format(r) else ""
                    sb.append("• ${d.name} (${d.ip}): ${if (ms < 0) "응답없음" else "${ms}ms"}  $st$rateStr\n")
                }
            }
            runOnUiThread { tv.text = netHeaderText() + "\n\n카메라 응답:\n" + sb.toString() }
        }.start()
    }

    private fun tcpPing(ip: String, port: Int, timeoutMs: Int): Long = try {
        val t0 = System.nanoTime()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }
        (System.nanoTime() - t0) / 1_000_000
    } catch (e: Exception) { -1 }

    // ── 카메라별 실시간 비트레이트(전송률) ──
    // libVLC 가 이미 집계하는 누적 수신바이트만 1초마다 수동으로 읽어 계산 → 디코딩/렌더 경로 무영향(저지연 유지)
    private val rateLastBytes = LongArray(4) { -1L }
    private val rateLastNs    = LongArray(4) { 0L }
    private val rateMbps      = FloatArray(4) { -1f }
    private val lastDecoded   = IntArray(4) { -1 }     // 디코딩된 프레임 수(생존판정) — 네트워크죽음·디코더프리즈 모두 감지
    private val healthyStreak = IntArray(4) { 0 }      // 연속 프레임증가 폴 수(스톨 후 버퍼배출 1폴 blip 무시용)

    // 고장 진단
    private val lastHealthyNs = LongArray(4) { 0L }
    private val faultReason   = arrayOfNulls<String>(4)
    private val lastProbeNs   = LongArray(4) { 0L }
    private val DOWN_MILD_MS  = 10_000L   // 10초: 일시 지연 → 조용히 "재연결 중"(경보 X)
    private val DOWN_ALARM_MS = 25_000L   // 25초 지속 → 진짜 장애 경보(빨강). 회복성 지연은 여기까지 안 감

    private fun slotIp(i: Int): String {
        val hb = channels.getOrNull(i)?.httpBase ?: return ""
        return hb.substringAfter("//").substringBefore(":").substringBefore("/")
    }

    private fun readInputBytes(p: MediaPlayer): Long = try {
        val m = p.media
        val s = m?.stats
        // RTSP(live555)는 access_demux → readBytes 가 0, 실제 데이터는 demuxReadBytes 에 집계됨
        val demux = s?.demuxReadBytes ?: 0
        val input = s?.readBytes ?: 0
        m?.release()
        val v = if (demux > 0) demux else input
        if (v <= 0) -1L else (v.toLong() and 0xFFFFFFFFL)    // 부호없는 32비트(래핑 대비)
    } catch (e: Exception) { -1L }

    private fun updateBitrates() {
        val nowNs = System.nanoTime()
        var downCount = 0
        val downLabels = StringBuilder()
        for (i in 0 until 4) {
            val ch = channels.getOrNull(i)
            val p = players[i]
            if (ch == null || p == null) { rateMbps[i] = -1f; rateLastBytes[i] = -1L; lastDecoded[i] = -1; healthyStreak[i] = 0; camDown[i] = false; continue }

            // 통계 1회 읽기: decodedVideo(생존판정=실제 프레임) + 수신바이트(Mbps 표시)
            var pics = -1; var bytes = -1L
            try {
                val m = p.media; val s = m?.stats
                if (s != null) {
                    pics = s.decodedVideo
                    val demux = s.demuxReadBytes; val input = s.readBytes
                    val v = if (demux > 0) demux else input
                    bytes = if (v <= 0) -1L else (v.toLong() and 0xFFFFFFFFL)
                }
                m?.release()
            } catch (e: Exception) {}

            // 생존판정: 실제 디코딩 프레임이 늘어야 정상(네트워크죽음·디코더프리즈 모두 감지).
            //  ※ player.time·수신바이트는 스트림 멈춰도 잠깐 흐르거나 stale → 오판 → 프레임수만 신뢰
            //  ※ 2폴 연속 증가만 인정 — 스톨 직후 버퍼배출 1폴 blip이 타이머 리셋해 감지 지연시키는 것 방지
            val advanced = pics >= 0 && lastDecoded[i] in 0 until pics
            if (pics >= 0) lastDecoded[i] = pics
            healthyStreak[i] = if (advanced) healthyStreak[i] + 1 else 0
            val healthy = healthyStreak[i] >= 2

            // Mbps 표시용 바이트 레이트 (판정엔 미사용)
            if (bytes >= 0L) {
                val prev = rateLastBytes[i]; val prevNs = rateLastNs[i]
                if (prevNs > 0L && prev in 0L..bytes) {
                    val dt = (nowNs - prevNs) / 1e9
                    if (dt >= 0.5) { rateMbps[i] = ((bytes - prev) * 8.0 / dt / 1_000_000.0).toFloat(); rateLastBytes[i] = bytes; rateLastNs[i] = nowNs }
                } else { rateLastBytes[i] = bytes; rateLastNs[i] = nowNs }
            }

            if (healthy) {
                if (camDown[i]) {
                    camDown[i] = false
                    UsageLogger.log("cam_fault", "cam" to ch.label,
                        "sec" to (nowNs - camDownSince[i]) / 1_000_000_000L, "reason" to (faultReason[i] ?: ""))
                }
                lastHealthyNs[i] = nowNs; faultReason[i] = null
            }
            val ip = slotIp(i)
            val downMs = (nowNs - lastHealthyNs[i]) / 1_000_000
            if (downMs > DOWN_ALARM_MS) {              // 25초+ 지속 = 진짜 장애 → 경보
                downCount++
                if (!camDown[i]) { camDown[i] = true; camDownSince[i] = nowNs; UsageLogger.log("cam_down", "cam" to ch.label) }
                if (downLabels.isNotEmpty()) downLabels.append(", ")
                downLabels.append(ch.label)
                if (nowNs - lastProbeNs[i] > 8_000_000_000L) {
                    lastProbeNs[i] = nowNs
                    Thread { faultReason[i] = diagnoseCamera(ip) }.start()
                }
                setDot(i, "red")
                ipList[i].text = "⚠ 확인 필요"           // 운전자 단순 문구 (기술 사유는 탭→진단)
                ipList[i].setTextColor(Color.parseColor("#FF6B6B"))
            } else if (downMs > DOWN_MILD_MS) {        // 10~25초 = 일시 지연(회복 가능) → 조용히 재연결, 경보 X
                setDot(i, "yellow")
                ipList[i].text = "재연결 중…"
                ipList[i].setTextColor(Color.parseColor("#FFC107"))
            } else {
                ipList[i].text = if (rateMbps[i] >= 0f) "$ip   %.1fM".format(rateMbps[i]) else ip
                ipList[i].setTextColor(Color.parseColor("#AACCFF"))
            }
        }
        if (downCount > 0) {
            b.faultBanner.visibility = View.VISIBLE
            val ssid = currentSsid()
            val good = ssidPrefs().getString("goodSsid", "") ?: ""
            if (ssid.isNotEmpty() && good.isNotEmpty() && ssid != good) {
                b.faultBanner.text = "⚠ 다른 와이파이 연결됨! 현재 '$ssid' — 전용 AP 아님 (탭하여 확인)"
                b.faultBanner.setBackgroundColor(Color.parseColor("#DDB00030"))
                if (!wifiBadLogged) { wifiBadLogged = true; UsageLogger.log("wifi", "k" to "wrong_ap", "ssid" to ssid) }
            } else {
                b.faultBanner.text = "⚠ 카메라 ${downCount}대 확인 필요 — $downLabels  (탭하여 확인)"
                b.faultBanner.setBackgroundColor(Color.parseColor("#DD8A1A1A"))
            }
        } else {
            b.faultBanner.visibility = View.GONE
            wifiBadLogged = false
            if (channels.isNotEmpty() && rateMbps.any { it >= 0f }) {
                val s = currentSsid()
                if (s.isNotEmpty()) ssidPrefs().edit().putString("goodSsid", s).apply()
            }
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSnapshotMs > 5 * 60_000L) {
            lastSnapshotMs = nowMs
            val st = powerMonitor?.status
            UsageLogger.log("snapshot",
                "pct" to (st?.level ?: -1), "chg" to (st?.charging ?: false),
                "rssi" to currentRssi(), "ssid" to currentSsid(), "down" to downCount)
        }
    }

    private fun ssidPrefs() = getSharedPreferences("net_check", Context.MODE_PRIVATE)
    private fun currentSsid(): String = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val s = wm.connectionInfo?.ssid?.replace("\"", "") ?: ""
        if (s.isBlank() || s.contains("unknown", true)) "" else s
    } catch (e: Exception) { "" }

    private fun diagnoseCamera(ip: String): String {
        if (ip.isBlank()) return "IP 없음"
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, 554), 1500) }
            "RTSP 오류 (코덱/계정 확인)"
        } catch (e: java.net.ConnectException) {
            if (e.message?.contains("refused", true) == true) "RTSP 포트 닫힘(554)" else "응답 없음 (전원·랜·IP)"
        } catch (e: java.net.SocketTimeoutException) { "응답 없음 (전원·랜·IP)" }
          catch (e: java.net.NoRouteToHostException) { "응답 없음 (전원·랜·IP)" }
          catch (e: Exception) { "연결 오류" }
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        // 항상 remove 후 post → 카메라 전환(재개)마다 시계 타이머가 중복 누적되는 것 방지
        handler.removeCallbacks(clockRunnable); handler.post(clockRunnable)
        alertDb = AlertDatabase(this)
        startAll()
        startCameraPowerIfEnabled(autoOn = true)   // 앱 실행/복귀 시 무선 전원 자동 ON (사용 설정 시)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
        // 단독뷰 전환 시(단독뷰 onResume 前) 4분할 플레이어를 미리 해제 →
        //   플레이어 겹침으로 인한 vout 생성 실패(검은화면) 방지 + 배경 스트리밍 낭비 제거. (onStop 은 너무 늦음)
        stopAll()
    }

    private fun setFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onStop() {
        super.onStop()
        // 앱이 백그라운드로 가도 OSD 정리 (안전장치)
        try { sendEmptyPosOsd() } catch (e: Exception) {}
        handler.removeCallbacks(clockRunnable)   // 백그라운드에선 시계 타이머 정지
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        powerMonitor?.stop(); powerMonitor = null
        releaseWakeLock()
        CameraPowerController.onUpdate = null; CameraPowerController.stop()
        // ★ 카메라 OSD 텍스트 제거 (잘못된 값이 영상에 박히는 것 방지)
        try { sendEmptyPosOsd() } catch (e: Exception) {}
        stopAll()
        ocrEngine?.release(); ocrEngine = null
        handler.removeCallbacks(clockRunnable)
        libVLC = null   // 전역 공유 인스턴스이므로 release 하지 않음 (참조만 해제)
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private fun restartAll() { stopAll(); startAll() }

    private fun showUsageLog() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(40, 28, 40, 16); text = "집계 중..."
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("📈 사용 로그").setView(sv).setPositiveButton("닫기", null).show()
        Thread { val s = UsageLogger.summary(7); runOnUiThread { tv.text = s } }.start()
    }

    // ── 전원/배터리 감시 + 절전/복귀 ──
    private fun startPowerMonitor() {
        if (powerMonitor != null) return
        powerMonitor = PowerMonitor(this,
            onEvent  = { kind, msg -> runOnUiThread { onPowerEvent(kind, msg) } },
            onStatus = { st -> runOnUiThread { updateBattery(st) } },
            onSleep  = { runOnUiThread { enterSleep() } },
            onWake   = { runOnUiThread { wakeUp() } }
        ).also { it.start() }
    }

    private fun updateBattery(st: PowerMonitor.Status) {
        if (!::b.isInitialized) return
        val icon = if (st.charging) "⚡" else "🔋"
        val color = when {
            st.level <= 15 -> "#FF5252"
            st.level <= 30 -> "#FFC107"
            st.charging    -> "#4CAF50"
            else           -> "#AAAAAA"
        }
        b.tvBattery.text = "$icon ${st.level}%"
        b.tvBattery.setTextColor(Color.parseColor(color))
        if (st.level != lastBatteryLogged) {
            lastBatteryLogged = st.level
            UsageLogger.log("battery", "pct" to st.level, "chg" to st.charging, "tC" to st.tempC.toInt())
        }
    }

    private fun onPowerEvent(kind: PowerMonitor.Kind, msg: String) {
        if (msg.isBlank()) return
        android.util.Log.d("Power", "[$kind] $msg")
        UsageLogger.log("power", "kind" to kind.name, "msg" to msg)
        if (kind == PowerMonitor.Kind.INFO) return
        b.powerBanner.visibility = View.VISIBLE
        b.powerBanner.text = "${if (kind == PowerMonitor.Kind.CRIT) "🔴" else "⚠"}  $msg"
        b.powerBanner.setBackgroundColor(Color.parseColor(if (kind == PowerMonitor.Kind.CRIT) "#DD8A1A1A" else "#DD7A4A0A"))
        beep()
        handler.removeCallbacksAndMessages(powerBannerToken)
        handler.postAtTime({ b.powerBanner.visibility = View.GONE },
            powerBannerToken, android.os.SystemClock.uptimeMillis() + 12000L)
    }

    private fun beep() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400)
            handler.postDelayed({ tg.release() }, 600L)
        } catch (e: Exception) {}
    }

    /** 시동 OFF N분 → 절전: 영상·감지 중지 + 화면 어둡게/꺼지게 (배터리 보존). 기기는 살아있음. */
    private fun enterSleep() {
        android.util.Log.w("Power", "절전 진입")
        UsageLogger.log("sleep")
        Watchdog.setSleeping(true)
        sleepOverlay.visibility = View.VISIBLE
        sleepOverlay.bringToFront()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        try { sendEmptyPosOsd() } catch (e: Exception) {}
        ocrEngine?.stopLoop()
        stopAll()
        if (BatteryProfile.isEnabled(this)) CameraPowerController.setOutput(false)   // 절전 시 카메라 배터리 OFF
    }

    /** 시동 ON → 복귀: 화면 켜고 영상·감지 재개. */
    private fun wakeUp() {
        android.util.Log.w("Power", "복귀(깨우기)")
        UsageLogger.log("wake")
        Watchdog.setSleeping(false)
        acquireWakeLock()
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
        sleepOverlay.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        setFullscreen()
        startAll()
        startCameraPowerIfEnabled(autoOn = true)   // 복귀 시 카메라 배터리 ON
        handler.postDelayed({ releaseWakeLock() }, 4000L)
    }

    // ── 카메라 전원(무선) — 상태 표시/알람 공통 처리 ──
    private var powerDialogRender: ((Boolean, CameraPowerController.Status?) -> Unit)? = null
    private var lastPowerAlarmMs = 0L

    private fun onPowerUpdate(connected: Boolean, st: CameraPowerController.Status?) {
        val tvc = b.tvCamBattery
        if (BatteryProfile.isEnabled(this)) {
            if (st != null) {
                val v = BatteryProfile.evaluate(this, st.volts)
                val out = if (st.relayOn) "⚡ON" else "⛔OFF"
                tvc.text = "🔋$out %.1fV %s".format(st.volts, v.text) + (if (!connected) " ⚠끊김" else "")
                tvc.setTextColor(if (v.level >= 2) Color.parseColor("#FF6B6B") else if (v.level == 1) Color.parseColor("#FFC107") else Color.parseColor("#8FE388"))
            } else {
                tvc.text = if (connected) "🔋 카메라 대기" else "🔋 TX 미연결"
                tvc.setTextColor(Color.parseColor("#AAAAAA"))
            }
            tvc.visibility = View.VISIBLE
        } else tvc.visibility = View.GONE
        if (st != null && BatteryProfile.isEnabled(this)) {
            val v = BatteryProfile.evaluate(this, st.volts)
            if (v.level >= 1) {
                val now = System.currentTimeMillis()
                if (now - lastPowerAlarmMs > 60_000L) {
                    lastPowerAlarmMs = now
                    val msg = if (v.level >= 2) "⚠ 카메라 배터리 긴급! %.1fV — 즉시 충전/교체".format(st.volts)
                              else "⚠ 카메라 배터리 %s · %.1fV — 충전/교체 필요".format(v.text, st.volts)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    try { val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                          tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2); handler.postDelayed({ tg.release() }, 800) } catch (_: Exception) {}
                }
            }
        }
        powerDialogRender?.invoke(connected, st)
    }

    private fun startCameraPowerIfEnabled(autoOn: Boolean) {
        if (!BatteryProfile.isEnabled(this)) { b.tvCamBattery.visibility = View.GONE; return }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        CameraPowerController.onUpdate = { c, s -> runOnUiThread { onPowerUpdate(c, s) } }
        CameraPowerController.ensure(this)
        if (autoOn) CameraPowerController.setOutput(true)
        onPowerUpdate(CameraPowerController.isConnected(), CameraPowerController.lastStatus)
    }

    private fun showCameraPower() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val need = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 77); return }
        }
        val ctx = this
        val tv = android.widget.TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(48, 24, 48, 16) }
        val chkEnable = android.widget.CheckBox(ctx).apply { text = "무선 전원제어 사용"; setTextColor(Color.WHITE); isChecked = BatteryProfile.isEnabled(ctx) }
        val btnOn  = android.widget.Button(ctx).apply { text = "전원 ON" }
        val btnOff = android.widget.Button(ctx).apply { text = "전원 OFF (절전)" }
        val btnSet = android.widget.Button(ctx).apply { text = "🔧 배터리 설정" }
        val btnForget = android.widget.Button(ctx).apply { text = "TX 재검색(연결 초기화)" }
        val ll = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(chkEnable); addView(tv); addView(btnOn); addView(btnOff); addView(btnSet); addView(btnForget)
        }
        fun render(connected: Boolean, st: CameraPowerController.Status?) {
            val sb = StringBuilder()
            sb.append(if (connected) "🟢 운전실 TX 연결됨\n" else "🔴 운전실 TX 미연결\n")
            if (st != null) {
                sb.append(if (st.rxLinked) "🟢 배터리 RX 연결됨\n" else "🔴 배터리 RX 응답 없음\n")
                sb.append("카메라 전원: ${if (st.relayOn) "ON ⚡" else "OFF ⛔"}\n")
                val v = BatteryProfile.evaluate(ctx, st.volts)
                sb.append("배터리: %.2fV\n".format(st.volts))
                sb.append("잔량: ${v.text}")
            } else sb.append("(상태 수신 대기)")
            tv.text = sb.toString()
        }
        powerDialogRender = { c, s -> render(c, s) }
        startCameraPowerIfEnabled(autoOn = false)
        render(CameraPowerController.isConnected(), CameraPowerController.lastStatus)
        chkEnable.setOnCheckedChangeListener { _, on ->
            BatteryProfile.setEnabled(ctx, on)
            if (on) startCameraPowerIfEnabled(autoOn = true) else { CameraPowerController.stop(); b.tvCamBattery.visibility = View.GONE }
        }
        btnOn.setOnClickListener { CameraPowerController.setOutput(true) }
        btnOff.setOnClickListener {
            AlertDialog.Builder(ctx).setMessage("카메라 배터리 출력을 차단합니다.\n(카메라가 꺼집니다) 계속할까요?")
                .setPositiveButton("차단") { _, _ -> CameraPowerController.setOutput(false) }
                .setNegativeButton("취소", null).show()
        }
        btnSet.setOnClickListener { showBatterySettings() }
        btnForget.setOnClickListener {
            CameraPowerController.forget(ctx)
            Toast.makeText(ctx, "TX 연결 초기화됨 — 다시 검색합니다", Toast.LENGTH_SHORT).show()
            if (BatteryProfile.isEnabled(ctx)) startCameraPowerIfEnabled(autoOn = false)
        }
        AlertDialog.Builder(ctx).setTitle("🔋 카메라 전원 (무선)").setView(ll)
            .setPositiveButton("닫기", null)
            .setOnDismissListener { powerDialogRender = null }
            .show()
    }

    private fun showBatterySettings() {
        val ctx = this
        val cur = BatteryProfile.type(ctx)
        val types = BatteryProfile.Type.values()
        val ll = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }
        fun label(t: String) = android.widget.TextView(ctx).apply { text = t; setTextColor(Color.parseColor("#AACCFF")); textSize = 13f; setPadding(0, 16, 0, 4) }
        ll.addView(label("배터리 종류"))
        val spinner = android.widget.Spinner(ctx)
        spinner.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, types.map { it.label })
        spinner.setSelection(types.indexOf(cur))
        ll.addView(spinner)
        ll.addView(label("용량 (Ah)"))
        val capEt = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(BatteryProfile.capacityAh(ctx).toString()); setTextColor(Color.WHITE)
        }
        ll.addView(capEt)
        val thBox = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        ll.addView(thBox)
        val thFields = HashMap<String, android.widget.EditText>()
        fun rebuildThresholds() {
            thBox.removeAllViews(); thFields.clear()
            thBox.addView(label("전압 임계값 (V)"))
            BatteryProfile.getThresholdsV(ctx).forEach { (k, v) ->
                val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                row.addView(android.widget.TextView(ctx).apply { text = k; setTextColor(Color.WHITE); width = 320 })
                val et = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText("%.2f".format(v)); setTextColor(Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                thFields[k] = et; row.addView(et); thBox.addView(row)
            }
        }
        rebuildThresholds()
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (types[pos] != BatteryProfile.type(ctx)) { BatteryProfile.setType(ctx, types[pos]); rebuildThresholds() }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(ll) }
        AlertDialog.Builder(ctx).setTitle("🔧 배터리 설정").setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                capEt.text.toString().toIntOrNull()?.let { BatteryProfile.setCapacity(ctx, it) }
                val vals = HashMap<String, Float>()
                thFields.forEach { (k, et) -> et.text.toString().toFloatOrNull()?.let { vals[k] = it } }
                BatteryProfile.setThresholdsV(ctx, vals)
                Toast.makeText(ctx, "배터리 설정 저장됨", Toast.LENGTH_SHORT).show()
                onPowerUpdate(CameraPowerController.isConnected(), CameraPowerController.lastStatus)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            showCameraPower()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "tower:wake").apply { acquire(8000L) }
        } catch (e: Exception) { android.util.Log.e("Power", "wakelock 실패: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startAll() {
        if (isSleeping()) return   // 절전 중에는 스트림을 켜지 않음
        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)
        ocrChannelIndex = channels.size - 1

        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = ch.label
                ipList[i].text  = ch.extractIp()
                setDot(i, "gray")
                touchList[i].setOnClickListener { openPlayer(i) }
                startStream(i, ch)
                lastHealthyNs[i] = System.nanoTime()   // 최초 연결 grace(여기서만) → 이후 판정은 실제 프레임으로
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"; ipList[i].text = ""
                setDot(i, "gray")
            }
        }

        if (channels.isEmpty()) {
            lblList[0].text = "⚙ 설정에서 장치를 등록해 주세요"
        }

        if (OcrSettings.isOcrEnabled(this) && ocrChannelIndex >= 0) {
            startOcrLoop()
        }
    }

    private fun stopAll() {
        for (i in 0 until 4) {
            reconnectJobs[i]?.let { handler.removeCallbacks(it) }
            reconnectJobs[i] = null
        }
        for (i in 0 until 4) {
            players[i]?.stop()
            players[i]?.detachViews()
            players[i]?.release()
            players[i] = null
        }
    }

    private fun startStream(idx: Int, ch: Channel) {
        players[idx]?.stop()
        players[idx]?.detachViews()
        players[idx]?.release()
        players[idx] = null

        setDot(idx, "yellow")
        faultReason[idx] = null
        lastDecoded[idx] = -1; healthyStreak[idx] = 0; rateLastBytes[idx] = -1L; rateLastNs[idx] = 0L   // 판정 기준 재설정
        // ※ grace(lastHealthyNs)는 여기서 리셋하지 않음 — 재연결이 죽은 카메라를 '정상'으로 은폐하기 때문. grace는 startAll에서 최초 1회만.

        val player = MediaPlayer(libVLC)
        players[idx] = player
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN  // 화면 꽉 채움(검은 띠 제거, 가장자리 크롭)

        val url = ch.subUrl   // 브랜드별 서브스트림 (Device.toChannels 에서 생성)
        val media = Media(libVLC, Uri.parse(url))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=100")  // 단독뷰와 동일: 지연 단축
        media.addOption(":rtsp-tcp")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":no-audio")
        player.media = media
        media.release()
        player.play()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        setDot(idx, "green")
                        reconnectJobs[idx]?.let { handler.removeCallbacks(it) }
                        reconnectJobs[idx] = null
                    }
                    MediaPlayer.Event.Buffering -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError -> {
                        setDot(idx, "red"); scheduleReconnect(idx, ch)
                    }
                    MediaPlayer.Event.EndReached -> {
                        setDot(idx, "gray"); scheduleReconnect(idx, ch)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(idx: Int, ch: Channel) {
        if (isSleeping()) return   // 절전 중 재연결 금지
        reconnectJobs[idx]?.let { handler.removeCallbacks(it) }
        val job = Runnable {
            if (!isDestroyed && !isFinishing && !isSleeping()) startStream(idx, ch)
        }
        reconnectJobs[idx] = job
        handler.postDelayed(job, 1500L)   // 일시적 0.0.0.0 등 빠른 복구(6초 팔트배너 전에)
    }

    private fun startOcrLoop() {
        // ★ 시작 시 모든 OCR 상태 초기화 (이전 값/경보 잔재 제거)
        lastWeight      = null
        lastWeightAlert = false
        lastAlertTime   = 0L
        com.pone.towerccctv.ocr.OcrSettings.clearLatest(this)

        // ★ 등록된 마지막 카메라(OCR 대상 = CH4 관행)의 실제 인증정보 사용
        // (하드코딩 비번 제거 - 자동등록 시 입력한 비번이 자동 적용됨)
        val devs = DeviceStore.load(this).filter { it.enabled }
        if (devs.isEmpty()) return
        val dev = devs.last()
        // 메인스트림(101) picture 사용 - ROI 설정 화면과 동일한 소스 (좌표 일치)
        val ocrUrl  = "http://${dev.ip}:${dev.httpPort}/ISAPI/Streaming/channels/101/picture"
        val ocrUser = dev.username
        val ocrPass = dev.password

        b.osdOverlay.visibility = View.VISIBLE
        b.tvOcrBadge.visibility = View.VISIBLE
        // 초기 상태로 OSD 표시 (-- 로 표시)
        updateOsd()
        // 카메라 OSD에 남아있을 수 있는 이전 텍스트도 클리어
        sendEmptyPosOsd()
        ocrEngine?.release()
        ocrEngine = OcrEngine(this).also { engine ->
            engine.onResult = { result ->
                // ★ null도 그대로 반영 (이전 값 유지 금지)
                lastWeight      = result.weight
                lastWeightAlert = result.weightAlert
                updateOsd()
                sendPosOsd(lastWeight)
            }
            engine.startHttpLoop(ocrUrl, ocrUser, ocrPass)
        }
    }

    private fun updateOsd() {
        val isTon = OcrSettings.isWeightTon(this)
        b.osdWeight.text = if (lastWeight != null) {
            if (isTon) "⚖  %.2f t".format(lastWeight) else "⚖  %.2f kg".format(lastWeight)
        } else "⚖  --"
        b.osdWeight.setTextColor(if (lastWeightAlert) Color.parseColor("#FF4444") else Color.WHITE)

        if (lastWeightAlert) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > ALERT_INTERVAL) {
                lastAlertTime = now
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
                alertDb.insert(AlertRecord(
                    dateTime    = sdf.format(java.util.Date()),
                    weight      = lastWeight,
                    weightAlert = lastWeightAlert
                ))
                b.osdOverlay.setBackgroundColor(Color.parseColor("#DD880000"))
                handler.postDelayed({ b.osdOverlay.setBackgroundColor(Color.parseColor("#DD000000")) }, 3000L)
                val msg = if (lastWeight != null) "🚨 중량 초과: %.2f t".format(lastWeight) else "🚨 중량 초과"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                playAlertSound()
            }
        }
    }

    private fun playAlertSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            handler.postDelayed({ tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 400L)
            handler.postDelayed({
                tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                handler.postDelayed({ tg.release() }, 400L)
            }, 800L)
        } catch (e: Exception) {}

        handler.postDelayed({
            tts?.speak("중량 초과. 중량 초과", TextToSpeech.QUEUE_FLUSH, null, "alert")
        }, 1200L)
    }

    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        val bmp: Bitmap? = try {
            var found: Bitmap? = null
            val vlcView = vlcList[index]
            for (i in 0 until vlcView.childCount) {
                val child = vlcView.getChildAt(i)
                if (child is TextureView) { found = child.bitmap; break }
            }
            found
        } catch (e: Exception) { null }

        lifecycleScope.launch {
            val snapPath = bmp?.let {
                withContext(Dispatchers.IO) {
                    try {
                        val f = File(cacheDir, "snap_$index.jpg")
                        FileOutputStream(f).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                        it.recycle()
                        f.absolutePath
                    } catch (e: Exception) { null }
                }
            }
            startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra("rtspUrl",    ch.rtspUrl)
                putExtra("httpBase",   ch.httpBase)
                putExtra("username",   ch.username)
                putExtra("password",   ch.password)
                putExtra("label",      ch.label)
                putExtra("hasPtz",     ch.hasPtz)
                putExtra("ptzChannel", ch.ptzChannel)
                putExtra("deviceType", ch.deviceType.name)
                putExtra("brand",      ch.brand.name)
                snapPath?.let { putExtra("snapPath", it) }
            })
            overridePendingTransition(0, 0)
        }
    }

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(when(c) {
            "green"  -> R.drawable.dot_green
            "yellow" -> R.drawable.dot_yellow
            "red"    -> R.drawable.dot_red
            else     -> R.drawable.dot_gray
        })
    }

    private fun syncCameraTime() {
        val devices = DeviceStore.load(this).filter { it.enabled }
        if (devices.isEmpty()) {
            Toast.makeText(this, "등록된 카메라가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "카메라 ${devices.size}대 시간 동기화 중...", Toast.LENGTH_SHORT).show()

        val now = java.util.Calendar.getInstance()
        val year  = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day   = now.get(java.util.Calendar.DAY_OF_MONTH)
        val hour  = now.get(java.util.Calendar.HOUR_OF_DAY)
        val min   = now.get(java.util.Calendar.MINUTE)
        val sec   = now.get(java.util.Calendar.SECOND)

        val timeXml = """<?xml version="1.0" encoding="UTF-8"?>
<Time>
<timeMode>manual</timeMode>
<localTime>${"%04d".format(year)}-${"%02d".format(month)}-${"%02d".format(day)}T${"%02d".format(hour)}:${"%02d".format(min)}:${"%02d".format(sec)}+09:00</localTime>
<timeZone>CST-9:00:00</timeZone>
</Time>"""

        var successCount = 0
        var doneCount = 0
        val failLines = java.util.Collections.synchronizedList(mutableListOf<String>())

        devices.forEach { dev ->
            Thread {
                // 사유: null=성공, 그 외=실패 사유(운전자/설치자용 문구)
                val fail: String? = try {
                    val authCache = java.util.concurrent.ConcurrentHashMap<String,
                            com.burgstaller.okhttp.digest.CachingAuthenticator>()
                    val creds = com.burgstaller.okhttp.digest.Credentials(dev.username, dev.password)
                    val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                        .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                        .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds))
                        .build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, authCache))
                        .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(authCache))
                        .trustSelfSignedCam()   // 한화 HTTPS 자체서명 신뢰
                        .build()
                    // ★장치관리에 설정된 브랜드 프로토콜로 분기 (하드코딩 X)
                    val code: Int; var bad = false
                    when (dev.brand) {
                        com.pone.towerccctv.model.CameraBrand.HANWHA -> {
                            val q = "SyncType=Manual&Year=$year&Month=$month&Day=$day&Hour=$hour&Minute=$min&Second=$sec"
                            client.newCall(okhttp3.Request.Builder()
                                .url("https://${dev.ip}/stw-cgi/system.cgi?msubmenu=date&action=set&$q").get().build())
                                .execute().use { code = it.code; bad = (it.body?.string() ?: "").contains("NG", true) }
                        }
                        com.pone.towerccctv.model.CameraBrand.DAHUA -> {
                            val ts = java.net.URLEncoder.encode(
                                "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, min, sec), "UTF-8")
                            client.newCall(okhttp3.Request.Builder()
                                .url("http://${dev.ip}/cgi-bin/global.cgi?action=setCurrentTime&time=$ts").get().build())
                                .execute().use { code = it.code }
                        }
                        else -> {   // HIKVISION / IDIS : ISAPI
                            val body = okhttp3.RequestBody.create("application/xml".toMediaType(), timeXml)
                            client.newCall(okhttp3.Request.Builder()
                                .url("http://${dev.ip}/ISAPI/System/time").put(body).build())
                                .execute().use { code = it.code }
                        }
                    }
                    when {
                        code in 200..299 && !bad -> null
                        code == 401 || code == 403 -> "아이디/비밀번호 확인"
                        code == 404 -> "브랜드 설정 확인"      // 이 브랜드에 없는 경로 → 등록 브랜드 오류
                        else -> "카메라 응답 오류($code)"
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    "연결 안 됨(응답 없음)"
                } catch (e: java.net.ConnectException) {
                    "연결 안 됨(네트워크)"
                } catch (e: Exception) {
                    "연결 안 됨"
                }

                synchronized(this) {
                    if (fail == null) successCount++
                    else failLines.add("• ${dev.name} (${dev.ip}) — $fail")
                    doneCount++
                    if (doneCount == devices.size) {
                        handler.post {
                            if (failLines.isEmpty()) {
                                Toast.makeText(this,
                                    "✅ 시간 동기화 완료: ${successCount}대 모두 성공",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                // 실패가 있으면 사유를 다이얼로그로 명확히 (운전자가 즉시 조치)
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("시간 동기화 결과")
                                    .setMessage("✅ 성공 ${successCount}대\n❌ 실패 ${failLines.size}대\n\n"
                                            + failLines.joinToString("\n"))
                                    .setPositiveButton("확인", null)
                                    .show()
                            }
                        }
                    }
                }
            }.start()
        }
    }

    private fun sendPosOsd(weight: Float?) {
        val isTon = OcrSettings.isWeightTon(this)
        val weightStr = weight?.let {
            if (isTon) "중량 %.2f t".format(it) else "중량 %.2f kg".format(it)
        } ?: "중량 --"
        // ★ 갱신 시각을 함께 표기 → 앱이 끊기면 시각이 멈춰 stale 여부를 식별 가능
        //   (카메라 자체 시계와 비교. 네트워크 복구/앱 재시작 시 자동 갱신)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
            .format(java.util.Date())
        val text = "$weightStr   $time"

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TextOverlayList>
  <TextOverlay>
    <id>1</id>
    <enabled>true</enabled>
    <positionX>0</positionX>
    <positionY>0</positionY>
    <displayText>$text</displayText>
  </TextOverlay>
</TextOverlayList>"""

        val ch1 = channels.firstOrNull() ?: return

        Thread {
            try {
                val authCache = java.util.concurrent.ConcurrentHashMap<String,
                        com.burgstaller.okhttp.digest.CachingAuthenticator>()
                val creds = com.burgstaller.okhttp.digest.Credentials(ch1.username, ch1.password)
                val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                    .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                    .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds))
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, authCache))
                    .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(authCache))
                    .build()
                val body = okhttp3.RequestBody.create("application/xml".toMediaType(), xml)
                val req = okhttp3.Request.Builder()
                    .url("http://${ch1.extractIp()}/ISAPI/System/Video/inputs/channels/1/overlays/text")
                    .put(body).build()
                val resp = client.newCall(req).execute()
                resp.close()
            } catch (e: Exception) {}
        }.start()
    }

    // ── 빈 OSD 송출 (텍스트 완전 제거, enabled=false) ──
    private fun sendEmptyPosOsd() {
        sendOverlayXml(text = "", enabled = false)
    }

    // ── 공통 OSD 송출 헬퍼 (sendEmptyPosOsd 가 호출) ──
    private fun sendOverlayXml(text: String, enabled: Boolean) {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TextOverlayList>
  <TextOverlay>
    <id>1</id>
    <enabled>$enabled</enabled>
    <positionX>0</positionX>
    <positionY>0</positionY>
    <displayText>$text</displayText>
  </TextOverlay>
</TextOverlayList>"""
        // 첫 번째 등록된 카메라(CH1)에 송출 - IP 변경 대응
        val ch1 = channels.firstOrNull() ?: return
        Thread {
            try {
                val authCache = java.util.concurrent.ConcurrentHashMap<String,
                        com.burgstaller.okhttp.digest.CachingAuthenticator>()
                val creds = com.burgstaller.okhttp.digest.Credentials(ch1.username, ch1.password)
                val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                    .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                    .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds))
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, authCache))
                    .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(authCache))
                    .build()
                val body = okhttp3.RequestBody.create("application/xml".toMediaType(), xml)
                val req = okhttp3.Request.Builder()
                    .url("http://${ch1.extractIp()}/ISAPI/System/Video/inputs/channels/1/overlays/text")
                    .put(body).build()
                val resp = client.newCall(req).execute()
                resp.close()
            } catch (e: Exception) {}
        }.start()
    }

    // ── 자동 등록 다이얼로그 (카메라 4대 / NVR 1대 선택) ──
    private fun showAutoRegisterDialog() {
        AlertDialog.Builder(this).setTitle("⚡ 기본 장치 자동 등록")
            .setItems(arrayOf("📷 카메라 4대 (101~104)", "📼 NVR 1대 (100)")) { _, w ->
                if (w == 0) showAutoRegisterCamerasDialog() else showAutoRegisterNvrDialog()
            }.setNegativeButton("취소", null).show()
    }

    // ── 카메라 4대 등록 입력 다이얼로그 ──
    private fun showAutoRegisterCamerasDialog() {
        val ctx = this
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun lbl(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        fun input(default: String, hint: String): android.widget.EditText =
            android.widget.EditText(this).apply {
                setText(default); this.hint = hint
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                setBackgroundColor(Color.parseColor("#1E1E22"))
                setPadding(20, 14, 20, 14)
            }

        container.addView(lbl("사용자명"))
        val etUser = input("admin", "admin")
        container.addView(etUser)

        container.addView(lbl("비밀번호 (4대 공통)"))
        val etPass = input("", "예: 1q2w3e4r@")
        container.addView(etPass)

        container.addView(lbl("시작 IP (자동으로 +1, +2, +3)"))
        val etIp = input("192.168.0.101", "192.168.0.101")
        container.addView(etIp)

        container.addView(android.widget.TextView(this).apply {
            text = "CH4는 PTZ 없음으로 등록됨\n등록 후 장치 관리에서 IP/이름 개별 변경 가능"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this).setTitle("📷 카메라 4대 자동 등록")
            .setView(container)
            .setPositiveButton("등록") { _, _ ->
                val user    = etUser.text.toString().trim().ifEmpty { "admin" }
                val pass    = etPass.text.toString()
                val startIp = etIp.text.toString().trim().ifEmpty { "192.168.0.101" }
                autoRegisterCameras(user, pass, startIp)
            }
            .setNegativeButton("취소", null).show()
    }

    // ── NVR 1대 등록 입력 다이얼로그 ──
    private fun showAutoRegisterNvrDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun lbl(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        fun input(default: String, hint: String, numeric: Boolean = false): android.widget.EditText =
            android.widget.EditText(this).apply {
                setText(default); this.hint = hint
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setBackgroundColor(Color.parseColor("#1E1E22"))
                setPadding(20, 14, 20, 14)
            }

        container.addView(lbl("사용자명"))
        val etUser = input("admin", "admin")
        container.addView(etUser)

        container.addView(lbl("비밀번호"))
        val etPass = input("", "예: 1q2w3e4r@")
        container.addView(etPass)

        container.addView(lbl("NVR IP"))
        val etIp = input("192.168.0.100", "192.168.0.100")
        container.addView(etIp)

        container.addView(lbl("채널 수"))
        val etChCount = input("4", "4", numeric = true)
        container.addView(etChCount)

        container.addView(android.widget.TextView(this).apply {
            text = "등록 후 장치 관리에서 변경 가능"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this).setTitle("📼 NVR 1대 자동 등록")
            .setView(container)
            .setPositiveButton("등록") { _, _ ->
                val user    = etUser.text.toString().trim().ifEmpty { "admin" }
                val pass    = etPass.text.toString()
                val ip      = etIp.text.toString().trim().ifEmpty { "192.168.0.100" }
                val chCount = etChCount.text.toString().toIntOrNull() ?: 4
                autoRegisterNvr(user, pass, ip, chCount)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun autoRegisterCameras(user: String, pass: String, startIp: String) {
        val parts = startIp.split(".")
        if (parts.size != 4 || parts[3].toIntOrNull() == null) {
            Toast.makeText(this, "IP 형식이 올바르지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val prefix   = "${parts[0]}.${parts[1]}.${parts[2]}."
        val baseLast = parts[3].toInt()

        DeviceStore.save(this, listOf(
            com.pone.towerccctv.model.Device(
                name="CH1", type=DeviceType.CAMERA, ip="$prefix${baseLast}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH2", type=DeviceType.CAMERA, ip="$prefix${baseLast+1}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH3", type=DeviceType.CAMERA, ip="$prefix${baseLast+2}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH4", type=DeviceType.CAMERA, ip="$prefix${baseLast+3}",
                username=user, password=pass, hasPtz=false)
        ))
        Toast.makeText(this, "카메라 4대 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }

    private fun autoRegisterNvr(user: String, pass: String, ip: String, chCount: Int) {
        DeviceStore.save(this, listOf(com.pone.towerccctv.model.Device(
            name="NVR", type=DeviceType.NVR, ip=ip,
            username=user, password=pass, channelCount=chCount, hasPtz=true)))
        Toast.makeText(this, "NVR 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }
}

private fun Channel.extractIp() = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")