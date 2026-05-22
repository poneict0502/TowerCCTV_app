package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.roundToInt

/**
 * OCR 엔진 (영상 프레임 기반)
 * - MainActivity가 0.5초마다 TextureView 프레임을 캡처해서 processFrame() 호출
 * - ROI 영역 크롭 후 ML Kit OCR
 * - 결과를 SharedPreferences에 저장 + 콜백
 */
class OcrEngine(private val context: Context) {

    data class OcrResult(
        val windSpeed: Float?,
        val weight: Float?,
        val windAlert: Boolean,
        val weightAlert: Boolean
    )

    var onResult: ((OcrResult) -> Unit)? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var processing = false  // 중복 처리 방지

    /**
     * 영상 프레임에서 OCR 실행
     * MainActivity에서 0.5초마다 호출
     */
    fun processFrame(bmp: Bitmap) {
        if (processing) return  // 이전 처리 중이면 스킵
        processing = true

        val windRoi    = OcrSettings.getWindRoi(context)
        val weightRoi  = OcrSettings.getWeightRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        // 비트맵 복사 (원본 재활용 방지)
        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)

        val windBmp   = cropRoi(frame, windRoi)
        val weightBmp = cropRoi(frame, weightRoi)
        frame.recycle()

        var windVal: Float?   = null
        var weightVal: Float? = null
        var pending = 2

        fun check() {
            if (pending > 0) return
            processing = false
            val wv = windVal; val wt = weightVal
            val result = OcrResult(
                windSpeed   = wv,
                weight      = wt,
                windAlert   = wv != null && wv >= windLimit,
                weightAlert = wt != null && wt >= weightLimit
            )
            OcrSettings.saveLatestValues(context, wv, wt,
                result.windAlert, result.weightAlert)
            mainHandler.post { onResult?.invoke(result) }
        }

        recognizer.process(InputImage.fromBitmap(windBmp, 0))
            .addOnSuccessListener { windVal = extractNumber(it.text); pending--; check() }
            .addOnFailureListener { pending--; check() }

        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { weightVal = extractNumber(it.text); pending--; check() }
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
            .maxOrNull()
    }

    fun release() {
        recognizer.close()
    }
}
