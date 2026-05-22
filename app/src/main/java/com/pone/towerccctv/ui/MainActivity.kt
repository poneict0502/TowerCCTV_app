package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.pone.towerccctv.R
import com.pone.towerccctv.databinding.ActivityMainBinding
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream

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

    // 자동 재연결
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectJobs = arrayOfNulls<Runnable>(4)
    private val RECONNECT_DELAY = 5000L  // 5초 후 재연결

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

        libVLC = LibVLC(this, arrayListOf(
            "--no-audio", "--rtsp-tcp", "--network-caching=500"
        ))

        // 설정 팝업 메뉴
        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "⚡  기본 카메라 4대 자동 등록")
            popup.menu.add(0, 3, 2, "📡  OCR / 계측 설정")
            popup.menu.add(0, 4, 3, "──────────────").isEnabled = false
            popup.menu.add(0, 5, 4, "추후 추가 예정").isEnabled = false
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                    2 -> showAutoRegisterDialog()
                    3 -> OcrSettingsDialog(this) { /* OCR 상태 변경 시 */ }.show()
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
        cancelAllReconnects()
        stopAllStreams()
    }

    // ── 기본 카메라 4대 자동 등록 ──
    private fun showAutoRegisterDialog() {
        val msg = "다음 설정으로 등록됩니다:\n\n" +
            "CH1  192.168.0.101  admin / 1234qwer@\n" +
            "CH2  192.168.0.102  admin / 1q2w3e4r@\n" +
            "CH3  192.168.0.103  admin / 1q2w3e4r@\n" +
            "CH4  192.168.0.104  admin / 1q2w3e4r@\n\n" +
            "기존 등록된 장치는 삭제됩니다."
        AlertDialog.Builder(this)
            .setTitle("기본 카메라 4대 자동 등록")
            .setMessage(msg)
            .setPositiveButton("등록") { _: android.content.DialogInterface, _: Int ->
                autoRegisterCameras()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun autoRegisterCameras() {
        val cameras = listOf(
            com.pone.towerccctv.model.Device(
                name = "CH1", type = com.pone.towerccctv.model.DeviceType.CAMERA,
                ip = "192.168.0.101", username = "admin", password = "1234qwer@",
                hasPtz = true, enabled = true
            ),
            com.pone.towerccctv.model.Device(
                name = "CH2", type = com.pone.towerccctv.model.DeviceType.CAMERA,
                ip = "192.168.0.102", username = "admin", password = "1q2w3e4r@",
                hasPtz = true, enabled = true
            ),
            com.pone.towerccctv.model.Device(
                name = "CH3", type = com.pone.towerccctv.model.DeviceType.CAMERA,
                ip = "192.168.0.103", username = "admin", password = "1q2w3e4r@",
                hasPtz = true, enabled = true
            ),
            com.pone.towerccctv.model.Device(
                name = "CH4", type = com.pone.towerccctv.model.DeviceType.CAMERA,
                ip = "192.168.0.104", username = "admin", password = "1q2w3e4r@",
                hasPtz = false, enabled = true  // 계측 카메라 PTZ 없음
            )
        )
        com.pone.towerccctv.model.DeviceStore.save(this, cameras)
        android.widget.Toast.makeText(this, "카메라 4대 등록 완료!", android.widget.Toast.LENGTH_SHORT).show()
        startAllStreams()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllReconnects()
        releaseAll()
        libVLC?.release()
        libVLC = null
    }

    private fun startAllStreams() {
        stopAllStreams()
        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)

        // 장치 미등록 안내
        if (channels.isEmpty()) {
            for (i in 0 until 4) {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = if (i == 0) "장치를 등록해 주세요" else "--"
                ipList[i].text = ""
                setDot(i, "gray")
            }
            return
        }

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

    private fun startStream(idx: Int, ch: Channel) {
        setDot(idx, "yellow")
        val player = MediaPlayer(libVLC)
        players[idx] = player
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val subUrl = ch.toSubStreamUrl()
        val media = Media(libVLC, Uri.parse(subUrl))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        media.addOption(":rtsp-tcp")
        player.media = media
        media.release()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        setDot(idx, "green")
                        cancelReconnect(idx)  // 연결 성공 → 재연결 취소
                    }
                    MediaPlayer.Event.Buffering -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError -> {
                        setDot(idx, "red")
                        scheduleReconnect(idx, ch)  // 자동 재연결 예약
                    }
                    MediaPlayer.Event.EndReached -> {
                        setDot(idx, "gray")
                        scheduleReconnect(idx, ch)
                    }
                }
            }
        }
        player.play()
    }

    // ── 자동 재연결 ──
    private fun scheduleReconnect(idx: Int, ch: Channel) {
        cancelReconnect(idx)
        val job = Runnable {
            if (isDestroyed || isFinishing) return@Runnable
            players[idx]?.detachViews()
            players[idx]?.release()
            players[idx] = null
            setDot(idx, "yellow")
            startStream(idx, ch)
        }
        reconnectJobs[idx] = job
        reconnectHandler.postDelayed(job, RECONNECT_DELAY)
    }

    private fun cancelReconnect(idx: Int) {
        reconnectJobs[idx]?.let { reconnectHandler.removeCallbacks(it) }
        reconnectJobs[idx] = null
    }

    private fun cancelAllReconnects() {
        for (i in 0 until 4) cancelReconnect(i)
    }

    // ── PlayerActivity 전환 (스냅샷 백그라운드 처리) ──
    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return

        // UI 스레드에서 bitmap 캡처 → IO 스레드에서 파일 저장
        val bmp = captureFrameOnUiThread(index)

        lifecycleScope.launch {
            val snapPath = bmp?.let {
                withContext(Dispatchers.IO) {
                    saveBitmapToFile(it, index).also { _ -> it.recycle() }
                }
            }
            cancelAllReconnects()
            stopAllStreams()
            startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply {
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
    }

    // UI 스레드에서 TextureView 비트맵 캡처
    private fun captureFrameOnUiThread(index: Int): Bitmap? {
        return try {
            val vlcView = vlcList[index]
            for (i in 0 until vlcView.childCount) {
                val child = vlcView.getChildAt(i)
                if (child is TextureView) return child.bitmap
            }
            if (vlcView.width > 0 && vlcView.height > 0) {
                val bmp = Bitmap.createBitmap(vlcView.width, vlcView.height, Bitmap.Config.ARGB_8888)
                vlcView.draw(Canvas(bmp))
                bmp
            } else null
        } catch (e: Exception) { null }
    }

    // IO 스레드에서 파일 저장
    private fun saveBitmapToFile(bmp: Bitmap, index: Int): String? {
        return try {
            val f = File(cacheDir, "snap_$index.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            f.absolutePath
        } catch (e: Exception) { null }
    }

    private fun stopAllStreams() {
        for (i in 0 until 4) {
            players[i]?.stop()
            players[i]?.detachViews()
            players[i]?.release()
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

private fun Channel.toSubStreamUrl() =
    if (rtspUrl.endsWith("1")) rtspUrl.dropLast(1) + "2" else rtspUrl
