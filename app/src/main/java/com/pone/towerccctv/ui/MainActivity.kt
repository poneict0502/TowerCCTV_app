package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pone.towerccctv.R
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
    private val players = arrayOfNulls<MediaPlayer>(4)

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

        vlcList   = listOf(b.vlc0, b.vlc1, b.vlc2, b.vlc3)
        dotList   = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        lblList   = listOf(b.lbl0, b.lbl1, b.lbl2, b.lbl3)
        ipList    = listOf(b.ip0, b.ip1, b.ip2, b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=500"))

        b.btnAddCamera.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java)
                .apply { putExtra("type", DeviceType.CAMERA.name) })
        }
        b.btnDevices.setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        startAllStreams()
    }

    override fun onStop() {
        super.onStop()
        stopAllStreams()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        libVLC?.release()
        libVLC = null
    }

    private fun startAllStreams() {
        stopAllStreams()

        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)

        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = ch.label
                ipList[i].text = ch.extractIp()
                setDot(i, "gray")
                val idx = i
                // 탭 → PlayerActivity 즉시 전환 (애니메이션 없음)
                touchList[i].setOnClickListener {
                    openPlayer(idx)
                }
                startStream(i, ch)
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"
                ipList[i].text = ""
                setDot(i, "gray")
            }
        }
    }

    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        stopAllStreams()   // 그리드 스트림 중지 (리소스 절약)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("rtspUrl",    ch.rtspUrl)
            putExtra("httpBase",   ch.httpBase)
            putExtra("username",   ch.username)
            putExtra("password",   ch.password)
            putExtra("label",      ch.label)
            putExtra("hasPtz",     ch.hasPtz)
            putExtra("ptzChannel", ch.ptzChannel)
            putExtra("deviceType", ch.deviceType.name)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)  // 애니메이션 없이 즉시 전환
    }

    private fun startStream(idx: Int, ch: Channel) {
        setDot(idx, "yellow")
        val player = MediaPlayer(libVLC)
        players[idx] = player
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

    private fun stopAllStreams() {
        for (i in 0 until 4) {
            players[i]?.stop()
            players[i]?.detachViews()
            players[i]?.release()
            players[i] = null
        }
    }

    private fun releaseAll() {
        stopAllStreams()
    }

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(when(c) {
            "green"  -> R.drawable.dot_green
            "yellow" -> R.drawable.dot_yellow
            "red"    -> R.drawable.dot_red
            else     -> R.drawable.dot_gray
        })
    }
}

private fun Channel.extractIp() =
    httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
