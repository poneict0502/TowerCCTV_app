package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * OCR 엔진
 * - 0.5초마다 CH4 HTTP 스냅샷 수신
 * - ROI 영역에서 숫자 추출
 * - 한계치 비교 후 콜백
 */
class OcrEngine(private val context: Context) {

    data class OcrResult(
        val windSpeed: Float?,   // m/s
        val weight: Float?,      // kg
        val windAlert: Boolean,
        val weightAlert: Boolean
    )

    var onResult: ((OcrResult) -> Unit)? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    // 마지막 스냅샷 (그리드 표시용)
    var lastBitmap: Bitmap? = null

    fun start(snapshotUrl: String, username: String, password: String) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    val bmp = fetchSnapshot(snapshotUrl, username, password)
                    if (bmp != null) {
                        lastBitmap = bmp
                        processOcr(bmp)
                    }
                } catch (e: Exception) {
                    Log.e("OCR", "스냅샷 오류: ${e.message}")
                }
                delay(500L)  // 0.5초 간격
            }
        }
        Log.d("OCR", "엔진 시작: $snapshotUrl")
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.d("OCR", "엔진 중지")
    }

    private fun fetchSnapshot(url: String, user: String, pass: String): Bitmap? {
        val credential = okhttp3.Credentials.basic(user, pass)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else null
        }
    }

    private fun processOcr(bmp: Bitmap) {
        val windRoi    = OcrSettings.getWindRoi(context)
        val weightRoi  = OcrSettings.getWeightRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        val windBmp   = cropRoi(bmp, windRoi)
        val weightBmp = cropRoi(bmp, weightRoi)

        var windVal: Float?   = null
        var weightVal: Float? = null
        var pending = 2

        fun check() {
            if (pending == 0) {
                val result = OcrResult(
                    windSpeed = windVal,
                    weight    = weightVal,
                    windAlert   = windVal   != null && windVal   >= windLimit,
                    weightAlert = weightVal != null && weightVal >= weightLimit
                )
                mainHandler.post { onResult?.invoke(result) }
            }
        }

        // 풍속 OCR
        val windImg = InputImage.fromBitmap(windBmp, 0)
        recognizer.process(windImg)
            .addOnSuccessListener { text ->
                windVal = extractNumber(text.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }

        // 중량 OCR
        val weightImg = InputImage.fromBitmap(weightBmp, 0)
        recognizer.process(weightImg)
            .addOnSuccessListener { text ->
                weightVal = extractNumber(text.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }
    }

    // 비트맵에서 ROI 영역 크롭
    private fun cropRoi(bmp: Bitmap, roi: OcrSettings.Roi): Bitmap {
        val x = (bmp.width  * roi.x).roundToInt().coerceIn(0, bmp.width  - 1)
        val y = (bmp.height * roi.y).roundToInt().coerceIn(0, bmp.height - 1)
        val w = (bmp.width  * roi.w).roundToInt().coerceIn(1, bmp.width  - x)
        val h = (bmp.height * roi.h).roundToInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    // 텍스트에서 숫자 추출 (소수점 포함)
    // 예: "12.3 m/s" → 12.3, "2,450kg" → 2450.0
    private fun extractNumber(text: String): Float? {
        val clean = text.replace(",", "").replace(" ", "")
        val regex = Regex("""(\d+\.?\d*)""")
        return regex.findAll(clean)
            .mapNotNull { it.value.toFloatOrNull() }
            .maxOrNull()  // 가장 큰 숫자 (계측값일 가능성 높음)
    }

    fun release() {
        stop()
        recognizer.close()
        scope.cancel()
    }
}
