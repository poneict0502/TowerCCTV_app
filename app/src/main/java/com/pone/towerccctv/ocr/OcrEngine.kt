package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    fun startHttpLoop(snapshotUrl: String, username: String, password: String) {
        stopLoop()

        // ★ Digest + Basic 인증 (Hikvision 필수)
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
            Log.d("OCR", "루프 시작: $snapshotUrl")
            while (isActive) {
                if (!processing) {
                    try {
                        val bmp = fetchSnapshot(snapshotUrl)
                        if (bmp != null) {
                            Log.d("OCR", "스냅샷 수신 성공: ${bmp.width}x${bmp.height}")
                            processOcr(bmp)
                        } else {
                            Log.w("OCR", "스냅샷 null")
                        }
                    } catch (e: Exception) {
                        Log.e("OCR", "오류: ${e.message}")
                    }
                }
                delay(500L)
            }
        }
    }

    fun stopLoop() {
        job?.cancel(); job = null
        httpClient = null
    }

    private fun fetchSnapshot(url: String): Bitmap? {
        val client = httpClient ?: return null
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            Log.d("OCR", "HTTP 응답: ${resp.code}")
            if (resp.isSuccessful) {
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else null
        }
    }

    private fun processOcr(bmp: Bitmap) {
        processing = true
        val windRoi    = OcrSettings.getWindRoi(context)
        val weightRoi  = OcrSettings.getWeightRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val windBmp   = cropRoi(frame, windRoi)
        val weightBmp = cropRoi(frame, weightRoi)
        frame.recycle()

        var windVal: Float? = null
        var weightVal: Float? = null
        var rawWind = ""; var rawWeight = ""
        var pending = 2

        fun check() {
            if (pending > 0) return
            processing = false
            val wv = windVal; val wt = weightVal
            Log.d("OCR", "결과 → 풍속=$wv[$rawWind] 중량=$wt[$rawWeight]")
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

        recognizer.process(InputImage.fromBitmap(windBmp, 0))
            .addOnSuccessListener { txt ->
                rawWind = txt.text.replace("\n", " ").trim()
                windVal = extractNumber(txt.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }

        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { txt ->
                rawWeight = txt.text.replace("\n", " ").trim()
                weightVal = extractNumber(txt.text)
                pending--; check()
            }
            .addOnFailureListener { pending--; check() }
    }

    private fun cropRoi(bmp: Bitmap, roi: OcrSettings.Roi): Bitmap {
        val x = (bmp.width  * roi.x).roundToInt().coerceIn(0, bmp.width  - 1)
        val y = (bmp.height * roi.y).roundToInt().coerceIn(0, bmp.height - 1)
        val w = (bmp.width  * roi.w).roundToInt().coerceIn(1, bmp.width  - x)
        val h = (bmp.height * roi.h).roundToInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    private fun extractNumber(text: String): Float? {
        val clean = text.replace(",", "").replace(" ", "")
        return Regex("""(\d+\.?\d*)""").findAll(clean)
            .mapNotNull { it.value.toFloatOrNull() }
            .filter { it > 0 }
            .maxOrNull()
    }

    fun release() {
        stopLoop()
        recognizer.close()
        scope.cancel()
    }
}
