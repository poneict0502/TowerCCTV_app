package com.pone.towerccctv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var ptz: PtzController? = null
    private var channelLabel = ""

    // 프리셋 버튼 목록
    private lateinit var presetBtns: List<Pair<Button, Int>>

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

        b.lblPlayer.text = label
        b.ipPlayer.text  = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
        setStatus("연결 중...", "#FFB300")

        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=300"))

        // 즉시 재생
        val p = MediaPlayer(libVLC)
        player = p
        p.attachViews(b.vlcPlayer, null, true, false)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(rtspUrl))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=300")
        media.addOption(":rtsp-tcp")
        p.media = media; media.release()

        p.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        // 연결 성공 = LIVE 표시 (버퍼링 이후 정상 재생)
                        setStatus("● LIVE", "#4CAF50")
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_green)
                    }
                    MediaPlayer.Event.Buffering -> {
                        // 최초 연결 중 또는 재연결 중 (오류 아님)
                        if (b.tvStreamStatus.text != "● LIVE") {
                            setStatus("연결 중...", "#FFB300")
                        }
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_yellow)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        setStatus("연결 실패 — 재시도 중", "#F44336")
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_red)
                    }
                    MediaPlayer.Event.Stopped -> {
                        setStatus("중지됨", "#888888")
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_gray)
                    }
                }
            }
        }
        p.play()

        // PTZ
        if (hasPtz) {
            val ch = Channel(0, label, rtspUrl, httpBase, ptzChannel,
                username, password, true, deviceType)
            ptz = PtzController(ch)
        } else {
            b.ptzArea.alpha = 0.3f
            b.btnZoomIn.alpha = 0.3f; b.btnZoomIn.isEnabled = false
            b.btnZoomOut.alpha = 0.3f; b.btnZoomOut.isEnabled = false
        }

        // 프리셋 버튼 목록
        presetBtns = listOf(b.btnP1 to 1, b.btnP2 to 2, b.btnP3 to 3,
            b.btnP4 to 4, b.btnP5 to 5, b.btnP6 to 6,
            b.btnP7 to 7, b.btnP8 to 8, b.btnP9 to 9)

        loadPresetNames()
        setupControls()
        setupDoubleTap()

        // 녹화 재생
        b.btnPlayback.isEnabled = deviceType == DeviceType.CAMERA
        b.btnPlayback.setOnClickListener {
            startActivity(Intent(this, PlaybackActivity::class.java).apply {
                putExtra("httpBase", httpBase); putExtra("username", username)
                putExtra("password", password); putExtra("label", label)
            })
        }
    }

    // ─── 스트림 상태 표시 ───
    private fun setStatus(text: String, colorHex: String) {
        b.tvStreamStatus.text = text
        b.tvStreamStatus.setTextColor(Color.parseColor(colorHex))
    }

    // ─── 더블탭 = 그리드 복귀 ───
    private fun setupDoubleTap() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { goBack(); return true }
        })
        b.touchOverlay.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
    }

    private fun goBack() { finish(); overridePendingTransition(0, 0) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    // ─── 프리셋 이름 저장/불러오기 ───
    private fun presetKey(id: Int) = "preset_${channelLabel}_$id"

    private fun loadPresetNames() {
        val prefs = getSharedPreferences("preset_names", Context.MODE_PRIVATE)
        presetBtns.forEach { (btn, id) ->
            val name = prefs.getString(presetKey(id), null)
            if (name.isNullOrBlank()) {
                // 이름 없음: 번호만 크게
                btn.text = id.toString()
                btn.textSize = 20f
            } else {
                // 이름 있음: "번호\n이름" - 고정 크기 (버튼 높이 변경 없음)
                btn.text = "$id\n$name"
                btn.textSize = 11f
            }
        }
    }

    private fun savePresetName(id: Int, name: String) {
        getSharedPreferences("preset_names", Context.MODE_PRIVATE)
            .edit().putString(presetKey(id), name).apply()
        val btn = presetBtns.find { it.second == id }?.first ?: return
        if (name.isBlank()) {
            btn.text = id.toString()
            btn.textSize = 20f
        } else {
            btn.text = "$id\n$name"
            btn.textSize = 11f
        }
    }

    // ─── 컨트롤 설정 ───
    private fun setupControls() {
        // 방향키 ON/OFF: 행 전체 터치 → 스위치 토글 (우측 끝 터치 불편 해결)
        b.ptzToggleRow.setOnClickListener {
            b.swPtz.isChecked = !b.swPtz.isChecked
        }
        b.swPtz.setOnCheckedChangeListener { _, on ->
            b.ptzArea.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }

        setupDpad(b.btnN,  0, -60); setupDpad(b.btnS,  0,  60)
        setupDpad(b.btnW, -60,  0); setupDpad(b.btnE,  60,  0)
        setupDpad(b.btnNW,-50,-50); setupDpad(b.btnNE, 50,-50)
        setupDpad(b.btnSW,-50, 50); setupDpad(b.btnSE, 50, 50)
        b.btnStop.setOnClickListener { ptz?.stop() }

        b.btnZoomIn.setOnTouchListener  { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true }
        b.btnZoomOut.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true }

        // 프리셋: 탭=이동, 길게=이름 편집 다이얼로그
        presetBtns.forEach { (btn, id) ->
            btn.setOnClickListener { ptz?.gotoPreset(id) }
            btn.setOnLongClickListener {
                showPresetNameDialog(id)
                true
            }
        }
    }

    private fun showPresetNameDialog(id: Int) {
        val prefs = getSharedPreferences("preset_names", Context.MODE_PRIVATE)
        val current = prefs.getString(presetKey(id), "") ?: ""

        val input = EditText(this).apply {
            setText(current)
            hint = "프리셋 $id 이름 (예: 타워북쪽, 홈위치)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E22"))
            setPadding(16, 12, 16, 12)
            // 한글 입력 지원
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 16f
            setSingleLine(false)
        }

        AlertDialog.Builder(this)
            .setTitle("프리셋 $id 이름 설정")
            .setMessage("탭하면 이동, 이름은 버튼에 표시됩니다")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString().trim()
                ptz?.setPreset(id)
                savePresetName(id, name)
                Toast.makeText(this, "프리셋 $id '${name.ifBlank { id.toString() }}' 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupDpad(btn: Button, pan: Int, tilt: Int) {
        btn.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ptz?.move(pan, tilt); v.alpha = 0.5f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ptz?.stop(); v.alpha = 1f }
            }; true
        }
    }

    override fun onStop()    { super.onStop(); player?.stop() }
    override fun onDestroy() {
        super.onDestroy()
        player?.detachViews(); player?.release(); player = null
        libVLC?.release(); libVLC = null
    }
}
