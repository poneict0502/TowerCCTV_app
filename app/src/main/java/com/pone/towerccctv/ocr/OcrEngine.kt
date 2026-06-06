package com.pone.towerccctv.ocr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OcrEngine(private val context: Context) {

    data class OcrResult(
        val weight: Float?,
        val weightAlert: Boolean,
        val rawWeight: String = ""
    )

    var onResult: ((OcrResult) -> Unit)? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var processing = false
    private var httpClient: OkHttpClient? = null

    // 폴링 주기 - 원본 동작 유지 (스냅샷과 RTSP는 독립이라 충돌 없음)
    private val POLL_INTERVAL_MS = 500L

    // 적색 FND 이진화 임계값 (현장 환경에 따라 미세조정)
    private val RED_MIN = 90        // 켜진 적색 세그먼트로 볼 최소 빨강 밝기
    private val RED_DOMINANCE = 35  // 빨강이 초록/파랑보다 우세해야 하는 정도

    // ── 디버그 캡처: 일정 시간 동안 매 프레임(원본+이진화) 저장 ──
    private var captureUntil = 0L   // 이 시각(ms) 전까지 캡처 저장
    private var captureCount = 0

    /** 지정 시간(기본 30초)동안 매 OCR 프레임을 다운로드/AIVION/ocr 에 저장 시작 */
    fun startDebugCapture(durationMs: Long = 30_000L) {
        captureUntil = System.currentTimeMillis() + durationMs
        captureCount = 0
        Log.d("OCR", "디버그 캡처 시작 (${durationMs}ms)")
    }

    fun startHttpLoop(snapshotUrl: String, username: String, password: String) {
        stopLoop()
        Log.d("OCR", "시작: $snapshotUrl (user=$username)")
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        val credentials = Credentials(username, password)
        val authenticator = DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(credentials))
            .with("basic", BasicAuthenticator(credentials))
            .build()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .authenticator(CachingAuthenticatorDecorator(authenticator, authCache))
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            .build()

        // 연속 실패 카운트 - 5회 실패 시 자동 정지 (인증오류 무한반복 방지)
        var failStreak = 0

        job = scope.launch {
            while (isActive) {
                if (!processing) {
                    try {
                        val bmp = fetchSnapshot(snapshotUrl)
                        if (bmp != null) {
                            failStreak = 0
                            processOcr(bmp)
                        } else {
                            failStreak++
                            if (failStreak >= 5) {
                                Log.w("OCR", "연속 실패 ${failStreak}회 - 자동 정지")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OCR", "오류: ${e.message}")
                        failStreak++
                        if (failStreak >= 5) {
                            Log.w("OCR", "연속 실패 ${failStreak}회 - 자동 정지")
                            break
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopLoop() { job?.cancel(); job = null; httpClient = null }

    private fun fetchSnapshot(url: String): Bitmap? {
        val client = httpClient ?: return null
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            else { Log.w("OCR", "HTTP ${resp.code}"); null }
        }
    }

    private fun processOcr(bmp: Bitmap) {
        processing = true
        val weightRoi = OcrSettings.getWeightRoi(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val weightBmp = cropRoi(frame, weightRoi)
        frame.recycle()

        fun emit(weightVal: Float?, rawWeight: String) {
            processing = false
            Log.d("OCR", "중량=$weightVal[$rawWeight]")
            maybeSaveDebug(bmp, weightBmp, weightVal)
            val result = OcrResult(
                weight = weightVal,
                weightAlert = weightVal != null && weightVal >= weightLimit,
                rawWeight = rawWeight
            )
            OcrSettings.saveLatestValues(context, weightVal, result.weightAlert)
            mainHandler.post { onResult?.invoke(result) }
        }

        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { txt ->
                emit(extractWeightNumber(txt.text), txt.text.replace("\n", " ").trim())
            }
            .addOnFailureListener { emit(null, "") }
    }

    private fun cropRoi(bmp: Bitmap, roi: OcrSettings.Roi): Bitmap {
        val x = (bmp.width  * roi.x).roundToInt().coerceIn(0, bmp.width  - 1)
        val y = (bmp.height * roi.y).roundToInt().coerceIn(0, bmp.height - 1)
        val w = (bmp.width  * roi.w).roundToInt().coerceIn(1, bmp.width  - x)
        val h = (bmp.height * roi.h).roundToInt().coerceIn(1, bmp.height - y)
        val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
        // 적색 7세그먼트(FND) → 검은 글자 / 흰 배경 이진화
        val binary = binarizeRedFnd(cropped)
        cropped.recycle()
        // ML Kit 인식률 향상을 위해 3배 확대 (필터링으로 획 가장자리 부드럽게)
        val scaled = Bitmap.createScaledBitmap(binary, w * 3, h * 3, true)
        if (scaled != binary) binary.recycle()
        return scaled
    }

    /**
     * 적색 FND(7세그먼트) 전용 이진화.
     * - 켜진 적색 세그먼트(R 우세) → 검은 글자
     * - 어두운 배경 / 흰색 라벨(WEIGHT, Ton 등) → 흰 배경
     * ML Kit은 밝은 배경의 어두운 글자를 가장 잘 인식하므로 반전한다.
     */
    private fun binarizeRedFnd(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 적색 발광 픽셀: 빨강이 충분히 밝고(RED_MIN), G/B보다 우세(RED_DOMINANCE)
            val lit = r >= RED_MIN && (r - maxOf(g, b)) >= RED_DOMINANCE
            px[i] = if (lit) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // 캡처 구간이면 원본+이진화 이미지를 저장. 구간이 끝나면 1회 완료 알림.
    private fun maybeSaveDebug(original: Bitmap, binary: Bitmap, weight: Float?) {
        val now = System.currentTimeMillis()
        if (now >= captureUntil) {
            if (captureCount > 0) {
                val n = captureCount; captureCount = 0
                mainHandler.post {
                    android.widget.Toast.makeText(context,
                        "OCR 캡처 저장 완료: ${n}장 (다운로드/AIVION/ocr)",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        captureCount++
        // 각 프레임 비트맵은 고유하고 다른 곳에서 재사용/recycle 되지 않으므로
        // 복사 없이 IO 스레드에서 바로 압축 저장 (콜백 = 메인 스레드 차단 방지)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.KOREA)
            .format(java.util.Date(now))
        val wTag = weight?.let { "_w%.2f".format(it) } ?: "_wNA"
        scope.launch {
            try {
                saveToDownloads("ocr_${stamp}${wTag}_orig.jpg", "image/jpeg") {
                    original.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                saveToDownloads("ocr_${stamp}${wTag}_bin.png", "image/png") {
                    binary.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (e: Exception) {
                Log.e("OCR", "디버그 저장 실패: ${e.message}")
            }
        }
    }

    private fun saveToDownloads(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AIVION/ocr")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { write(it) }
    }

    fun extractWeightNumber(text: String): Float? {
        val decimalPattern = Regex("""(\d{1,3}\.\d{1,2})""")
        val decimals = decimalPattern.findAll(text)
            .mapNotNull { it.value.toFloatOrNull() }
            .filter { it in 0.0f..100.0f }
            .toList()
        if (decimals.isNotEmpty()) return decimals.minOrNull()
        return Regex("""(\d{1,3})""").findAll(text)
            .mapNotNull { it.value.toFloatOrNull() }
            .filter { it in 0.0f..100.0f }
            .minOrNull()
    }

    fun release() { stopLoop(); recognizer.close(); scope.cancel() }
}