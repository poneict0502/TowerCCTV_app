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
import com.pone.towerccctv.controller.PtzController
import com.pone.towerccctv.databinding.ActivityPlayerBinding
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

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectJob: Runnable? = null
    private var reconnectCount = 0
    private val MAX_RECONNECT = 10
    private var rtspUrlCached = ""
    private var isPlaying = false

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
        channelLabel   = label
        rtspUrlCached  = rtspUrl

        b.lblPlayer.text = label
        b.ipPlayer.text  = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
        setStatus("연결 중...", "#FFB300")

        val ocrOn = OcrSettings.isOcrEnabled(this)
        b.osdWeightPlayer.visibility = if (ocrOn) View.VISIBLE else View.GONE

        // 스냅샷 배경
        intent.getStringExtra("snapPath")?.let { path ->
            try {
                BitmapFactory.decodeFile(path)?.let { bmp ->
                    b.imgSnapshot.setImageBitmap(bmp)
                    b.imgSnapshot.alpha = 1f
                    b.imgSnapshot.visibility = View.VISIBLE
                }
            } catch (e: Exception) { }
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
                username, password, true, deviceType)
            ptz = PtzController(ch)
            ptz?.fetchPresets { cameraPresets = it }   // 스와이프 순환용 프리셋 목록 로드
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
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        if (reconnectCount >= MAX_RECONNECT) {
            setStatus("연결 실패 (재시도 중단)", "#F44336"); return
        }
        reconnectCount++
        val delay = minOf(reconnectCount * 3000L, 15000L)
        setStatus("재연결 중... ($reconnectCount/$MAX_RECONNECT) ${delay/1000}초 후", "#FF9800")
        val job = Runnable {
            if (isDestroyed || isFinishing) return@Runnable
            setStatus("재연결 시도 중...", "#FFB300")
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
    private fun presetCycleList(): List<Int> {
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
                MotionEvent.ACTION_DOWN -> ptz?.zoom(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true }
        b.btnZoomOut.setOnTouchListener { _, ev ->
            when(ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
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
        osdHandler.removeCallbacks(osdRunnable)
        player?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelReconnect()
        osdHandler.removeCallbacks(osdRunnable)
        player?.detachViews(); player?.release(); player = null
        libVLC = null   // 전역 공유 인스턴스이므로 release 하지 않음 (참조만 해제)
        cardHandler.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts = null
        b.imgSnapshot.setImageBitmap(null)
    }
}