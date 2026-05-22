package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
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
        ipList    = listOf(b.ip0,  b.ip1,  b.ip2,  b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=500"))

        // 설정 팝업 메뉴
        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "─────────────").isEnabled = false
            popup.menu.add(0, 3, 2, "추후 추가 예정").isEnabled = false
            popup.menu.add(0, 4, 3, "추후 추가 예정").isEnabled = false
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                }
                true
            }
            popup.show()
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
                ipList[i].text  = ch.extractIp()
                setDot(i, "gray")
                val idx = i
                touchList[i].setOnClickListener { openPlayer(idx) }
                startStream(i, ch)
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"
                ipList[i].text  = ""
                setDot(i, "gray")
            }
        }
    }

    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return

        // ★ 탭 순간 그리드 마지막 프레임 캡처 (블랙 없는 전환용)
        val snapPath = captureSnapshot(index)

        stopAllStreams()
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("rtspUrl",    ch.rtspUrl)
            putExtra("httpBase",   ch.httpBase)
            putExtra("username",   ch.username)
            putExtra("password",   ch.password)
            putExtra("label",      ch.label)
            putExtra("hasPtz",     ch.hasPtz)
            putExtra("ptzChannel", ch.ptzChannel)
            putExtra("deviceType", ch.deviceType.name)
            snapPath?.let { putExtra("snapPath", it) }
        })
        overridePendingTransition(0, 0)
    }

    // TextureView에서 마지막 프레임 추출
    private fun captureSnapshot(index: Int): String? {
        return try {
            val vlcView = vlcList[index]
            // VLCVideoLayout 내부 TextureView 찾기 (useTextureView=true 이므로 존재)
            var bitmap: Bitmap? = null
            for (i in 0 until vlcView.childCount) {
                val child = vlcView.getChildAt(i)
                if (child is TextureView) {
                    bitmap = child.bitmap
                    break
                }
            }
            // TextureView가 없으면 View 전체 draw
            if (bitmap == null && vlcView.width > 0 && vlcView.height > 0) {
                bitmap = Bitmap.createBitmap(vlcView.width, vlcView.height, Bitmap.Config.ARGB_8888)
                vlcView.draw(Canvas(bitmap))
            }
            bitmap?.let {
                val f = File(cacheDir, "snap_$index.jpg")
                FileOutputStream(f).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                f.absolutePath
            }
        } catch (e: Exception) {
            null  // 실패해도 정상 작동 (블랙 화면으로 폴백)
        }
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
                    MediaPlayer.Event.Playing            -> setDot(idx, "green")
                    MediaPlayer.Event.Buffering          -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError   -> setDot(idx, "red")
                }
            }
        }
        player.play()
    }

    private fun stopAllStreams() {
        for (i in 0 until 4) {
            players[i]?.stop(); players[i]?.detachViews(); players[i]?.release()
            players[i] = null
        }
    }

    private fun releaseAll() = stopAllStreams()

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
