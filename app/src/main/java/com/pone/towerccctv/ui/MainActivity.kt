package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.TextureView
import android.view.View
import android.view.WindowManager
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
import com.pone.towerccctv.data.AlertDatabase
import okhttp3.MediaType.Companion.toMediaType
import com.pone.towerccctv.data.AlertRecord
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var alertDb: AlertDatabase
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = java.util.Calendar.getInstance()
            val h = "%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY))
            val m = "%02d".format(now.get(java.util.Calendar.MINUTE))
            val s = "%02d".format(now.get(java.util.Calendar.SECOND))
            if (::b.isInitialized) b.tvClock.text = "$h:$m"
            handler.postDelayed(this, 1000L)
        }
    }
    private val reconnectJobs = arrayOfNulls<Runnable>(4)
    private var tts: TextToSpeech? = null

    private var ocrEngine: OcrEngine? = null
    private var ocrChannelIndex = -1
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL = 10_000L
    private var lastWind: Float? = null
    private var lastWeight: Float? = null
    private var lastWindAlert = false
    private var lastWeightAlert = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 전체화면 몰입 모드 (setContentView 이후 호출)
        setFullscreen()
        handler.post(clockRunnable)
        alertDb = AlertDatabase(this)

        vlcList   = listOf(b.vlc0, b.vlc1, b.vlc2, b.vlc3)
        dotList   = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        lblList   = listOf(b.lbl0, b.lbl1, b.lbl2, b.lbl3)
        ipList    = listOf(b.ip0,  b.ip1,  b.ip2,  b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        // LibVLC 한 번만 생성
        libVLC = LibVLC(this, arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=300"))

        // TTS 초기화
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.KOREAN
                tts?.setSpeechRate(0.9f)
            }
        }

        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "⚡  기본 장치 자동 등록")
            popup.menu.add(0, 3, 2, "📡  OCR / 계측 설정")
            popup.menu.add(0, 4, 3, "📹  NVR 녹화 재생")
            popup.menu.add(0, 5, 4, "🕐  카메라 시간 동기화")
            popup.menu.add(0, 6, 5, "📊  경보 이력 조회")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                    2 -> showAutoRegisterDialog()
                    3 -> OcrSettingsDialog(this) { restartAll() }.show()
                    4 -> showNvrPlaybackSelector()
                    5 -> syncCameraTime()
                    6 -> startActivity(Intent(this, AlertHistoryActivity::class.java))
                }
                true
            }
            popup.show()
        }
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        handler.post(clockRunnable)
        alertDb = AlertDatabase(this)
        startAll()
    }

    private fun setFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onStop() {
        super.onStop()
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        ocrEngine?.release(); ocrEngine = null
        handler.removeCallbacks(clockRunnable)
        libVLC?.release(); libVLC = null
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private fun restartAll() { stopAll(); startAll() }

    private fun startAll() {
        // 장치 로드
        val devices = DeviceStore.load(this).filter { it.enabled }
        channels = devices.flatMap { it.toChannels() }.take(4)
        ocrChannelIndex = channels.size - 1

        for (i in 0 until 4) {
            if (i < channels.size) {
                val ch = channels[i]
                vlcList[i].visibility = View.VISIBLE
                lblList[i].text = ch.label
                ipList[i].text  = ch.extractIp()
                setDot(i, "gray")
                touchList[i].setOnClickListener { openPlayer(i) }
                startStream(i, ch)
            } else {
                vlcList[i].visibility = View.INVISIBLE
                touchList[i].setOnClickListener(null)
                lblList[i].text = "--"; ipList[i].text = ""
                setDot(i, "gray")
            }
        }

        if (channels.isEmpty()) {
            lblList[0].text = "⚙ 설정에서 장치를 등록해 주세요"
        }

        // OCR
        if (OcrSettings.isOcrEnabled(this) && ocrChannelIndex >= 0) {
            startOcrLoop()
        }
    }

    private fun stopAll() {
        // 재연결 취소
        for (i in 0 until 4) {
            reconnectJobs[i]?.let { handler.removeCallbacks(it) }
            reconnectJobs[i] = null
        }
        // 스트림 정지
        for (i in 0 until 4) {
            players[i]?.stop()
            players[i]?.detachViews()
            players[i]?.release()
            players[i] = null
        }
        // OCR은 백그라운드 유지 (단독모드에서도 SharedPreferences 업데이트)
        // onDestroy에서만 완전 해제
    }

    private fun startStream(idx: Int, ch: Channel) {
        // 기존 정리
        players[idx]?.stop()
        players[idx]?.detachViews()
        players[idx]?.release()
        players[idx] = null

        setDot(idx, "yellow")

        val player = MediaPlayer(libVLC)
        players[idx] = player
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val url = ch.toSubStreamUrl()
        val media = Media(libVLC, Uri.parse(url))
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=300")
        media.addOption(":rtsp-tcp")
        player.media = media
        media.release()
        player.play()

        player.setEventListener { ev ->
            runOnUiThread {
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        setDot(idx, "green")
                        reconnectJobs[idx]?.let { handler.removeCallbacks(it) }
                        reconnectJobs[idx] = null
                    }
                    MediaPlayer.Event.Buffering -> setDot(idx, "yellow")
                    MediaPlayer.Event.EncounteredError -> {
                        setDot(idx, "red")
                        scheduleReconnect(idx, ch)
                    }
                    MediaPlayer.Event.EndReached -> {
                        setDot(idx, "gray")
                        scheduleReconnect(idx, ch)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(idx: Int, ch: Channel) {
        reconnectJobs[idx]?.let { handler.removeCallbacks(it) }
        val job = Runnable {
            if (!isDestroyed && !isFinishing) startStream(idx, ch)
        }
        reconnectJobs[idx] = job
        handler.postDelayed(job, 5000L)
    }

    // ── OCR ──
    private fun startOcrLoop() {
        val ch = channels.getOrNull(ocrChannelIndex) ?: return
        val url = "${ch.httpBase}/ISAPI/Streaming/channels/101/picture"
        b.osdOverlay.visibility = View.VISIBLE
        b.tvOcrBadge.visibility = View.VISIBLE
        ocrEngine?.release()
        ocrEngine = OcrEngine(this).also { engine ->
            engine.onResult = { result ->
                if (result.windSpeed != null) { lastWind = result.windSpeed; lastWindAlert = result.windAlert }
                if (result.weight   != null) { lastWeight = result.weight;  lastWeightAlert = result.weightAlert }
                updateOsd()
                // CH1 카메라 POS OSD 전송 (0.5초마다)
                sendPosOsd(lastWind, lastWeight)
            }
            engine.startHttpLoop(url, ch.username, ch.password)
        }
    }

    private fun updateOsd() {
        val isTon = OcrSettings.isWeightTon(this)
        b.osdWind.text   = if (lastWind   != null) "🌬  %.1f m/s".format(lastWind)   else "🌬  --"
        b.osdWeight.text = if (lastWeight != null) {
            if (isTon) "⚖  %.2f t".format(lastWeight) else "⚖  %.2f kg".format(lastWeight)
        } else "⚖  --"
        b.osdWind.setTextColor(if (lastWindAlert)   Color.parseColor("#FF4444") else Color.WHITE)
        b.osdWeight.setTextColor(if (lastWeightAlert) Color.parseColor("#FF4444") else Color.WHITE)

        if (lastWindAlert || lastWeightAlert) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > ALERT_INTERVAL) {
                lastAlertTime = now
                // 경보 이력 저장
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
                alertDb.insert(AlertRecord(
                    dateTime    = sdf.format(java.util.Date()),
                    weight      = lastWeight,
                    windSpeed   = lastWind,
                    weightAlert = lastWeightAlert,
                    windAlert   = lastWindAlert
                ))
                b.osdOverlay.setBackgroundColor(Color.parseColor("#DD880000"))
                handler.postDelayed({ b.osdOverlay.setBackgroundColor(Color.parseColor("#DD000000")) }, 3000L)
                val msg = buildString {
                    if (lastWindAlert && lastWind != null) append("🚨 풍속 초과: %.1f m/s\n".format(lastWind))
                    if (lastWeightAlert && lastWeight != null) append("🚨 중량 초과: %.2f t".format(lastWeight))
                }
                Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
                playAlertSound(lastWindAlert, lastWeightAlert)
            }
        }
    }

    private fun playAlertSound(windAlert: Boolean, weightAlert: Boolean) {
        // 삐삐삐 비프음
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            handler.postDelayed({ tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 400L)
            handler.postDelayed({ tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300); handler.postDelayed({ tg.release() }, 400L) }, 800L)
        } catch (e: Exception) {}

        // TTS 음성 안내 (비프음 후 1.2초 뒤)
        handler.postDelayed({
            val msg = when {
                weightAlert && windAlert -> "중량 초과. 풍속 초과"
                weightAlert -> "중량 초과. 중량 초과"
                windAlert   -> "풍속 초과. 풍속 초과"
                else -> ""
            }
            if (msg.isNotEmpty()) {
                tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "alert")
            }
        }, 1200L)
    }

    // ── PlayerActivity 전환 ──
    private fun openPlayer(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        val bmp: Bitmap? = try {
            var found: Bitmap? = null
            val vlcView = vlcList[index]
            for (i in 0 until vlcView.childCount) {
                val child = vlcView.getChildAt(i)
                if (child is TextureView) { found = child.bitmap; break }
            }
            found
        } catch (e: Exception) { null }

        lifecycleScope.launch {
            val snapPath = bmp?.let {
                withContext(Dispatchers.IO) {
                    try {
                        val f = File(cacheDir, "snap_$index.jpg")
                        FileOutputStream(f).use { out -> it.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                        it.recycle()
                        f.absolutePath
                    } catch (e: Exception) { null }
                }
            }
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

    private fun setDot(i: Int, c: String) = runOnUiThread {
        dotList[i].setBackgroundResource(when(c) {
            "green"  -> R.drawable.dot_green
            "yellow" -> R.drawable.dot_yellow
            "red"    -> R.drawable.dot_red
            else     -> R.drawable.dot_gray
        })
    }

    private fun showNvrPlaybackSelector() {
        AlertDialog.Builder(this)
            .setTitle("📹 NVR 녹화 재생")
            .setItems(arrayOf("CH 1","CH 2","CH 3","CH 4")) { _, idx ->
                startActivity(Intent(this, PlaybackActivity::class.java).apply {
                    putExtra("httpBase", "http://192.168.0.100")
                    putExtra("username", "admin"); putExtra("password", "1q2w3e4r@")
                    putExtra("label", "NVR-CH${idx+1}"); putExtra("nvrChannel", idx+1)
                })
            }.setNegativeButton("취소", null).show()
    }

    private fun syncCameraTime() {
        val devices = DeviceStore.load(this).filter { it.enabled }  // 카메라 + NVR 모두
        if (devices.isEmpty()) {
            Toast.makeText(this, "등록된 카메라가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "카메라 ${devices.size}대 시간 동기화 중...", Toast.LENGTH_SHORT).show()

        val now = java.util.Calendar.getInstance()
        val year  = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day   = now.get(java.util.Calendar.DAY_OF_MONTH)
        val hour  = now.get(java.util.Calendar.HOUR_OF_DAY)
        val min   = now.get(java.util.Calendar.MINUTE)
        val sec   = now.get(java.util.Calendar.SECOND)

        // ISO 8601 형식 (Hikvision ISAPI)
        val timeXml = """<?xml version="1.0" encoding="UTF-8"?>
<Time>
<timeMode>manual</timeMode>
<localTime>${"%04d".format(year)}-${"%02d".format(month)}-${"%02d".format(day)}T${"%02d".format(hour)}:${"%02d".format(min)}:${"%02d".format(sec)}+09:00</localTime>
<timeZone>CST-9:00:00</timeZone>
</Time>"""

        var successCount = 0
        var failCount = 0
        var doneCount = 0

        devices.forEach { dev ->
            Thread {
                val ok = try {
                    val authCache = java.util.concurrent.ConcurrentHashMap<String,
                            com.burgstaller.okhttp.digest.CachingAuthenticator>()
                    val creds = com.burgstaller.okhttp.digest.Credentials(dev.username, dev.password)
                    val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                        .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                        .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds))
                        .build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, authCache))
                        .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(authCache))
                        .build()
                    val mediaType = "application/xml".toMediaType()
                    val body = okhttp3.RequestBody.create(mediaType, timeXml)
                    val req = okhttp3.Request.Builder()
                        .url("http://${dev.ip}/ISAPI/System/time")
                        .put(body).build()
                    val resp = client.newCall(req).execute()
                    android.util.Log.d("TimeSync", "${dev.ip} → ${resp.code}")
                    resp.code in 200..299
                } catch (e: Exception) {
                    android.util.Log.e("TimeSync", "${dev.ip} 실패: ${e.message}")
                    false
                }

                synchronized(this) {
                    if (ok) successCount++ else failCount++
                    doneCount++
                    if (doneCount == devices.size) {
                        handler.post {
                            Toast.makeText(this,
                                "시간 동기화 완료: ✅ ${successCount}대 성공" +
                                        if (failCount > 0) "  ❌ ${failCount}대 실패" else "",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }
    }

    // ── CH1 카메라 POS OSD 전송 ──
    private fun sendPosOsd(wind: Float?, weight: Float?) {
        val isTon = OcrSettings.isWeightTon(this)
        val windStr   = wind?.let { "풍속 %.1f m/s".format(it) } ?: "풍속 --"
        val weightStr = weight?.let {
            if (isTon) "중량 %.2f t".format(it) else "중량 %.2f kg".format(it)
        } ?: "중량 --"
        val text = "$weightStr   $windStr"

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TextOverlayList>
  <TextOverlay>
    <id>1</id>
    <enabled>true</enabled>
    <positionX>0</positionX>
    <positionY>0</positionY>
    <displayText>$text</displayText>
  </TextOverlay>
</TextOverlayList>"""

        // CH1 카메라 정보
        val ch1 = channels.firstOrNull { it.extractIp() == "192.168.0.101" }
            ?: return

        Thread {
            try {
                val authCache = java.util.concurrent.ConcurrentHashMap<String,
                        com.burgstaller.okhttp.digest.CachingAuthenticator>()
                val creds = com.burgstaller.okhttp.digest.Credentials(ch1.username, ch1.password)
                val auth = com.burgstaller.okhttp.DispatchingAuthenticator.Builder()
                    .with("digest", com.burgstaller.okhttp.digest.DigestAuthenticator(creds))
                    .with("basic", com.burgstaller.okhttp.basic.BasicAuthenticator(creds))
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .authenticator(com.burgstaller.okhttp.CachingAuthenticatorDecorator(auth, authCache))
                    .addInterceptor(com.burgstaller.okhttp.AuthenticationCacheInterceptor(authCache))
                    .build()
                val body = okhttp3.RequestBody.create("application/xml".toMediaType(), xml)
                val req = okhttp3.Request.Builder()
                    .url("http://${ch1.extractIp()}/ISAPI/System/Video/inputs/channels/1/overlays/text")
                    .put(body).build()
                val resp = client.newCall(req).execute()
                android.util.Log.d("PosOSD", "CH1 → ${resp.code}")
                resp.close()
            } catch (e: Exception) {
                android.util.Log.e("PosOSD", "오류: ${e.message}")
            }
        }.start()
    }

    private fun showAutoRegisterDialog() {
        AlertDialog.Builder(this).setTitle("⚡ 기본 장치 자동 등록")
            .setItems(arrayOf("📷 카메라 4대 (101~104)", "📼 NVR 1대 (100)")) { _, w ->
                if (w == 0) autoRegisterCameras() else autoRegisterNvr()
            }.setNegativeButton("취소", null).show()
    }

    private fun autoRegisterCameras() {
        DeviceStore.save(this, listOf(
            com.pone.towerccctv.model.Device(name="CH1", type=DeviceType.CAMERA, ip="192.168.0.101", username="admin", password="1234qwer@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH2", type=DeviceType.CAMERA, ip="192.168.0.102", username="admin", password="1q2w3e4r@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH3", type=DeviceType.CAMERA, ip="192.168.0.103", username="admin", password="1q2w3e4r@", hasPtz=true),
            com.pone.towerccctv.model.Device(name="CH4", type=DeviceType.CAMERA, ip="192.168.0.104", username="admin", password="1q2w3e4r@", hasPtz=false)
        ))
        Toast.makeText(this, "카메라 4대 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }

    private fun autoRegisterNvr() {
        DeviceStore.save(this, listOf(com.pone.towerccctv.model.Device(
            name="NVR", type=DeviceType.NVR, ip="192.168.0.100",
            username="admin", password="1q2w3e4r@", channelCount=4, hasPtz=true)))
        Toast.makeText(this, "NVR 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }
}

private fun Channel.extractIp() = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
private fun Channel.toSubStreamUrl() = if (rtspUrl.endsWith("1")) rtspUrl.dropLast(1) + "2" else rtspUrl