package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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

    private lateinit var cellList: List<FrameLayout>
    private lateinit var vlcList: List<VLCVideoLayout>
    private lateinit var dotList: List<View>
    private lateinit var lblList: List<TextView>
    private lateinit var ipList: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        cellList = listOf(b.cell0, b.cell1, b.cell2, b.cell3)
        vlcList  = listOf(b.vlc0, b.vlc1, b.vlc2, b.vlc3)
        dotList  = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        lblList  = listOf(b.lbl0, b.lbl1, b.lbl2, b.lbl3)
        ipList   = listOf(b.ip0, b.ip1, b.ip2, b.ip3)

        // libVLC 초기화 - 안정성 우선 (드롭/스킵 옵션 제거)
        val args = arrayListOf(
            "--no-audio",
            "--rtsp-tcp",
            "--network-caching=500"
        )
        libVLC = LibVLC(this, args)

        setupControls()
        applyTheme(true)
        enterGridMode()
    }

    override fun onResume() {
        super.onResume()
        if (selectedIndex < 0) enterGridMode()
    }

    private fun enterGridMode() {
        // 단독 모드 종료
        singlePlayer?.stop()
        singlePlayer?.release()
        singlePlayer = null

        selectedIndex = -1
        b.singleMode.visibility = View.GONE
        b.gridMode.visibility = View.VISIBLE

        // PTZ 비활성
        ptz = null
        setPtzEnabled(false)
        b.tvCtrlHint.text = "화면을 탭하여 단독 모드"

        // 채널 로드
        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)

        // 모든 그리드 플레이어 정리
        gridPlayers.forEachIndexed { i, p ->
            p?.stop(); p?.release(); gridPlayers[i] = null
        }

        // 4개 셀 채우기
        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                cellList[i].visibility = View.VISIBLE
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = "CH${i + 1}"
                ipList[i].text = ch.extractIp()
                cellList[i].setOnClickListener { enterSingleMode(i) }
                startGridStream(i, ch)
            } else {
                cellList[i].visibility = View.VISIBLE
                vlcList[i].visibility = View.INVISIBLE
                lblList[i].text = "--"
                ipList[i].text = ""
                cellList[i].setOnClickListener(null)
                setDot(i, "gray")
            }
        }
    }

    private fun startGridStream(i: Int, ch: Channel) {
        setDot(i, "yellow")

        val player = MediaPlayer(libVLC)
        gridPlayers[i] = player
        player.attachViews(vlcList[i], null, false, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        // 서브스트림 사용 (저해상도, 4개 동시 가능)
        val subUrl = ch.toSubStreamUrl()
        val media = Media(libVLC, Uri.parse(subUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=500")
        media.addOption(":rtsp-tcp")
        player.media = media
        media.release()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> setDot(i, "green")
                    MediaPlayer.Event.Buffering -> setDot(i, "yellow")
                    MediaPlayer.Event.EncounteredError -> setDot(i, "red")
                }
            }
        }
        player.play()
    }

    private fun enterSingleMode(index: Int) {
        if (index >= channels.size) return

        // 그리드 플레이어 모두 정지
        gridPlayers.forEachIndexed { i, p ->
            p?.stop(); p?.release(); gridPlayers[i] = null
        }

        selectedIndex = index
        val ch = channels[index]

        b.gridMode.visibility = View.GONE
        b.singleMode.visibility = View.VISIBLE
        b.lblMain.text = "CH${index + 1}"
        b.ipMain.text = ch.extractIp()
        b.tvLive.visibility = View.GONE
        setMainDot("yellow")

        // 메인스트림 (고해상도)
        singlePlayer?.release()
        val player = MediaPlayer(libVLC)
        singlePlayer = player
        player.attachViews(b.vlcMain, null, false, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(ch.rtspUrl))
        media.setHWDecoderEnabled(true, false)
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

        // PTZ 활성
        ptz = if (ch.hasPtz) PtzController(ch) else null
        setPtzEnabled(ch.hasPtz)
        b.tvCtrlHint.text = if (ch.hasPtz) "PTZ 활성 (CH${index + 1})" else "이 채널은 PTZ 미지원"
        b.btnPlayback.isEnabled = ch.deviceType == DeviceType.CAMERA
    }

    private fun setPtzEnabled(on: Boolean) {
        val alpha = if (on) 1f else 0.35f
        b.joystick.isEnabled = on
        b.joystick.alpha = alpha
        b.btnZoomIn.isEnabled = on
        b.btnZoomOut.isEnabled = on
        b.btnZoomIn.alpha = alpha
        b.btnZoomOut.alpha = alpha
        listOf(b.btnP1, b.btnP2, b.btnP3, b.btnP4, b.btnP5,
               b.btnP6, b.btnP7, b.btnP8, b.btnP9).forEach {
            it.isEnabled = on
            it.alpha = alpha
        }
    }

    private fun setupControls() {
        b.btnBack.setOnClickListener { enterGridMode() }

        b.tabCtrl.setOnClickListener { switchTab(true) }
        b.tabMgmt.setOnClickListener { switchTab(false) }

        b.joystick.onMove    = { angle, mag -> ptz?.moveByAngle(angle, mag) }
        b.joystick.onRelease = { ptz?.stop() }

        fun zoomTouch(zoomIn: Boolean) = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> ptz?.zoom(zoomIn)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptz?.zoomStop()
            }; true
        }
        b.btnZoomIn.setOnTouchListener(zoomTouch(true))
        b.btnZoomOut.setOnTouchListener(zoomTouch(false))

        listOf(b.btnP1 to 1, b.btnP2 to 2, b.btnP3 to 3, b.btnP4 to 4, b.btnP5 to 5,
               b.btnP6 to 6, b.btnP7 to 7, b.btnP8 to 8, b.btnP9 to 9).forEach { (btn, id) ->
            btn.setOnClickListener { ptz?.gotoPreset(id) }
            btn.setOnLongClickListener {
                ptz?.setPreset(id)
                btn.animate().alpha(0.2f).setDuration(80)
                    .withEndAction { btn.animate().alpha(1f).setDuration(200).start() }.start()
                true
            }
        }

        b.btnAddCamera.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java).apply {
                putExtra("type", DeviceType.CAMERA.name) })
        }
        b.btnAddNvr.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java).apply {
                putExtra("type", DeviceType.NVR.name) })
        }
        b.btnDevices.setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
        b.btnPlayback.setOnClickListener {
            val ch = channels.getOrNull(selectedIndex) ?: return@setOnClickListener
            startActivity(Intent(this, PlaybackActivity::class.java).apply {
                putExtra("httpBase", ch.httpBase); putExtra("username", ch.username)
                putExtra("password", ch.password); putExtra("label", "CH${selectedIndex + 1}")
            })
        }

        b.swTheme.setOnCheckedChangeListener { _, isLight ->
            isDarkTheme = !isLight
            b.tvThemeLbl.text = if (isDarkTheme) "밝은 화면" else "어두운 화면"
            applyTheme(isDarkTheme)
        }
    }

    private fun switchTab(isCtrl: Boolean) {
        b.tabCtrl.setTextColor(if (isCtrl) Color.parseColor("#2979FF") else Color.parseColor("#556677"))
        b.tabMgmt.setTextColor(if (isCtrl) Color.parseColor("#556677") else Color.parseColor("#2979FF"))
        b.tabCtrl.setBackgroundColor(if (isCtrl) Color.parseColor("#1a2d40") else Color.TRANSPARENT)
        b.tabMgmt.setBackgroundColor(if (isCtrl) Color.TRANSPARENT else Color.parseColor("#1a2d40"))
        b.panelCtrl.visibility = if (isCtrl) View.VISIBLE else View.GONE
        b.panelMgmt.visibility = if (isCtrl) View.GONE else View.VISIBLE
    }

    private fun applyTheme(dark: Boolean) {
        val bg  = if (dark) Color.parseColor("#1E2A3A") else Color.parseColor("#E8EDF2")
        val pan = if (dark) Color.parseColor("#162030") else Color.WHITE
        val vid = if (dark) Color.parseColor("#111820") else Color.parseColor("#D0D8E4")
        b.rootLayout.setBackgroundColor(bg)
        b.rightPanel.setBackgroundColor(pan)
        b.singleMode.setBackgroundColor(vid)
        cellList.forEach { it.setBackgroundColor(vid) }
    }

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(dotRes(c))
    }
    private fun setMainDot(c: String) = runOnUiThread {
        b.dotMain.setBackgroundResource(dotRes(c))
    }
    private fun dotRes(c: String) = when(c) {
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
        gridPlayers.forEachIndexed { i, p ->
            p?.release(); gridPlayers[i] = null
        }
        singlePlayer?.release()
        singlePlayer = null
        libVLC?.release()
        libVLC = null
    }
}

// 확장 함수
private fun Channel.extractIp(): String {
    return httpBase
        .substringAfter("//")
        .substringBefore(":")
        .substringBefore("/")
}

private fun Channel.toSubStreamUrl(): String {
    // /Streaming/Channels/101 → /Streaming/Channels/102
    // /Streaming/Channels/201 → /Streaming/Channels/202
    return if (rtspUrl.endsWith("1")) rtspUrl.dropLast(1) + "2" else rtspUrl
}
