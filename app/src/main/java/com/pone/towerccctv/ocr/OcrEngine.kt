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

class OcrEngine(private val context: Context) {

    data class OcrResult(
        val windSpeed: Float?,
        val weight: Float?,
        val windAlert: Boolean,
        val weightAlert: Boolean,
        val rawWind: String = "",    // 디버그: ML Kit 원본 텍스트
        val rawWeight: String = ""   // 디버그: ML Kit 원본 텍스트
    )

    var onResult: ((OcrResult) -> Unit)? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var processing = false

    fun processFrame(bmp: Bitmap) {
        if (processing) return
        processing = true

        val windRoi    = OcrSettings.getWindRoi(context)
        val weightRoi  = OcrSettings.getWeightRoi(context)
        val windLimit  = OcrSettings.getWindLimit(context)
        val weightLimit = OcrSettings.getWeightLimit(context)

        Log.d("OCR", "프레임 처리 시작 ${bmp.width}x${bmp.height}")
        Log.d("OCR", "풍속ROI x=%.2f y=%.2f w=%.2f h=%.2f".format(
            windRoi.x, windRoi.y, windRoi.w, windRoi.h))
        Log.d("OCR", "중량ROI x=%.2f y=%.2f w=%.2f h=%.2f".format(
            weightRoi.x, weightRoi.y, weightRoi.w, weightRoi.h))

        val frame = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val windBmp   = cropRoi(frame, windRoi)
        val weightBmp = cropRoi(frame, weightRoi)
        frame.recycle()

        var windVal: Float?   = null
        var weightVal: Float? = null
        var rawWind   = ""
        var rawWeight = ""
        var pending = 2

        fun check() {
            if (pending > 0) return
            processing = false
            val wv = windVal; val wt = weightVal
            Log.d("OCR", "결과 → 풍속=$wv (raw: $rawWind) | 중량=$wt (raw: $rawWeight)")

            val result = OcrResult(
                windSpeed   = wv, weight = wt,
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
                rawWind = txt.text.replace("\n", " ")
                windVal = extractNumber(txt.text)
                Log.d("OCR", "풍속 raw: '$rawWind' → $windVal")
                pending--; check()
            }
            .addOnFailureListener { e ->
                Log.e("OCR", "풍속 인식 실패: ${e.message}")
                pending--; check()
            }

        recognizer.process(InputImage.fromBitmap(weightBmp, 0))
            .addOnSuccessListener { txt ->
                rawWeight = txt.text.replace("\n", " ")
                weightVal = extractNumber(txt.text)
                Log.d("OCR", "중량 raw: '$rawWeight' → $weightVal")
                pending--; check()
            }
            .addOnFailureListener { e ->
                Log.e("OCR", "중량 인식 실패: ${e.message}")
                pending--; check()
            }
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

    fun release() { recognizer.close() }
}
