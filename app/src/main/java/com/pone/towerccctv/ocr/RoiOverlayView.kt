package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * ROI 드래그 설정 뷰
 * 사용자가 손가락으로 영역을 드래그하여 풍속/중량 ROI 지정
 */
class RoiOverlayView(context: Context) : View(context) {

    enum class RoiMode { WIND, WEIGHT, NONE }

    var currentMode = RoiMode.NONE
    var onRoiSet: ((mode: RoiMode, roi: OcrSettings.Roi) -> Unit)? = null

    private var startX = 0f; private var startY = 0f
    private var endX = 0f;   private var endY = 0f
    private var drawing = false

    // 저장된 ROI
    private var windRect: RectF? = null
    private var weightRect: RectF? = null

    private val paintWind = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
        color = Color.parseColor("#2979FF")  // 파란색 = 풍속
    }
    private val paintWeight = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
        color = Color.parseColor("#FF9800")  // 주황색 = 중량
    }
    private val paintFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#332979FF")
    }
    private val paintFillW = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33FF9800")
    }
    private val paintText = Paint().apply {
        color = Color.WHITE; textSize = 32f; isFakeBoldText = true
    }
    private val paintCurrent = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 저장된 풍속 ROI
        windRect?.let {
            canvas.drawRect(it, paintFill)
            canvas.drawRect(it, paintWind)
            canvas.drawText("🌬 풍속", it.left + 8, it.top + 36, paintText)
        }
        // 저장된 중량 ROI
        weightRect?.let {
            canvas.drawRect(it, paintFillW)
            canvas.drawRect(it, paintWeight)
            canvas.drawText("⚖ 중량", it.left + 8, it.top + 36, paintText)
        }
        // 현재 드래그 중
        if (drawing) {
            val rect = RectF(
                minOf(startX, endX), minOf(startY, endY),
                maxOf(startX, endX), maxOf(startY, endY)
            )
            canvas.drawRect(rect, paintCurrent)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentMode == RoiMode.NONE) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX = event.x;   endY = event.y
                drawing = true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                drawing = false
                saveRoi()
                invalidate()
            }
        }
        return true
    }

    private fun saveRoi() {
        if (width == 0 || height == 0) return
        val x = minOf(startX, endX) / width
        val y = minOf(startY, endY) / height
        val w = Math.abs(endX - startX) / width
        val h = Math.abs(endY - startY) / height
        if (w < 0.05f || h < 0.05f) return  // 너무 작으면 무시

        val roi = OcrSettings.Roi(x, y, w, h)
        when (currentMode) {
            RoiMode.WIND -> {
                windRect = RectF(minOf(startX,endX), minOf(startY,endY),
                                 maxOf(startX,endX), maxOf(startY,endY))
            }
            RoiMode.WEIGHT -> {
                weightRect = RectF(minOf(startX,endX), minOf(startY,endY),
                                   maxOf(startX,endX), maxOf(startY,endY))
            }
            else -> {}
        }
        onRoiSet?.invoke(currentMode, roi)
    }

    fun loadSavedRois(context: Context) {
        // 저장된 ROI를 화면 좌표로 변환
        post {
            if (width == 0 || height == 0) return@post
            val wr = OcrSettings.getWindRoi(context)
            windRect = RectF(wr.x*width, wr.y*height,
                (wr.x+wr.w)*width, (wr.y+wr.h)*height)
            val gr = OcrSettings.getWeightRoi(context)
            weightRect = RectF(gr.x*width, gr.y*height,
                (gr.x+gr.w)*width, (gr.y+gr.h)*height)
            invalidate()
        }
    }
}
