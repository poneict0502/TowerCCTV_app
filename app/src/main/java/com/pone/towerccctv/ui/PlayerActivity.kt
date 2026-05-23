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

    // OSD Handler (콜백 방식으로 대체, 최초 1회만 초기화)
    private val osdHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

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
        b.osdWindPlayer.visibility   = if (ocrOn) View.VISIBLE else View.GONE
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

        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=300"))

        if (hasPtz) {
            val ch = Channel(0, label, rtspUrl, httpBase, ptzChannel,
                username, password, true, deviceType)
            ptz = PtzController(ch)
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

        val isCh4 = httpBase.contains("192.168.0.104") || label == "CH4"
        if (isCh4) {
            b.btnPlayback.text = "📐  ROI 영역 설정"
            b.btnPlayback.isEnabled = true
            b.btnPlayback.setOnClickListener { showRoiSetup() }
        } else {
            b.btnPlayback.isEnabled = deviceType == DeviceType.CAMERA
            b.btnPlayback.setOnClickListener {
                startActivity(Intent(this, PlaybackActivity::class.java).apply {
                    putExtra("httpBase", httpBase)
                    putExtra("username", username)
                    putExtra("password", password)
                    putExtra("label", label)
                })
            }
        }

        // OSD 갱신 시작 (0.3초 간격)
        if (ocrOn) {
            osdHandler.post(osdRunnable)
        }
    }

    // ── OSD 갱신 ──
    fun refreshOsd() {
        val wind   = OcrSettings.getLatestWind(this)
        val weight = OcrSettings.getLatestWeight(this)
        val windAlert   = OcrSettings.isLatestWindAlert(this)
        val weightAlert = OcrSettings.isLatestWeightAlert(this)
        val isTon = OcrSettings.isWeightTon(this)

        // 값 있으면 표시, 없으면 -- (타임아웃 없음 - 마지막 값 유지)
        b.osdWindPlayer.text = if (wind != null) "풍속: %.1f m/s".format(wind) else "풍속: --"
        b.osdWeightPlayer.text = if (weight != null) {
            if (isTon) "중량: %.2f t".format(weight) else "중량: %.1f kg".format(weight)
        } else "중량: --"

        b.osdWindPlayer.setTextColor(
            if (windAlert) Color.parseColor("#FF4444") else Color.WHITE)
        b.osdWeightPlayer.setTextColor(
            if (weightAlert) Color.parseColor("#FF4444") else Color.WHITE)
    }

    // ── ROI 설정 (전체화면 오버레이, Dialog 없음) ──
    private fun showRoiSetup() {
        // HTTP 스냅샷을 배경으로 사용 (영상과 좌표 일치)
        val httpBase = intent.getStringExtra("httpBase") ?: ""
        val username = intent.getStringExtra("username") ?: "admin"
        val password = intent.getStringExtra("password") ?: ""
        val snapshotUrl = "$httpBase/ISAPI/Streaming/channels/101/picture"

        // 비동기로 스냅샷 가져오기
        android.os.AsyncTask.execute {
            val frame = try {
                val authCache = java.util.concurrent.ConcurrentHashMap<com.burgstaller.okhttp.digest.CachingAuthenticator, com.burgstaller.okhttp.digest.CachingAuthenticator>()
                val credentials = com.burgstaller.okhttp.digest.Credentials(username, password)
                val authenticator = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                    .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(credentials))
                    .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(credentials))
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder().url(snapshotUrl)
                    .header("Authorization", okhttp3.Credentials.basic(username, password))
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.body?.bytes()?.let {
                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                }
            } catch (e: Exception) { captureCurrentFrame() }
            runOnUiThread { showRoiOverlay(frame) }
        }
    }

    private fun showRoiSetupOld() {
        val frame = captureCurrentFrame()

        showRoiOverlay(null)
    }

    private fun showRoiOverlay(frame: android.graphics.Bitmap?) {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 배경: 캡처 프레임 또는 검정
        val bgImage = ImageView(this).apply {
            frame?.let { setImageBitmap(it) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 1.0f  // 원본 밝기 유지
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.bottomMargin = 120 }
        }
        overlay.addView(bgImage)

        // ROI 드로잉 레이어
        val roiView = RoiOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.bottomMargin = 120 }
            loadSavedRois(this@PlayerActivity)
        }
        overlay.addView(roiView)

        // 상단 안내 텍스트
        val tvGuide = TextView(this).apply {
            text = "아래 버튼 선택 후 화면에서 드래그하여 영역 설정"
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.parseColor("#CC000000"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        overlay.addView(tvGuide)

        // 하단 버튼 바 (Dialog 없이 → 드래그 가능!)
        val btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(16, 12, 16, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 120
            ).also { it.gravity = Gravity.BOTTOM }
        }

        fun makeBtn(text: String, color: String, action: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8 }
            setOnClickListener { action() }
        }

        val btnWind = makeBtn("🌬 풍속 영역", "#1A3A8F") {
            roiView.currentMode = RoiOverlayView.RoiMode.WIND
            roiView.onRoiSet = { _, roi ->
                OcrSettings.setWindRoi(this, roi)
                Toast.makeText(this, "풍속 ROI 저장됨", Toast.LENGTH_SHORT).show()
            }
            tvGuide.text = "🌬 풍속 영역을 드래그하세요 (파란색)"
            Toast.makeText(this, "풍속 영역을 드래그하세요", Toast.LENGTH_SHORT).show()
        }

        val btnWeight = makeBtn("⚖ 중량 영역", "#8F4A00") {
            roiView.currentMode = RoiOverlayView.RoiMode.WEIGHT
            roiView.onRoiSet = { _, roi ->
                OcrSettings.setWeightRoi(this, roi)
                Toast.makeText(this, "중량 ROI 저장됨", Toast.LENGTH_SHORT).show()
            }
            tvGuide.text = "⚖ 중량 영역을 드래그하세요 (주황색)"
            Toast.makeText(this, "중량 영역을 드래그하세요", Toast.LENGTH_SHORT).show()
        }

        val btnDone = makeBtn("✅ 저장 완료", "#1A6A2A") {
            root.removeView(overlay)
            frame?.recycle()
            Toast.makeText(this, "ROI 설정 저장 완료!", Toast.LENGTH_SHORT).show()
        }

        val btnCancel = makeBtn("✕ 취소", "#4A1A1A") {
            root.removeView(overlay)
            frame?.recycle()
        }

        btnBar.addView(btnWind); btnBar.addView(btnWeight)
        btnBar.addView(btnDone); btnBar.addView(btnCancel)
        overlay.addView(btnBar)

        root.addView(overlay)
    }

    private fun captureCurrentFrame(): Bitmap? {
        return try {
            for (i in 0 until b.vlcPlayer.childCount) {
                val child = b.vlcPlayer.getChildAt(i)
                if (child is TextureView) return child.bitmap
            }
            null
        } catch (e: Exception) { null }
    }

    // ── 재생 시작 ──
    private fun startPlaying(url: String) {
        player?.stop(); player?.detachViews(); player?.release()
        val p = MediaPlayer(libVLC)
        player = p
        p.attachViews(b.vlcPlayer, null, true, false)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(url))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=300"); media.addOption(":rtsp-tcp")
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
        b.touchOverlay.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
    }

    private fun goBack() { finish(); overridePendingTransition(0, 0) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    private fun presetKey(id: Int) = "preset_${channelLabel}_$id"

    private fun loadPresetNames() {
        val prefs = getSharedPreferences("preset_names", Context.MODE_PRIVATE)
        presetBtns.forEach { (btn, id) ->
            val name = prefs.getString(presetKey(id), null)
            if (name.isNullOrBlank()) { btn.text = id.toString(); btn.textSize = 20f }
            else { btn.text = "$id\n$name"; btn.textSize = 11f }
        }
    }

    private fun savePresetName(id: Int, name: String) {
        getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .edit().putString(presetKey(id), name).apply()
        val btn = presetBtns.find { it.second == id }?.first ?: return
        if (name.isBlank()) { btn.text = id.toString(); btn.textSize = 20f }
        else { btn.text = "$id\n$name"; btn.textSize = 11f }
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
        b.ptzToggleRow.setOnClickListener { b.swPtz.isChecked = !b.swPtz.isChecked }
        b.swPtz.setOnCheckedChangeListener { _, on ->
            b.ptzArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        setupDpad(b.btnN,  0,-60); setupDpad(b.btnS, 0,  60)
        setupDpad(b.btnW,-60,  0); setupDpad(b.btnE,60,   0)
        setupDpad(b.btnNW,-50,-50); setupDpad(b.btnNE,50,-50)
        setupDpad(b.btnSW,-50, 50); setupDpad(b.btnSE,50, 50)
        b.btnStop.setOnClickListener { ptz?.stop() }
        b.btnZoomIn.setOnTouchListener  { _, ev ->
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
            btn.setOnClickListener { ptz?.gotoPreset(id) }
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
        libVLC?.release(); libVLC = null
        b.imgSnapshot.setImageBitmap(null)
    }
}
