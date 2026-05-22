package com.pone.towerccctv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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

        b.lblPlayer.text = label
        b.ipPlayer.text  = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")

        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=300"))

        // 즉시 재생 시작
        val p = MediaPlayer(libVLC)
        player = p
        p.attachViews(b.vlcPlayer, null, true, false)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(rtspUrl))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=300")
        media.addOption(":rtsp-tcp")
        p.media = media
        media.release()

        p.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        b.tvLive.visibility = View.VISIBLE
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_green)
                    }
                    MediaPlayer.Event.Buffering -> {
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_yellow)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        b.tvLive.visibility = View.GONE
                        b.dotPlayer.setBackgroundResource(R.drawable.dot_red)
                    }
                }
            }
        }
        p.play()

        // PTZ 설정
        if (hasPtz) {
            val ch = Channel(0, label, rtspUrl, httpBase, ptzChannel,
                username, password, true, deviceType)
            ptz = PtzController(ch)
            b.tvPtzHint.text = "PTZ 활성 ($label)"
        } else {
            b.tvPtzHint.text = "$label (PTZ 없음)"
            b.ptzArea.alpha = 0.3f
            b.ptzArea.isEnabled = false
            b.btnZoomIn.alpha = 0.3f
            b.btnZoomOut.alpha = 0.3f
        }

        setupControls()

        // 그리드 복귀 버튼
        b.btnGridBack.setOnClickListener { goBack() }

        // 녹화 재생
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

    private fun goBack() {
        finish()
        overridePendingTransition(0, 0)  // 즉시 복귀
    }

    override fun onBackPressed() {
        goBack()
    }

    private fun setupControls() {
        // 방향키 스위치
        b.swPtz.setOnCheckedChangeListener { _, on ->
            b.ptzArea.visibility = if (on) View.VISIBLE else View.GONE
        }

        // D-pad
        setupDpad(b.btnN,  0,  -60); setupDpad(b.btnS,  0,   60)
        setupDpad(b.btnW, -60,   0); setupDpad(b.btnE,  60,   0)
        setupDpad(b.btnNW,-50, -50); setupDpad(b.btnNE, 50, -50)
        setupDpad(b.btnSW,-50,  50); setupDpad(b.btnSE, 50,  50)
        b.btnStop.setOnClickListener { ptz?.stop() }

        // 줌
        b.btnZoomIn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true
        }
        b.btnZoomOut.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true
        }

        // 프리셋
        listOf(b.btnP1 to 1, b.btnP2 to 2, b.btnP3 to 3, b.btnP4 to 4, b.btnP5 to 5,
               b.btnP6 to 6, b.btnP7 to 7, b.btnP8 to 8, b.btnP9 to 9).forEach { (btn, id) ->
            btn.setOnClickListener { ptz?.gotoPreset(id) }
            btn.setOnLongClickListener {
                ptz?.setPreset(id)
                Toast.makeText(this, "프리셋 $id 저장", Toast.LENGTH_SHORT).show()
                btn.animate().alpha(0.3f).setDuration(80)
                    .withEndAction { btn.animate().alpha(1f).setDuration(200).start() }.start()
                true
            }
        }
    }

    private fun setupDpad(btn: Button, pan: Int, tilt: Int) {
        btn.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ptz?.move(pan, tilt); v.alpha = 0.5f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ptz?.stop(); v.alpha = 1f }
            }; true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.detachViews()
        player?.release()
        player = null
        libVLC?.release()
        libVLC = null
    }
}
