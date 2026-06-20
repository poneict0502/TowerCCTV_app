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
    private var lastWeight: Float? = null
    private var lastWeightAlert = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setFullscreen()
        alertDb = AlertDatabase(this)

        vlcList   = listOf(b.vlc0, b.vlc1, b.vlc2, b.vlc3)
        dotList   = listOf(b.dot0, b.dot1, b.dot2, b.dot3)
        lblList   = listOf(b.lbl0, b.lbl1, b.lbl2, b.lbl3)
        ipList    = listOf(b.ip0,  b.ip1,  b.ip2,  b.ip3)
        touchList = listOf(b.touch0, b.touch1, b.touch2, b.touch3)

        libVLC = com.pone.towerccctv.VlcProvider.get(this)   // 전역 공유 (재생성 비용 제거)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(java.util.Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(java.util.Locale.getDefault())
                }
                tts?.setSpeechRate(0.85f)
            }
        }
        b.btnSettings.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "📋  장치 관리")
            popup.menu.add(0, 2, 1, "⚡  기본 장치 자동 등록")
            popup.menu.add(0, 3, 2, "📡  OCR / 계측 설정")
            popup.menu.add(0, 7, 3, "📸  OCR 캡처(30초 저장)")
            popup.menu.add(0, 5, 4, "🕐  카메라 시간 동기화")
            popup.menu.add(0, 6, 5, "📊  경보 이력 조회")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, DeviceListActivity::class.java))
                    2 -> showAutoRegisterDialog()
                    3 -> {
                        ocrEngine?.stopLoop()
                        OcrSettingsDialog(this) {
                            ocrEngine?.release(); ocrEngine = null
                            if (OcrSettings.isOcrEnabled(this)) {
                                startOcrLoop()
                            } else {
                                // OCR OFF: 카메라 OSD 비우고 화면 OSD 숨김
                                sendEmptyPosOsd()
                                b.osdOverlay.visibility = View.GONE
                                b.tvOcrBadge.visibility = View.GONE
                                lastWeight = null
                                lastWeightAlert = false
                                OcrSettings.clearLatest(this)
                            }
                        }.show()
                    }
                    5 -> syncCameraTime()
                    6 -> startActivity(Intent(this, AlertHistoryActivity::class.java))
                    7 -> {
                        if (ocrEngine != null && OcrSettings.isOcrEnabled(this)) {
                            ocrEngine?.startDebugCapture()
                            Toast.makeText(this,
                                "OCR 캡처 30초 저장 시작 (다운로드/AIVION/ocr)",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "먼저 OCR를 켜주세요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            popup.show()
        }
        b.btnExit.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("종료")
                .setMessage("앱을 종료하시겠습니까?")
                .setPositiveButton("종료") { _, _ -> finishAffinity() }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        // 항상 remove 후 post → 카메라 전환(재개)마다 시계 타이머가 중복 누적되는 것 방지
        handler.removeCallbacks(clockRunnable); handler.post(clockRunnable)
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
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onStop() {
        super.onStop()
        // 앱이 백그라운드로 가도 OSD 정리 (안전장치)
        try { sendEmptyPosOsd() } catch (e: Exception) {}
        handler.removeCallbacks(clockRunnable)   // 백그라운드에선 시계 타이머 정지
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ 카메라 OSD 텍스트 제거 (잘못된 값이 영상에 박히는 것 방지)
        try { sendEmptyPosOsd() } catch (e: Exception) {}
        stopAll()
        ocrEngine?.release(); ocrEngine = null
        handler.removeCallbacks(clockRunnable)
        libVLC = null   // 전역 공유 인스턴스이므로 release 하지 않음 (참조만 해제)
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private fun restartAll() { stopAll(); startAll() }

    private fun startAll() {
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

        if (OcrSettings.isOcrEnabled(this) && ocrChannelIndex >= 0) {
            startOcrLoop()
        }
    }

    private fun stopAll() {
        for (i in 0 until 4) {
            reconnectJobs[i]?.let { handler.removeCallbacks(it) }
            reconnectJobs[i] = null
        }
        for (i in 0 until 4) {
            players[i]?.stop()
            players[i]?.detachViews()
            players[i]?.release()
            players[i] = null
        }
    }

    private fun startStream(idx: Int, ch: Channel) {
        players[idx]?.stop()
        players[idx]?.detachViews()
        players[idx]?.release()
        players[idx] = null

        setDot(idx, "yellow")

        val player = MediaPlayer(libVLC)
        players[idx] = player
        player.attachViews(vlcList[idx], null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN  // 화면 꽉 채움(검은 띠 제거, 가장자리 크롭)

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
                        setDot(idx, "red"); scheduleReconnect(idx, ch)
                    }
                    MediaPlayer.Event.EndReached -> {
                        setDot(idx, "gray"); scheduleReconnect(idx, ch)
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

    private fun startOcrLoop() {
        // ★ 시작 시 모든 OCR 상태 초기화 (이전 값/경보 잔재 제거)
        lastWeight      = null
        lastWeightAlert = false
        lastAlertTime   = 0L
        com.pone.towerccctv.ocr.OcrSettings.clearLatest(this)

        // ★ 등록된 마지막 카메라(OCR 대상 = CH4 관행)의 실제 인증정보 사용
        // (하드코딩 비번 제거 - 자동등록 시 입력한 비번이 자동 적용됨)
        val devs = DeviceStore.load(this).filter { it.enabled }
        if (devs.isEmpty()) return
        val dev = devs.last()
        // 메인스트림(101) picture 사용 - ROI 설정 화면과 동일한 소스 (좌표 일치)
        val ocrUrl  = "http://${dev.ip}:${dev.httpPort}/ISAPI/Streaming/channels/101/picture"
        val ocrUser = dev.username
        val ocrPass = dev.password

        b.osdOverlay.visibility = View.VISIBLE
        b.tvOcrBadge.visibility = View.VISIBLE
        // 초기 상태로 OSD 표시 (-- 로 표시)
        updateOsd()
        // 카메라 OSD에 남아있을 수 있는 이전 텍스트도 클리어
        sendEmptyPosOsd()
        ocrEngine?.release()
        ocrEngine = OcrEngine(this).also { engine ->
            engine.onResult = { result ->
                // ★ null도 그대로 반영 (이전 값 유지 금지)
                lastWeight      = result.weight
                lastWeightAlert = result.weightAlert
                updateOsd()
                sendPosOsd(lastWeight)
            }
            engine.startHttpLoop(ocrUrl, ocrUser, ocrPass)
        }
    }

    private fun updateOsd() {
        val isTon = OcrSettings.isWeightTon(this)
        b.osdWeight.text = if (lastWeight != null) {
            if (isTon) "⚖  %.2f t".format(lastWeight) else "⚖  %.2f kg".format(lastWeight)
        } else "⚖  --"
        b.osdWeight.setTextColor(if (lastWeightAlert) Color.parseColor("#FF4444") else Color.WHITE)

        if (lastWeightAlert) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > ALERT_INTERVAL) {
                lastAlertTime = now
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
                alertDb.insert(AlertRecord(
                    dateTime    = sdf.format(java.util.Date()),
                    weight      = lastWeight,
                    weightAlert = lastWeightAlert
                ))
                b.osdOverlay.setBackgroundColor(Color.parseColor("#DD880000"))
                handler.postDelayed({ b.osdOverlay.setBackgroundColor(Color.parseColor("#DD000000")) }, 3000L)
                val msg = if (lastWeight != null) "🚨 중량 초과: %.2f t".format(lastWeight) else "🚨 중량 초과"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                playAlertSound()
            }
        }
    }

    private fun playAlertSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            handler.postDelayed({ tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }, 400L)
            handler.postDelayed({
                tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                handler.postDelayed({ tg.release() }, 400L)
            }, 800L)
        } catch (e: Exception) {}

        handler.postDelayed({
            tts?.speak("중량 초과. 중량 초과", TextToSpeech.QUEUE_FLUSH, null, "alert")
        }, 1200L)
    }

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

    private fun syncCameraTime() {
        val devices = DeviceStore.load(this).filter { it.enabled }
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
                    resp.code in 200..299
                } catch (e: Exception) {
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

    private fun sendPosOsd(weight: Float?) {
        val isTon = OcrSettings.isWeightTon(this)
        val weightStr = weight?.let {
            if (isTon) "중량 %.2f t".format(it) else "중량 %.2f kg".format(it)
        } ?: "중량 --"
        // ★ 갱신 시각을 함께 표기 → 앱이 끊기면 시각이 멈춰 stale 여부를 식별 가능
        //   (카메라 자체 시계와 비교. 네트워크 복구/앱 재시작 시 자동 갱신)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
            .format(java.util.Date())
        val text = "$weightStr   $time"

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

        val ch1 = channels.firstOrNull() ?: return

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
                resp.close()
            } catch (e: Exception) {}
        }.start()
    }

    // ── 빈 OSD 송출 (텍스트 완전 제거, enabled=false) ──
    private fun sendEmptyPosOsd() {
        sendOverlayXml(text = "", enabled = false)
    }

    // ── 공통 OSD 송출 헬퍼 (sendEmptyPosOsd 가 호출) ──
    private fun sendOverlayXml(text: String, enabled: Boolean) {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TextOverlayList>
  <TextOverlay>
    <id>1</id>
    <enabled>$enabled</enabled>
    <positionX>0</positionX>
    <positionY>0</positionY>
    <displayText>$text</displayText>
  </TextOverlay>
</TextOverlayList>"""
        // 첫 번째 등록된 카메라(CH1)에 송출 - IP 변경 대응
        val ch1 = channels.firstOrNull() ?: return
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
                resp.close()
            } catch (e: Exception) {}
        }.start()
    }

    // ── 자동 등록 다이얼로그 (카메라 4대 / NVR 1대 선택) ──
    private fun showAutoRegisterDialog() {
        AlertDialog.Builder(this).setTitle("⚡ 기본 장치 자동 등록")
            .setItems(arrayOf("📷 카메라 4대 (101~104)", "📼 NVR 1대 (100)")) { _, w ->
                if (w == 0) showAutoRegisterCamerasDialog() else showAutoRegisterNvrDialog()
            }.setNegativeButton("취소", null).show()
    }

    // ── 카메라 4대 등록 입력 다이얼로그 ──
    private fun showAutoRegisterCamerasDialog() {
        val ctx = this
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun lbl(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        fun input(default: String, hint: String): android.widget.EditText =
            android.widget.EditText(this).apply {
                setText(default); this.hint = hint
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                setBackgroundColor(Color.parseColor("#1E1E22"))
                setPadding(20, 14, 20, 14)
            }

        container.addView(lbl("사용자명"))
        val etUser = input("admin", "admin")
        container.addView(etUser)

        container.addView(lbl("비밀번호 (4대 공통)"))
        val etPass = input("", "예: 1q2w3e4r@")
        container.addView(etPass)

        container.addView(lbl("시작 IP (자동으로 +1, +2, +3)"))
        val etIp = input("192.168.0.101", "192.168.0.101")
        container.addView(etIp)

        container.addView(android.widget.TextView(this).apply {
            text = "CH4는 PTZ 없음으로 등록됨\n등록 후 장치 관리에서 IP/이름 개별 변경 가능"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this).setTitle("📷 카메라 4대 자동 등록")
            .setView(container)
            .setPositiveButton("등록") { _, _ ->
                val user    = etUser.text.toString().trim().ifEmpty { "admin" }
                val pass    = etPass.text.toString()
                val startIp = etIp.text.toString().trim().ifEmpty { "192.168.0.101" }
                autoRegisterCameras(user, pass, startIp)
            }
            .setNegativeButton("취소", null).show()
    }

    // ── NVR 1대 등록 입력 다이얼로그 ──
    private fun showAutoRegisterNvrDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun lbl(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        fun input(default: String, hint: String, numeric: Boolean = false): android.widget.EditText =
            android.widget.EditText(this).apply {
                setText(default); this.hint = hint
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setBackgroundColor(Color.parseColor("#1E1E22"))
                setPadding(20, 14, 20, 14)
            }

        container.addView(lbl("사용자명"))
        val etUser = input("admin", "admin")
        container.addView(etUser)

        container.addView(lbl("비밀번호"))
        val etPass = input("", "예: 1q2w3e4r@")
        container.addView(etPass)

        container.addView(lbl("NVR IP"))
        val etIp = input("192.168.0.100", "192.168.0.100")
        container.addView(etIp)

        container.addView(lbl("채널 수"))
        val etChCount = input("4", "4", numeric = true)
        container.addView(etChCount)

        container.addView(android.widget.TextView(this).apply {
            text = "등록 후 장치 관리에서 변경 가능"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this).setTitle("📼 NVR 1대 자동 등록")
            .setView(container)
            .setPositiveButton("등록") { _, _ ->
                val user    = etUser.text.toString().trim().ifEmpty { "admin" }
                val pass    = etPass.text.toString()
                val ip      = etIp.text.toString().trim().ifEmpty { "192.168.0.100" }
                val chCount = etChCount.text.toString().toIntOrNull() ?: 4
                autoRegisterNvr(user, pass, ip, chCount)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun autoRegisterCameras(user: String, pass: String, startIp: String) {
        val parts = startIp.split(".")
        if (parts.size != 4 || parts[3].toIntOrNull() == null) {
            Toast.makeText(this, "IP 형식이 올바르지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val prefix   = "${parts[0]}.${parts[1]}.${parts[2]}."
        val baseLast = parts[3].toInt()

        DeviceStore.save(this, listOf(
            com.pone.towerccctv.model.Device(
                name="CH1", type=DeviceType.CAMERA, ip="$prefix${baseLast}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH2", type=DeviceType.CAMERA, ip="$prefix${baseLast+1}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH3", type=DeviceType.CAMERA, ip="$prefix${baseLast+2}",
                username=user, password=pass, hasPtz=true),
            com.pone.towerccctv.model.Device(
                name="CH4", type=DeviceType.CAMERA, ip="$prefix${baseLast+3}",
                username=user, password=pass, hasPtz=false)
        ))
        Toast.makeText(this, "카메라 4대 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }

    private fun autoRegisterNvr(user: String, pass: String, ip: String, chCount: Int) {
        DeviceStore.save(this, listOf(com.pone.towerccctv.model.Device(
            name="NVR", type=DeviceType.NVR, ip=ip,
            username=user, password=pass, channelCount=chCount, hasPtz=true)))
        Toast.makeText(this, "NVR 등록 완료!", Toast.LENGTH_SHORT).show()
        restartAll()
    }
}

private fun Channel.extractIp() = httpBase.substringAfter("//").substringBefore(":").substringBefore("/")
private fun Channel.toSubStreamUrl() = if (rtspUrl.endsWith("1")) rtspUrl.dropLast(1) + "2" else rtspUrl