package com.pone.towerccctv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pone.towerccctv.R
import com.pone.towerccctv.UsageLogger
import com.pone.towerccctv.trustSelfSignedCam
import com.pone.towerccctv.controller.PtzController
import com.pone.towerccctv.databinding.ActivityPlayerBinding
import com.pone.towerccctv.model.CameraBrand
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceType
import android.speech.tts.TextToSpeech
import com.pone.towerccctv.ocr.OcrSettings
import com.pone.towerccctv.ocr.RoiOverlayView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var ptz: PtzController? = null
    private var channelLabel = ""
    private var viewStartMs = 0L

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectJob: Runnable? = null
    private var reconnectCount = 0
    private val MAX_RECONNECT = 10
    private var rtspUrlCached = ""
    private var isPlaying = false

    // 프레임 기반 생존 워치독: 재생 중인데 새 프레임이 10초간 안 오면(에러 이벤트 없이 스톨/죽음) '연결 끊김' + 얼어붙은 프레임 덮기
    private var lastPics = -1
    private var liveStreak = 0
    private var lastFrameNs = 0L
    private val liveWatch = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && isPlaying) {
                var pics = -1
                try { val m = p.media; val s = m?.stats; if (s != null) pics = s.decodedVideo; m?.release() } catch (e: Exception) {}
                val adv = pics >= 0 && lastPics in 0 until pics
                if (pics >= 0) lastPics = pics
                liveStreak = if (adv) liveStreak + 1 else 0
                if (liveStreak >= 2) lastFrameNs = System.nanoTime()
                if (lastFrameNs > 0L && (System.nanoTime() - lastFrameNs) / 1_000_000 > 10000) {
                    isPlaying = false
                    setStatus("⚠ 연결 끊김 (카메라 확인)", "#F44336")
                    b.dotPlayer.setBackgroundResource(R.drawable.dot_red)
                    coverDead()
                    scheduleReconnect()
                }
            } else if (p != null && !isPlaying && reconnectJob == null && reconnectCount < MAX_RECONNECT) {
                // 연결은 했는데 4초 넘게 첫 프레임(재생) 안 옴 = vout race 등 무증상 검은화면 → 자동 재시작
                if (lastFrameNs > 0L && (System.nanoTime() - lastFrameNs) / 1_000_000 > 4000) startPlaying(rtspUrlCached)
            }
            reconnectHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var presetBtns: List<Pair<Button, Int>>

    // ── 스와이프 프리셋 순환 ──
    private var presetIdx = -1
    @Volatile private var cameraPresets: List<Int> = emptyList()   // 카메라에 실제 설정된 프리셋
    private val cardHandler = Handler(Looper.getMainLooper())

    // OSD 갱신 (0.3초)
    private val osdHandler = Handler(Looper.getMainLooper())
    private val osdRunnable = object : Runnable {
        override fun run() {
            refreshOsd()
            osdHandler.postDelayed(this, 300L)
        }
    }
    private var tts: TextToSpeech? = null
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL = 10_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setFullscreen()

        val rtspUrl    = intent.getStringExtra("rtspUrl")    ?: return
        val httpBase   = intent.getStringExtra("httpBase")   ?: ""
        val username   = intent.getStringExtra("username")   ?: "admin"
        val password   = intent.getStringExtra("password")   ?: ""
        val label      = intent.getStringExtra("label")      ?: "--"
        val hasPtz     = intent.getBooleanExtra("hasPtz", false)
        val ptzChannel = intent.getIntExtra("ptzChannel", 1)
        val deviceType = DeviceType.valueOf(intent.getStringExtra("deviceType") ?: "CAMERA")
        val brand      = CameraBrand.valueOf(intent.getStringExtra("brand") ?: "HIKVISION")
        channelLabel   = label
        viewStartMs = android.os.SystemClock.elapsedRealtime()
        UsageLogger.log("view_open", "cam" to label)
        rtspUrlCached  = rtspUrl

        b.lblPlayer.text = label
        b.ipPlayer.text  = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
        setStatus("연결 중...", "#FFB300")

        val ocrOn = OcrSettings.isOcrEnabled(this)
        b.osdWeightPlayer.visibility = if (ocrOn) View.VISIBLE else View.GONE

        // 스냅샷 배경 (그리드 직전 프레임)
        val snapPath = intent.getStringExtra("snapPath")
        if (snapPath != null) {
            try {
                BitmapFactory.decodeFile(snapPath)?.let { bmp ->
                    b.imgSnapshot.setImageBitmap(bmp)
                    b.imgSnapshot.alpha = 1f
                    b.imgSnapshot.visibility = View.VISIBLE
                }
            } catch (e: Exception) { }
        } else {
            // 그리드 스냅샷 없으면 → 카메라 JPEG 즉시 받아 배경에 (연결 중 검은화면 방지)
            val ip = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
            loadLiveSnapshot(brand, ip, username, password)
        }

        libVLC = com.pone.towerccctv.VlcProvider.get(this)   // 전역 공유 (재생성 비용 제거)

        // TTS 초기화
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.KOREAN
                tts?.setSpeechRate(0.9f)
            }
        }

        if (hasPtz) {
            val ch = Channel(0, label, rtspUrl, httpBase, ptzChannel,
                username, password, true, deviceType, brand)
            ptz = PtzController(ch)
            // 캐시된 프리셋 목록 먼저 사용(조회 느림/실패 시에도 1~9 폴백 방지) → 조회 성공 시 갱신
            cameraPresets = loadCachedPresets()
            ptz?.fetchPresets { if (it.isNotEmpty()) { cameraPresets = it; saveCachedPresets(it) } }
        } else {
            b.ptzArea.alpha = 0.3f
            b.btnZoomIn.alpha = 0.3f; b.btnZoomIn.isEnabled = false
            b.btnZoomOut.alpha = 0.3f; b.btnZoomOut.isEnabled = false
        }

        presetBtns = listOf(
            b.btnP1 to 1, b.btnP2 to 2, b.btnP3 to 3,
            b.btnP4 to 4, b.btnP5 to 5, b.btnP6 to 6,
            b.btnP7 to 7, b.btnP8 to 8, b.btnP9 to 9
        )

        loadPresetNames()
        setupControls()
        setupDoubleTap()
        startPlaying(rtspUrl)
        // 기본은 패널 숨김(넓게) → 필요할 때 ◧ 패널 로 펼침 (하이드로와 동일)
        b.ptzPanel.visibility = View.GONE
        b.btnWide.text = "◧ 패널"


        if (ocrOn) {
            // MainActivity 백그라운드 OCR이 SharedPreferences 업데이트
            // → 0.3초마다 읽어서 OSD 표시 (즉시 최신값 표시)
            refreshOsd()
            osdHandler.post(osdRunnable)
        }
    }

    // ── OSD 갱신 (SharedPreferences에서 읽기) ──
    fun refreshOsd() {
        val weight      = OcrSettings.getLatestWeight(this)
        val weightAlert = OcrSettings.isLatestWeightAlert(this)
        val isTon       = OcrSettings.isWeightTon(this)

        b.osdWeightPlayer.text = if (weight != null) {
            if (isTon) "중량: %.2f t".format(weight) else "중량: %.2f kg".format(weight)
        } else "중량: --"

        b.osdWeightPlayer.setTextColor(
            if (weightAlert) Color.parseColor("#FF4444") else Color.WHITE)
    }


    // ── 재생 ──
    private fun startPlaying(url: String) {
        player?.stop(); player?.detachViews(); player?.release()
        val p = MediaPlayer(libVLC)
        player = p
        p.attachViews(b.vlcPlayer, null, true, false)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT  // 비율 유지(전체 영상, 크롭·크기변화 없음, 얇은 여백)

        val media = Media(libVLC, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)   // 단독 화면은 HW 디코딩 (시작 빠름·CPU↓)
        // 전체화면 열 때 지연 최소화 (캐싱 축소 + 지터 보정 끔)
        media.addOption(":network-caching=150"); media.addOption(":rtsp-tcp")
        media.addOption(":clock-jitter=0"); media.addOption(":clock-synchro=0")
        media.addOption(":no-audio")   // 오디오 트랙 스킵 → 시작 빠름
        p.media = media; media.release()

        p.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        isPlaying = true; reconnectCount = 0; cancelReconnect()
                        setStatus("● LIVE", "#4CAF50")
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_green)
                        if (b.imgSnapshot.visibility == View.VISIBLE) {
                            b.imgSnapshot.animate().alpha(0f).setDuration(500)
                                .withEndAction {
                                    b.imgSnapshot.visibility = View.GONE
                                    b.imgSnapshot.setImageBitmap(null)
                                    b.imgSnapshot.setBackgroundColor(Color.TRANSPARENT)   // 죽음 막 해제
                                }.start()
                        }
                    }
                    MediaPlayer.Event.Buffering -> {
                        if (!isPlaying) b.dotPlayer.setBackgroundResource(R.drawable.dot_yellow)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isPlaying = false
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_red)
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_gray)
                        scheduleReconnect()
                    }
                }
            }
        }
        p.play()
        lastPics = -1; liveStreak = 0; lastFrameNs = System.nanoTime()   // 워치독 기준 재설정(연결 grace)
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        if (reconnectCount >= MAX_RECONNECT) {
            setStatus("연결 실패 (재시도 중단)", "#F44336"); return
        }
        reconnectCount++
        // 첫 1~2회는 일시적 실패(연결 직후 IP 0.0.0.0 등) 가능성 大 → 0.5초 빠른 재시도 + "연결 중"만(겁나는 에러 숨김)
        val delay = if (reconnectCount <= 2) 500L else minOf(reconnectCount * 2000L, 12000L)
        setStatus(if (reconnectCount <= 2) "연결 중..." else "재연결 중... ($reconnectCount/$MAX_RECONNECT)", "#FF9800")
        if (reconnectCount > 2) coverDead()   // 진짜 문제 → 얼어붙은 프레임 덮기
        val job = Runnable {
            if (isDestroyed || isFinishing) return@Runnable
            b.dotPlayer.setBackgroundResource(R.drawable.dot_yellow)
            startPlaying(rtspUrlCached)
        }
        reconnectJob = job
        reconnectHandler.postDelayed(job, delay)
    }

    private fun cancelReconnect() {
        reconnectJob?.let { reconnectHandler.removeCallbacks(it) }
        reconnectJob = null
    }

    private fun setStatus(text: String, colorHex: String) {
        b.tvStreamStatus.text = text
        b.tvStreamStatus.setTextColor(Color.parseColor(colorHex))
    }

    // 카메라 죽음/끊김 시: 얼어붙은 마지막 프레임을 어두운 막으로 덮어 '정상처럼' 보이지 않게 (안전 필수)
    private fun coverDead() {
        b.imgSnapshot.setImageBitmap(null)
        b.imgSnapshot.setBackgroundColor(0xF00A0A0A.toInt())
        b.imgSnapshot.alpha = 1f
        b.imgSnapshot.visibility = View.VISIBLE
        b.imgSnapshot.bringToFront()
        b.tvStreamStatus.bringToFront(); b.dotPlayer.bringToFront()
    }

    // 연결 중 검은화면 방지: 카메라 정지영상(JPEG)을 즉시 받아 배경에 깔기
    private fun snapshotUrl(brand: CameraBrand, ip: String): String? = when (brand) {
        CameraBrand.DAHUA     -> "http://$ip/cgi-bin/snapshot.cgi?channel=1"
        CameraBrand.HIKVISION -> "http://$ip/ISAPI/Streaming/channels/101/picture"
        CameraBrand.HANWHA    -> "https://$ip/stw-cgi/video.cgi?msubmenu=snapshot&action=view&Channel=0"
        else -> null
    }
    private fun loadLiveSnapshot(brand: CameraBrand, ip: String, user: String, pass: String) {
        val url = snapshotUrl(brand, ip) ?: return
        Thread {
            try {
                val cache = java.util.concurrent.ConcurrentHashMap<String, com.burgstaller.okhttp.digest.CachingAuthenticator>()
                val creds = com.burgstaller.okhttp.digest.Credentials(user.trim(), pass.trim())
                val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                    .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                    .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds)).build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, cache))
                    .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(cache)).trustSelfSignedCam().build()
                client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { r ->
                    val bytes = r.body?.bytes() ?: return@Thread
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                    runOnUiThread {
                        if (!isPlaying && b.imgSnapshot.drawable == null) {
                            b.imgSnapshot.setImageBitmap(bmp); b.imgSnapshot.alpha = 1f
                            b.imgSnapshot.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    // ── 줌 사용 로깅(지속시간) ──
    private var zoomStartMs = 0L
    private var zoomDir = "in"
    private fun zoomStart(inZoom: Boolean) {
        ptz?.zoom(inZoom)
        if (zoomStartMs == 0L) { zoomStartMs = android.os.SystemClock.elapsedRealtime(); zoomDir = if (inZoom) "in" else "out" }
    }
    private fun zoomEnd() {
        ptz?.zoomStop()
        if (zoomStartMs != 0L) {
            UsageLogger.log("zoom", "dir" to zoomDir,
                "ms" to (android.os.SystemClock.elapsedRealtime() - zoomStartMs), "cam" to channelLabel)
            zoomStartMs = 0L
        }
    }

    private fun setupDoubleTap() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { goBack(); return true }
        })
        // 좌우 스와이프 → 프리셋 순환 (수동 감지, 왼쪽=다음·오른쪽=이전)
        var downX = 0f; var downY = 0f; var downT = 0L
        b.touchOverlay.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = ev.eventTime }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX; val dy = ev.y - downY; val dt = ev.eventTime - downT
                    if (ptz != null && dt < 600 &&
                        kotlin.math.abs(dx) > 140f &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        cyclePreset(dx < 0)
                    }
                }
            }
            true
        }
    }

    // 1~9번 프리셋만 순환 (하이크비전 공장 프리셋이 많아도 앱은 1~9만 사용). 못 가져왔으면 1~9
    // 순환 목록 우선순위: ①앱에서 저장한 프리셋(길게눌러 저장 — 동기·항상 정확) ②카메라 조회/캐시 ③1~9
    private fun presetCycleList(): List<Int> {
        val saved = (1..9).filter {
            getSharedPreferences("preset_names", Context.MODE_PRIVATE).contains(presetKey(it))
        }
        if (saved.isNotEmpty()) return saved
        val nine = cameraPresets.filter { it in 1..9 }
        return if (nine.isNotEmpty()) nine else (1..9).toList()
    }

    private fun cyclePreset(forward: Boolean) {
        if (ptz == null) return
        val list = presetCycleList()
        if (list.isEmpty()) return
        presetIdx = when {
            presetIdx < 0 -> 0
            forward       -> (presetIdx + 1) % list.size
            else          -> (presetIdx - 1 + list.size) % list.size
        }
        gotoPresetWithFeedback(list[presetIdx])
    }

    private fun gotoPresetWithFeedback(id: Int) {
        ptz?.gotoPreset(id)
        UsageLogger.log("preset", "id" to id, "cam" to channelLabel)
        val labelText = "프리셋 $id"   // 항상 "프리셋 N" 통일
        showCmdCard("📍  $labelText")
        tts?.speak(labelText, TextToSpeech.QUEUE_FLUSH, null, "preset")
    }

    // 반투명 카드 피드백 (1.1초 후 사라짐)
    private fun showCmdCard(text: String) {
        b.cmdCardPlayer.animate().cancel()
        b.cmdCardPlayer.text = text
        b.cmdCardPlayer.alpha = 1f
        b.cmdCardPlayer.visibility = View.VISIBLE
        cardHandler.removeCallbacksAndMessages(null)
        cardHandler.postDelayed({
            b.cmdCardPlayer.animate().alpha(0f).setDuration(400)
                .withEndAction { b.cmdCardPlayer.visibility = View.GONE }.start()
        }, 1100L)
    }

    private fun togglePanel() {
        // 우측 PTZ 패널을 접으면 영상이 화면 전체 폭으로 넓어짐
        val hide = b.ptzPanel.visibility == View.VISIBLE
        b.ptzPanel.visibility = if (hide) View.GONE else View.VISIBLE
        b.btnWide.text = if (hide) "◧ 패널" else "⛶ 넓게"
    }

    private fun goBack() { finish(); overridePendingTransition(0, 0) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    private fun presetKey(id: Int) = "preset_${channelLabel}_$id"

    // 카메라 프리셋 목록 캐시 (조회 실패/지연 시에도 마지막 정상 목록 유지 → 1~9 폴백 방지)
    private fun loadCachedPresets(): List<Int> =
        getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .getString("presetlist_$channelLabel", "")
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..9 } ?: emptyList()
    private fun saveCachedPresets(list: List<Int>) =
        getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .edit().putString("presetlist_$channelLabel", list.joinToString(",")).apply()

    private fun loadPresetNames() {
        val prefs = getSharedPreferences("preset_names", Context.MODE_PRIVATE)
        presetBtns.forEach { (btn, id) ->
            val name = prefs.getString(presetKey(id), null)
            updatePresetBtn(btn, id, name)
        }
    }

    private fun updatePresetBtn(btn: android.widget.Button, id: Int, name: String?) {
        if (name.isNullOrBlank()) {
            // 이름 없음 → 숫자만 크게
            btn.text = id.toString()
            btn.textSize = 22f
        } else {
            // 이름 있음 → 이름만 크게 표시
            btn.text = name
            btn.textSize = 15f
        }
    }

    private fun savePresetName(id: Int, name: String) {
        getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .edit().putString(presetKey(id), name).apply()
        val btn = presetBtns.find { it.second == id }?.first ?: return
        updatePresetBtn(btn, id, name.ifBlank { null })
    }

    private fun showPresetNameDialog(id: Int) {
        val current = getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .getString(presetKey(id), "") ?: ""
        val input = EditText(this).apply {
            setText(current); hint = "프리셋 $id 이름"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E22"))
            setPadding(16, 12, 16, 12); textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("프리셋 $id 설정")
            .setMessage("현재 카메라 위치를 저장합니다")
            .setView(input)
            .setPositiveButton("위치 저장") { _, _ ->
                ptz?.setPreset(id)
                savePresetName(id, input.text.toString().trim())
                Toast.makeText(this, "프리셋 $id 저장됨", Toast.LENGTH_SHORT).show()
                b.root.postDelayed({ ptz?.fetchPresets { if (it.isNotEmpty()) { cameraPresets = it; saveCachedPresets(it) } } }, 800L)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun setupControls() {
        // PTZ 패널 좌우 이동 (채널별 저장)
        val panelKey = "ptz_panel_right_$channelLabel"
        val prefs = getSharedPreferences("ptz_panel", Context.MODE_PRIVATE)
        var isPanelRight = prefs.getBoolean(panelKey, true)

        fun applyPanelPosition(right: Boolean) {
            val parent = b.root as? android.widget.LinearLayout ?: return
            parent.removeView(b.ptzPanel)
            if (right) {
                parent.addView(b.ptzPanel)
                b.btnPanelMove.text = "◀ 좌측"
            } else {
                parent.addView(b.ptzPanel, 0)
                b.btnPanelMove.text = "우측 ▶"
            }
        }

        // 저장된 위치 적용
        applyPanelPosition(isPanelRight)

        b.btnPanelMove.setOnClickListener {
            isPanelRight = !isPanelRight
            applyPanelPosition(isPanelRight)
            prefs.edit().putBoolean(panelKey, isPanelRight).apply()
        }
        b.btnWide.setOnClickListener { togglePanel() }

        // 방향키 ON/OFF 채널별 저장
        val ptzKey = "ptz_dpad_on_$channelLabel"
        val ptzPrefs = getSharedPreferences("ptz_panel", Context.MODE_PRIVATE)
        val savedPtzOn = ptzPrefs.getBoolean(ptzKey, true)
        b.swPtz.isChecked = savedPtzOn
        b.ptzArea.visibility = if (savedPtzOn) View.VISIBLE else View.INVISIBLE

        b.ptzToggleRow.setOnClickListener {
            val newOn = !b.swPtz.isChecked
            b.swPtz.isChecked = newOn
            b.ptzArea.visibility = if (newOn) View.VISIBLE else View.INVISIBLE
            ptzPrefs.edit().putBoolean(ptzKey, newOn).apply()
        }
        b.swPtz.setOnCheckedChangeListener { _, on ->
            b.ptzArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
            ptzPrefs.edit().putBoolean(ptzKey, on).apply()
        }
        setupDpad(b.btnN,  0,-60); setupDpad(b.btnS, 0,  60)
        setupDpad(b.btnW,-60,  0); setupDpad(b.btnE,60,   0)
        setupDpad(b.btnNW,-50,-50); setupDpad(b.btnNE,50,-50)
        setupDpad(b.btnSW,-50, 50); setupDpad(b.btnSE,50, 50)
        b.btnStop.setOnClickListener { ptz?.stop() }
        b.btnZoomIn.setOnTouchListener { _, ev ->
            when(ev.action) {
                MotionEvent.ACTION_DOWN -> zoomStart(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoomEnd()
            }; true }
        b.btnZoomOut.setOnTouchListener { _, ev ->
            when(ev.action) {
                MotionEvent.ACTION_DOWN -> zoomStart(false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoomEnd()
            }; true }
        presetBtns.forEach { (btn, id) ->
            btn.setOnClickListener { gotoPresetWithFeedback(id) }   // 번호 버튼도 카드+음성 피드백
            btn.setOnLongClickListener { showPresetNameDialog(id); true }
        }
    }

    private fun setupDpad(btn: Button, pan: Int, tilt: Int) {
        btn.setOnTouchListener { v, ev ->
            when(ev.action) {
                MotionEvent.ACTION_DOWN -> { ptz?.move(pan, tilt); v.alpha = 0.5f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ptz?.stop(); v.alpha = 1f }
            }; true
        }
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        reconnectHandler.removeCallbacks(liveWatch); reconnectHandler.postDelayed(liveWatch, 2000L)
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
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onStop() {
        super.onStop()
        cancelReconnect()
        reconnectHandler.removeCallbacks(liveWatch)
        osdHandler.removeCallbacks(osdRunnable)
        player?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewStartMs != 0L) {
            UsageLogger.log("view_close", "cam" to channelLabel,
                "sec" to (android.os.SystemClock.elapsedRealtime() - viewStartMs) / 1000L)
            viewStartMs = 0L
        }
        cancelReconnect()
        reconnectHandler.removeCallbacks(liveWatch)
        osdHandler.removeCallbacks(osdRunnable)
        player?.detachViews(); player?.release(); player = null
        libVLC = null   // 전역 공유 인스턴스이므로 release 하지 않음 (참조만 해제)
        cardHandler.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts = null
        b.imgSnapshot.setImageBitmap(null)
    }
}