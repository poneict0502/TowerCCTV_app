package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
        val windSpeed: Float?,
        val weight: Float?,
        val windAlert: Boolean,
        val weightAlert: Boolean,
        val rawWind: String = "",
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
        val windRoi   = OcrSettings.getWindRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val weightBmp = cropRoi(frame, weightRoi)
        val windBmp   = cropRoi(frame, windRoi)
        frame.recycle()

        var weightVal: Float? = null
        var windVal: Float?   = null
        var rawWeight = ""; var rawWind = ""
        var pending = 2

        fun check() {
            if (pending > 0) return
            processing = false
            val wv = windVal; val wt = weightVal
            Log.d("OCR", "중량=$wt[$rawWeight] 풍속=$wv[$rawWind]")
            val result = OcrResult(
                windSpeed = wv, weight = wt,
                windAlert   = wv != null && wv >= windLimit,
                weightAlert = wt != null && wt >= weightLimit,
                rawWind = rawWind, rawWeight = rawWeight
            )
            OcrSettings.saveLatestValues(context, wv, wt,
                result.windAlert, result.weightAlert)
            mainHandler.post { onResult?.invoke(result) }
        }

        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { txt ->
                rawWeight = txt.text.replace("\n", " ").trim()
                weightVal = extractWeightNumber(txt.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }

        recognizer.process(InputImage.fromBitmap(windBmp, 0))
            .addOnSuccessListener { txt ->
                rawWind = txt.text.replace("\n", " ").trim()
                windVal = extractWindNumber(txt.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }
    }

    private fun cropRoi(bmp: Bitmap, roi: OcrSettings.Roi): Bitmap {
        val x = (bmp.width  * roi.x).roundToInt().coerceIn(0, bmp.width  - 1)
        val y = (bmp.height * roi.y).roundToInt().coerceIn(0, bmp.height - 1)
        val w = (bmp.width  * roi.w).roundToInt().coerceIn(1, bmp.width  - x)
        val h = (bmp.height * roi.h).roundToInt().coerceIn(1, bmp.height - y)
        val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
        val scaled = Bitmap.createScaledBitmap(cropped, w * 2, h * 2, true)
        cropped.recycle()
        return enhanceContrast(scaled)
    }

    private fun enhanceContrast(bmp: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix(floatArrayOf(
            1.5f, 0f, 0f, 0f, -30f,
            0f, 1.5f, 0f, 0f, -30f,
            0f, 0f, 1.5f, 0f, -30f,
            0f, 0f, 0f, 1f,   0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        bmp.recycle()
        return result
    }

    fun extractWindNumber(text: String): Float? {
        val clean = text.replace(",", "").replace(" ", "")
        return Regex("""(\d+\.?\d*)""").findAll(clean)
            .mapNotNull { it.value.toFloatOrNull() }
            .filter { it in 0.1f..50.0f }
            .maxOrNull()
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