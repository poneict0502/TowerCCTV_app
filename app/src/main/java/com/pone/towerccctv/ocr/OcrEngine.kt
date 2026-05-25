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

    // 중량/풍속 별도 주기
    private var windTickCount = 0
    private val WIND_INTERVAL = 20  // 20회마다 풍속 인식 (0.5초 * 20 = 10초)

    fun startHttpLoop(snapshotUrl: String, username: String, password: String) {
        stopLoop()
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        val credentials = Credentials(username, password)
        val authenticator = DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(credentials))
            .with("basic", BasicAuthenticator(credentials))
            .build()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .authenticator(CachingAuthenticatorDecorator(authenticator, authCache))
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            .build()

        job = scope.launch {
            while (isActive) {
                if (!processing) {
                    try {
                        val bmp = fetchSnapshot(snapshotUrl)
                        if (bmp != null) processOcr(bmp)
                    } catch (e: Exception) {
                        Log.e("OCR", "오류: ${e.message}")
                    }
                }
                delay(500L)
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
        windTickCount++
        val doWind = (windTickCount >= WIND_INTERVAL)  // 10초마다 풍속
        if (doWind) windTickCount = 0

        val weightRoi = OcrSettings.getWeightRoi(context)
        val windRoi   = OcrSettings.getWindRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val weightBmp = cropRoi(frame, weightRoi)
        val windBmp   = if (doWind) cropRoi(frame, windRoi) else null
        frame.recycle()

        // 이전 풍속값 유지
        val prevWind = OcrSettings.getLatestWind(context)

        var weightVal: Float? = null
        var windVal: Float?   = prevWind  // 기본값: 이전값 유지
        var rawWeight = ""; var rawWind = ""
        var pending = if (doWind) 2 else 1

        fun check() {
            if (pending > 0) return
            processing = false
            val wv = windVal; val wt = weightVal
            Log.d("OCR", "중량=$wt[$rawWeight]" + if (doWind) " 풍속=$wv[$rawWind]" else "")
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

        // 중량: 매번 인식
        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { txt ->
                rawWeight = txt.text.replace("\n", " ").trim()
                weightVal = extractWeightNumber(txt.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }

        // 풍속: 10초마다만 인식
        if (doWind && windBmp != null) {
            recognizer.process(InputImage.fromBitmap(windBmp, 0))
                .addOnSuccessListener { txt ->
                    rawWind = txt.text.replace("\n", " ").trim()
                    windVal = extractWindNumber(txt.text) ?: prevWind
                    pending--; check()
                }
                .addOnFailureListener { pending--; check() }
        }
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
