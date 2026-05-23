package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pone.towerccctv.R
import com.pone.towerccctv.databinding.ActivityMainBinding
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType
import com.pone.towerccctv.ocr.OcrEngine
import com.pone.towerccctv.ocr.OcrSettings
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
    private val RECONNECT_DELAY = 5000L

    // OCR - 영상 프레임 캡처 방식
    private var ocrEngine: OcrEngine? = null
    private var ocrChannelIndex = -1  // OCR 대상 채널 인덱스

    // 경보 중복 방지
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL = 10_000L

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

        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "⚡  기본 장치 자동 등록")
            popup.menu.add(0, 3, 2, "📡  OCR / 계측 설정")
            popup.menu.add(0, 4, 3, "📹  NVR 녹화 재생")
            popup.menu.add(0, 5, 4, "──────────────").isEnabled = false
            popup.menu.add(0, 6, 5, "추후 추가 예정").isEnabled = false
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                    2 -> showAutoRegisterDialog()
                    3 -> OcrSettingsDialog(this) { restartStreams() }.show()
                    4 -> showNvrPlaybackSelector()
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
        // OCR은 단독모드에서도 계속 실행 (SharedPreferences 업데이트 유지)
        stopAllStreams()
    }

    private fun showNvrPlaybackSelector() {
        // NVR 고정 설정 (192.168.0.100)
        val nvrIp  = "192.168.0.100"
        val nvrUser = "admin"
        val nvrPass = "1q2w3e4r@"
        val chNames = arrayOf("CH 1", "CH 2", "CH 3", "CH 4")
        AlertDialog.Builder(this)
            .setTitle("📹 NVR 녹화 재생 - 채널 선택")
            .setItems(chNames) { _, idx ->
                val ch = idx + 1
                startActivity(Intent(this, PlaybackActivity::class.java).apply {
                    putExtra("httpBase", "http://$nvrIp")
                    putExtra("username", nvrUser)
                    putExtra("password", nvrPass)
                    putExtra("label", "NVR-CH$ch")
                    putExtra("nvrChannel", ch)
                })
            }
            .setNegativeButton("취소", null).show()
    }

    private fun playAlertSound() {
        try {
            val toneGen = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_ALARM, 100)
            // 삐삐삐 3번
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            reconnectHandler.postDelayed({
                toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            }, 400L)
            reconnectHandler.postDelayed({
                toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                reconnectHandler.postDelayed({ toneGen.release() }, 500L)
            }, 800L)
        } catch (e: Exception) { /* 무시 */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllReconnects()
        stopOcr()
        releaseAll()
        libVLC?.release(); libVLC = null
    }

    private fun restartStreams() {
        stopOcr(); stopAllStreams(); startAllStreams()
    }

    private fun startAllStreams() {
        stopAllStreams()
        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)

        if (channels.isEmpty()) {
            for (i in 0 until 4) {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = if (i == 0) "⚙ 설정에서 장치를 등록해 주세요" else "--"
                ipList[i].text = ""; setDot(i, "gray")
            }
            return
        }

        // CH4 인덱스 찾기 (마지막 채널)
        ocrChannelIndex = channels.size - 1

        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = ch.label
                ipList[i].text  = ch.extractIp()
                setDot(i, "gray")
                val idx = i
                touchList[i].setOnClickListener { openPlayer(idx) }
                // 모든 채널 동일하게 서브스트림 영상
                startStream(i, ch)
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"; ipList[i].text = ""; setDot(i, "gray")
            }
        }

        // OCR 모드 ON이면 0.5초 프레임 캡처 루프 시작
        if (OcrSettings.isOcrEnabled(this) && ocrChannelIndex >= 0) {
            startOcrLoop()
        }
    }

    // ── OCR: HTTP 스냅샷 방식 (TextureView 검정 문제 해결) ──
    private fun startOcrLoop() {
        val ch = channels.getOrNull(ocrChannelIndex) ?: return
        val snapshotUrl = "${ch.httpBase}/ISAPI/Streaming/channels/101/picture"

        b.osdOverlay.visibility = View.VISIBLE
        b.tvOcrBadge.visibility = View.VISIBLE

        ocrEngine?.release()
        ocrEngine = OcrEngine(this).also { engine ->
            engine.onResult = { result ->
                // 그리드 OSD 업데이트
                updateOsd(result.windSpeed, result.weight,
                          result.windAlert, result.weightAlert,
                          result.rawWind, result.rawWeight)

            }
            engine.startHttpLoop(snapshotUrl, ch.username, ch.password)
        }
    }

    private fun stopOcr() {
        ocrEngine?.release(); ocrEngine = null
        b.osdOverlay.visibility = View.GONE
        b.tvOcrBadge.visibility = View.GONE
    }

    // ── CH1 OSD 업데이트 ──
    // 마지막 인식값 보관 (사라지지 않음)
    private var lastWind: Float? = null
    private var lastWeight: Float? = null
    private var lastWindAlert = false
    private var lastWeightAlert = false

    private fun updateOsd(wind: Float?, weight: Float?,
                          windAlert: Boolean, weightAlert: Boolean,
                          rawWind: String = "", rawWeight: String = "") {
        // 새 값이 들어오면 업데이트, 없으면 이전 값 유지
        if (wind   != null) { lastWind   = wind;   lastWindAlert   = windAlert }
        if (weight != null) { lastWeight = weight; lastWeightAlert = weightAlert }

        val isTon = OcrSettings.isWeightTon(this)
        val windTxt   = if (lastWind   != null) "🌬  %.1f m/s".format(lastWind)
                        else "🌬  [${rawWind.take(10)}]"
        val weightTxt = if (lastWeight != null) {
            if (isTon) "⚖  %.2f t".format(lastWeight) else "⚖  %.1f kg".format(lastWeight)
        } else "⚖  [${rawWeight.take(10)}]"
        b.osdWind.text   = windTxt
        b.osdWeight.text = weightTxt
        b.osdWind.setTextColor(if (lastWindAlert) Color.parseColor("#FF4444") else Color.WHITE)
        b.osdWeight.setTextColor(if (lastWeightAlert) Color.parseColor("#FF4444") else Color.WHITE)

        if (windAlert || weightAlert) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > ALERT_INTERVAL) {
                lastAlertTime = now
                val msg = buildString {
                    if (windAlert && wind != null)
                        append("🚨 풍속 초과: %.1f m/s (한계: %.1f)\n"
                            .format(wind, OcrSettings.getWindLimit(this@MainActivity)))
                    if (weightAlert && weight != null)
                        append("🚨 중량 초과: %.2f t (한계: %.1f)"
                            .format(weight, OcrSettings.getWeightLimit(this@MainActivity)))
                }
                // OSD 빨간 배경
                b.osdOverlay.setBackgroundColor(Color.parseColor("#DD880000"))
                reconnectHandler.postDelayed({
                    b.osdOverlay.setBackgroundColor(Color.parseColor("#DD000000"))
                }, 3000L)
                Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
                // 음향 경보
                playAlertSound()
            }
        }
    }

    // ── RTSP 스트림 ──
    private fun startStream(idx: Int, ch: Channel) {
        setDot(idx, "yellow")
        val player = MediaPlayer(libVLC)
        players[idx] = player
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(libVLC, Uri.parse(ch.toSubStreamUrl()))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        media.addOption(":rtsp-tcp")
        player.media = media; media.release()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing  -> { setDot(idx, "green"); cancelReconnect(idx) }
                    MediaPlayer.Event.Buffering -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError -> { setDot(idx, "red"); scheduleReconnect(idx, ch) }
                    MediaPlayer.Event.EndReached -> { setDot(idx, "gray"); scheduleReconnect(idx, ch) }
                }
            }
        }
        player.play()
    }

    private fun scheduleReconnect(idx: Int, ch: Channel) {
        cancelReconnect(idx)
        val job = Runnable {
            if (isDestroyed || isFinishing) return@Runnable
            players[idx]?.detachViews(); players[idx]?.release(); players[idx] = null
            setDot(idx, "yellow"); startStream(idx, ch)
        }
        reconnectJobs[idx] = job
        reconnectHandler.postDelayed(job, RECONNECT_DELAY)
    }

    private fun cancelReconnect(idx: Int) {
        reconnectJobs[idx]?.let { reconnectHandler.removeCallbacks(it) }
        reconnectJobs[idx] = null
    }
    private fun cancelAllReconnects() = (0 until 4).forEach { cancelReconnect(it) }

    // ── PlayerActivity 전환 ──
    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return

        // TextureView에서 스냅샷 캡처 시도
        val bmp: Bitmap? = try {
            val vlcView = vlcList[index]
            var result: Bitmap? = null
            for (i in 0 until vlcView.childCount) {
                val child = vlcView.getChildAt(i)
                if (child is android.view.TextureView) {
                    result = child.bitmap; break
                }
            }
            result
        } catch (e: Exception) { null }

        lifecycleScope.launch {
            val snapPath = bmp?.let {
                withContext(Dispatchers.IO) {
                    saveBitmapToFile(it, index).also { _ -> it.recycle() }
                }
            }
            cancelAllReconnects(); stopOcr(); stopAllStreams()
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

    private fun saveBitmapToFile(bmp: Bitmap, index: Int): String? = try {
        val f = File(cacheDir, "snap_$index.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        f.absolutePath
    } catch (e: Exception) { null }

    private fun stopAllStreams() {
        for (i in 0 until 4) {
            players[i]?.stop(); players[i]?.detachViews()
            players[i]?.release(); players[i] = null
        }
    }
    private fun releaseAll() = stopAllStreams()

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(when(c) {
            "green" -> R.drawable.dot_green; "yellow" -> R.drawable.dot_yellow
            "red"   -> R.drawable.dot_red;  else     -> R.drawable.dot_gray
        })
    }

    // ── 기본 카메라 4대 자동 등록 ──
    private fun showAutoRegisterDialog() {
        val msg = "다음 설정으로 등록됩니다:\n\n" +
            "CH1  192.168.0.101  1234qwer@\n" +
            "CH2  192.168.0.102  1q2w3e4r@\n" +
            "CH3  192.168.0.103  1q2w3e4r@\n" +
            "CH4  192.168.0.104  1q2w3e4r@\n\n" +
            "기존 등록된 장치는 삭제됩니다."
        AlertDialog.Builder(this)
            .setTitle("기본 카메라 4대 자동 등록")
            .setMessage(msg)
            .setPositiveButton("등록") { _: android.content.DialogInterface, _: Int ->
                autoRegisterCameras()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun autoRegisterCameras() {
        val cameras = listOf(
            com.pone.towerccctv.model.Device(name="CH1",
                type=DeviceType.CAMERA, ip="192.168.0.101",
                username="admin", password="1234qwer@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH2",
                type=DeviceType.CAMERA, ip="192.168.0.102",
                username="admin", password="1q2w3e4r@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH3",
                type=DeviceType.CAMERA, ip="192.168.0.103",
                username="admin", password="1q2w3e4r@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH4",
                type=DeviceType.CAMERA, ip="192.168.0.104",
                username="admin", password="1q2w3e4r@", hasPtz=false)
        )
        DeviceStore.save(this, cameras)
        Toast.makeText(this, "카메라 4대 등록 완료!", Toast.LENGTH_SHORT).show()
        startAllStreams()
    }
}

private fun Channel.extractIp() =
    httpBase.substringAfter("//").substringBefore(":").substringBefore("/")

private fun Channel.toSubStreamUrl() =
    if (rtspUrl.endsWith("1")) rtspUrl.dropLast(1) + "2" else rtspUrl
