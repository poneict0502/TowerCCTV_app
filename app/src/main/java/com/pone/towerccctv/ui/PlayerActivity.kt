package com.pone.towerccctv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private var roiOverlay: RoiOverlayView? = null

    // OSD 갱신 타이머
    private val osdHandler = Handler(Looper.getMainLooper())
    private val osdRunnable = object : Runnable {
        override fun run() {
            refreshOsd()
            osdHandler.postDelayed(this, 800L)
        }
    }

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

        // 초기 OSD 숨김 (OCR 미활성시)
        b.osdWindPlayer.visibility   = if (OcrSettings.isOcrEnabled(this)) View.VISIBLE else View.GONE
        b.osdWeightPlayer.visibility = if (OcrSettings.isOcrEnabled(this)) View.VISIBLE else View.GONE

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

        // CH4: ROI 설정 버튼, 일반: 녹화 재생
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

        // OSD 갱신 시작
        if (OcrSettings.isOcrEnabled(this)) osdHandler.post(osdRunnable)
    }

    // ── OSD 갱신 (SharedPreferences에서 읽기) ──
    private fun refreshOsd() {
        val wind   = OcrSettings.getLatestWind(this)
        val weight = OcrSettings.getLatestWeight(this)
        val windAlert   = OcrSettings.isLatestWindAlert(this)
        val weightAlert = OcrSettings.isLatestWeightAlert(this)
        val updatedAt   = OcrSettings.getLatestTime(this)

        // 5초 이상 업데이트 없으면 회색 (OCR 미작동)
        val stale = System.currentTimeMillis() - updatedAt > 5000L

        val windTxt   = if (!stale && wind   != null) "🌬  %.1f".format(wind)   else "🌬  --"
        val weightTxt = if (!stale && weight != null) "⚖  %.0f".format(weight) else "⚖  --"

        b.osdWindPlayer.text   = windTxt
        b.osdWeightPlayer.text = weightTxt
        b.osdWindPlayer.setTextColor(
            if (windAlert && !stale) Color.parseColor("#FF4444") else Color.WHITE)
        b.osdWeightPlayer.setTextColor(
            if (weightAlert && !stale) Color.parseColor("#FF4444") else Color.WHITE)
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
        media.addOption(":network-caching=300")
        media.addOption(":rtsp-tcp")
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

    // ── 자동 재연결 ──
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

    // ── 더블탭 = 그리드 복귀 ──
    private fun setupDoubleTap() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { goBack(); return true }
        })
        b.touchOverlay.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
    }

    private fun goBack() { finish(); overridePendingTransition(0, 0) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    // ── ROI 설정 (드래그) ──
    private fun showRoiSetup() {
        val overlay = RoiOverlayView(this)
        overlay.setBackgroundColor(Color.parseColor("#55000000"))
        overlay.loadSavedRois(this)
        roiOverlay = overlay

        val videoParent = b.vlcPlayer.parent as ViewGroup
        videoParent.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ★ touchOverlay 비활성화 → ROI 드래그 가능하게
        b.touchOverlay.visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("📐 ROI 영역 설정")
            .setMessage("버튼 선택 후 화면에서 드래그하세요\n파란색=풍속  주황색=중량")
            .setItems(arrayOf(
                "🌬  풍속 영역 드래그",
                "⚖  중량 영역 드래그",
                "✅  완료"
            )) { _, which ->
                when (which) {
                    0 -> {
                        overlay.currentMode = RoiOverlayView.RoiMode.WIND
                        overlay.onRoiSet = { _, roi ->
                            OcrSettings.setWindRoi(this, roi)
                            Toast.makeText(this, "풍속 ROI 저장됨", Toast.LENGTH_SHORT).show()
                        }
                        Toast.makeText(this, "풍속 영역을 드래그하세요", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        overlay.currentMode = RoiOverlayView.RoiMode.WEIGHT
                        overlay.onRoiSet = { _, roi ->
                            OcrSettings.setWeightRoi(this, roi)
                            Toast.makeText(this, "중량 ROI 저장됨", Toast.LENGTH_SHORT).show()
                        }
                        Toast.makeText(this, "중량 영역을 드래그하세요", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        overlay.currentMode = RoiOverlayView.RoiMode.NONE
                        videoParent.removeView(overlay)
                        roiOverlay = null
                        // ★ touchOverlay 복원
                        b.touchOverlay.visibility = View.VISIBLE
                        Toast.makeText(this, "ROI 설정 완료!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setOnCancelListener {
                // 다이얼로그 닫혀도 오버레이 유지 (드래그 계속 가능)
            }
            .show()
    }

    // ── 프리셋 ──
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

    // ── 컨트롤 ──
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
        roiOverlay?.let { (b.vlcPlayer.parent as? ViewGroup)?.removeView(it) }
        player?.detachViews(); player?.release(); player = null
        libVLC?.release(); libVLC = null
        b.imgSnapshot.setImageBitmap(null)
    }
}
