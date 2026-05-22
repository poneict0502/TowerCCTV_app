package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pone.towerccctv.R
import com.pone.towerccctv.controller.PtzController
import com.pone.towerccctv.databinding.ActivityMainBinding
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var channels = listOf<Channel>()
    private var libVLC: LibVLC? = null
    private val gridPlayers = arrayOfNulls<MediaPlayer>(4)
    private var singlePlayer: MediaPlayer? = null
    private var ptz: PtzController? = null
    private var selectedIndex = -1
    private var isDarkTheme = true

    private lateinit var cellList:  List<FrameLayout>
    private lateinit var vlcList:   List<VLCVideoLayout>
    private lateinit var dotList:   List<View>
    private lateinit var lblList:   List<TextView>
    private lateinit var ipList:    List<TextView>
    private lateinit var touchList: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        cellList  = listOf(b.cell0,  b.cell1,  b.cell2,  b.cell3)
        vlcList   = listOf(b.vlc0,   b.vlc1,   b.vlc2,   b.vlc3)
        dotList   = listOf(b.dot0,   b.dot1,   b.dot2,   b.dot3)
        lblList   = listOf(b.lbl0,   b.lbl1,   b.lbl2,   b.lbl3)
        ipList    = listOf(b.ip0,    b.ip1,    b.ip2,    b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        // ★ TextureView 사용 (SurfaceView 잔상 완전 해결)
        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=500"))

        // 단독 모드 더블탭 → 그리드 복귀
        val doubleTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                enterGridMode(); return true
            }
        })
        b.touchMain.setOnTouchListener { _, ev -> doubleTap.onTouchEvent(ev); true }

        setupControls()
        applyTheme(true)
        enterGridMode()
    }

    override fun onResume() {
        super.onResume()
        if (selectedIndex < 0) enterGridMode()
    }

    private fun enterGridMode() {
        // 블랙 커버로 즉시 가림
        b.blackCover.visibility = View.VISIBLE
        b.blackCover.bringToFront()

        // 단독 플레이어 정리
        singlePlayer?.stop()
        singlePlayer?.detachViews()
        singlePlayer?.release()
        singlePlayer = null

        // 단독 모드 숨김
        b.singleMode.visibility = View.GONE

        // 그리드 표시
        b.gridMode.visibility = View.VISIBLE

        selectedIndex = -1
        ptz = null
        b.tvCtrlHint.text = "화면 탭 = 단독 모드"

        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)

        // 기존 그리드 플레이어 정리
        for (i in 0 until 4) {
            gridPlayers[i]?.stop()
            gridPlayers[i]?.detachViews()
            gridPlayers[i]?.release()
            gridPlayers[i] = null
        }

        // 셀 처리
        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = ch.label
                ipList[i].text = ch.extractIp()
                setDot(i, "gray")
                val idx = i
                touchList[i].setOnClickListener { enterSingleMode(idx) }
                startGridStream(i, ch)
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"
                ipList[i].text = ""
                setDot(i, "gray")
            }
        }

        // 300ms 후 블랙 커버 제거
        Handler(Looper.getMainLooper()).postDelayed({
            b.blackCover.visibility = View.GONE
        }, 300)
    }

    private fun startGridStream(idx: Int, ch: Channel) {
        setDot(idx, "yellow")
        val player = MediaPlayer(libVLC)
        gridPlayers[idx] = player

        // ★ useTextureView = true → SurfaceView 잔상 없음, GONE 시 완전히 사라짐
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val subUrl = if (ch.rtspUrl.endsWith("1")) ch.rtspUrl.dropLast(1) + "2" else ch.rtspUrl
        val media = Media(libVLC, Uri.parse(subUrl))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        media.addOption(":rtsp-tcp")
        player.media = media
        media.release()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing  -> setDot(idx, "green")
                    MediaPlayer.Event.Buffering -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError -> setDot(idx, "red")
                }
            }
        }
        player.play()
    }

    private fun enterSingleMode(index: Int) {
        if (index >= channels.size) return

        // 1) 블랙 커버로 즉시 가림 (잔상 완전 차단)
        b.blackCover.visibility = View.VISIBLE
        b.blackCover.bringToFront()

        // 2) 그리드 플레이어 모두 정리
        for (i in 0 until 4) {
            gridPlayers[i]?.stop()
            gridPlayers[i]?.detachViews()
            gridPlayers[i]?.release()
            gridPlayers[i] = null
        }

        // 3) 그리드 숨김, VLC 뷰도 GONE
        vlcList.forEach { it.visibility = View.GONE }
        b.gridMode.visibility = View.GONE

        selectedIndex = index
        val ch = channels[index]

        // 4) 단독 모드 표시
        b.singleMode.visibility = View.VISIBLE
        b.lblMain.text = ch.label
        b.ipMain.text = ch.extractIp()
        b.tvLive.visibility = View.GONE
        setMainDot("yellow")

        Toast.makeText(this, "더블탭으로 그리드 복귀", Toast.LENGTH_SHORT).show()

        // 단독 플레이어 (메인스트림, TextureView)
        singlePlayer?.stop()
        singlePlayer?.detachViews()
        singlePlayer?.release()

        val player = MediaPlayer(libVLC)
        singlePlayer = player

        // ★ useTextureView = true
        player.attachViews(b.vlcMain, null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(ch.rtspUrl))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        media.addOption(":rtsp-tcp")
        player.media = media
        media.release()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        b.tvLive.visibility = View.VISIBLE
                        setMainDot("green")
                    }
                    MediaPlayer.Event.Buffering -> setMainDot("yellow")
                    MediaPlayer.Event.EncounteredError -> {
                        b.tvLive.visibility = View.GONE
                        setMainDot("red")
                    }
                }
            }
        }
        player.play()

        // 5) 300ms 후 블랙 커버 제거 (플레이어 시작 후)
        Handler(Looper.getMainLooper()).postDelayed({
            b.blackCover.visibility = View.GONE
        }, 300)

        ptz = if (ch.hasPtz) PtzController(ch) else null
        b.tvCtrlHint.text = if (ch.hasPtz) "PTZ 활성 (${ch.label})" else "${ch.label} — PTZ 없음"
        b.btnPlayback.isEnabled = ch.deviceType == DeviceType.CAMERA
    }

    private fun setupControls() {
        b.tabCtrl.setOnClickListener { switchTab(true) }
        b.tabMgmt.setOnClickListener { switchTab(false) }

        // ★ 방향키(ptzArea)만 토글 — zoomRow는 항상 표시
        b.swPtz.setOnCheckedChangeListener { _, on ->
            b.ptzArea.visibility = if (on) View.VISIBLE else View.GONE
            // zoomRow는 건드리지 않음
        }

        // 8방향 D-pad
        setupDpad(b.btnN,  0,  -60); setupDpad(b.btnS,  0,   60)
        setupDpad(b.btnW, -60,   0); setupDpad(b.btnE,  60,   0)
        setupDpad(b.btnNW,-50, -50); setupDpad(b.btnNE, 50, -50)
        setupDpad(b.btnSW,-50,  50); setupDpad(b.btnSE, 50,  50)
        b.btnStop.setOnClickListener { ptz?.stop() }

        // 줌 (누르고 있는 동안)
        b.btnZoomIn.setOnTouchListener  { _, ev ->
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

        // 프리셋 (탭=이동, 길게=저장)
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

        b.btnAddCamera.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java)
                .apply { putExtra("type", DeviceType.CAMERA.name) })
        }
        b.btnAddNvr.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java)
                .apply { putExtra("type", DeviceType.NVR.name) })
        }
        b.btnDevices.setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
        b.btnPlayback.setOnClickListener {
            val ch = channels.getOrNull(selectedIndex) ?: return@setOnClickListener
            startActivity(Intent(this, PlaybackActivity::class.java).apply {
                putExtra("httpBase", ch.httpBase)
                putExtra("username", ch.username)
                putExtra("password", ch.password)
                putExtra("label", ch.label)
            })
        }
        b.swTheme.setOnCheckedChangeListener { _, isLight ->
            isDarkTheme = !isLight
            b.tvThemeLbl.text = if (isDarkTheme) "밝은 화면" else "어두운 화면"
            applyTheme(isDarkTheme)
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

    private fun switchTab(isCtrl: Boolean) {
        b.tabCtrl.setTextColor(
            if (isCtrl) Color.parseColor("#2979FF") else Color.parseColor("#556677"))
        b.tabMgmt.setTextColor(
            if (isCtrl) Color.parseColor("#556677") else Color.parseColor("#2979FF"))
        b.tabCtrl.setBackgroundColor(
            if (isCtrl) Color.parseColor("#1a2d40") else Color.TRANSPARENT)
        b.tabMgmt.setBackgroundColor(
            if (isCtrl) Color.TRANSPARENT else Color.parseColor("#1a2d40"))
        b.panelCtrl.visibility = if (isCtrl) View.VISIBLE else View.GONE
        b.panelMgmt.visibility = if (isCtrl) View.GONE else View.VISIBLE
    }

    private fun applyTheme(dark: Boolean) {
        b.rootLayout.setBackgroundColor(
            if (dark) Color.parseColor("#1E2A3A") else Color.parseColor("#E8EDF2"))
        b.rightPanel.setBackgroundColor(
            if (dark) Color.parseColor("#162030") else Color.WHITE)
        val vid = if (dark) Color.parseColor("#111820") else Color.parseColor("#D0D8E4")
        b.singleMode.setBackgroundColor(vid)
        cellList.forEach { it.setBackgroundColor(vid) }
    }

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(dotRes(c))
    }
    private fun setMainDot(c: String) = runOnUiThread {
        b.dotMain.setBackgroundResource(dotRes(c))
    }
    private fun dotRes(c: String) = when (c) {
        "green"  -> R.drawable.dot_green
        "yellow" -> R.drawable.dot_yellow
        "red"    -> R.drawable.dot_red
        else     -> R.drawable.dot_gray
    }

    override fun onStop() {
        super.onStop()
        gridPlayers.forEach { it?.stop() }
        singlePlayer?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (i in 0 until 4) {
            gridPlayers[i]?.detachViews(); gridPlayers[i]?.release(); gridPlayers[i] = null
        }
        singlePlayer?.detachViews(); singlePlayer?.release(); singlePlayer = null
        libVLC?.release(); libVLC = null
    }
}

private fun Channel.extractIp() =
    httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
