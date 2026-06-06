package com.pone.towerccctv.ocr

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class RoiOverlayView(context: Context) : View(context) {

    enum class RoiMode { WEIGHT, NONE }

    var currentMode = RoiMode.NONE
    var onRoiSet: ((mode: RoiMode, roi: OcrSettings.Roi) -> Unit)? = null

    // 실제 이미지 크기 (OCR 처리 이미지 기준)
    var imageWidth: Int = 0
    var imageHeight: Int = 0

    private var startX = 0f; private var startY = 0f
    private var endX = 0f;   private var endY = 0f
    private var drawing = false

    private var weightRect: RectF? = null

    private val paintWeight = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f
        color = Color.parseColor("#FF9800")
    }
    private val paintFillWeight = Paint().apply {
        style = Paint.Style.FILL; color = Color.parseColor("#55FF9800")
    }
    private val paintText = Paint().apply {
        color = Color.WHITE; textSize = 40f; isFakeBoldText = true
    }
    private val paintDash = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(15f, 8f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        weightRect?.let {
            canvas.drawRect(it, paintFillWeight)
            canvas.drawRect(it, paintWeight)
            canvas.drawText("⚖ 중량", it.left + 10, it.top + 50, paintText)
        }
        if (drawing) {
            canvas.drawRect(
                RectF(minOf(startX,endX), minOf(startY,endY),
                      maxOf(startX,endX), maxOf(startY,endY)), paintDash)
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
                endX = event.x; endY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                drawing = false; saveRoi(); invalidate()
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
        if (w < 0.03f || h < 0.03f) return

        android.util.Log.d("ROI", "저장: x=$x y=$y w=$w h=$h (뷰 ${width}x${height})")

        val roi = OcrSettings.Roi(x, y, w, h)
        if (currentMode == RoiMode.WEIGHT) {
            weightRect = RectF(minOf(startX,endX), minOf(startY,endY),
                               maxOf(startX,endX), maxOf(startY,endY))
        }
        onRoiSet?.invoke(currentMode, roi)
    }

    fun loadSavedRois(context: Context) {
        post {
            if (width == 0 || height == 0) return@post
            val gr = OcrSettings.getWeightRoi(context)
            weightRect = RectF(gr.x*width, gr.y*height,
                (gr.x+gr.w)*width, (gr.y+gr.h)*height)
            invalidate()
        }
    }
}
