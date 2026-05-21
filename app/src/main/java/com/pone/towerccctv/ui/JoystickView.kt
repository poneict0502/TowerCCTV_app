package com.pone.towerccctv.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((angle: Double, magnitude: Float) -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    private var tx = 0f; private var ty = 0f; private var active = false

    private val bgP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#252528"); style = Paint.Style.FILL }
    private val ringP= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3E"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val arrP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555560"); style = Paint.Style.FILL }
    private val tP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2979FF"); style = Paint.Style.FILL }
    private val tRP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B9BFF"); style = Paint.Style.STROKE; strokeWidth = 2f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { tx = w/2f; ty = h/2f }

    override fun onDraw(canvas: Canvas) {
        val cx = width/2f; val cy = height/2f; val r = min(cx,cy)-6f; val tr = r*0.32f
        canvas.drawCircle(cx, cy, r, bgP); canvas.drawCircle(cx, cy, r, ringP)
        canvas.drawCircle(cx, cy, r*0.55f, ringP.apply { alpha = 60 }); ringP.alpha = 255
        listOf(0f,90f,180f,270f).forEach { deg ->
            canvas.save(); canvas.translate(cx,cy); canvas.rotate(deg)
            val d = r*0.72f
            canvas.drawPath(Path().apply { moveTo(0f,-(d+r*0.14f)); lineTo(r*0.1f,-d); lineTo(-r*0.1f,-d); close() }, arrP)
            canvas.restore()
        }
        canvas.drawCircle(tx, ty, tr, tP); canvas.drawCircle(tx, ty, tr, tRP)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val cx = width/2f; val cy = height/2f; val max = min(cx,cy)*0.65f
        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                active = true
                val dx = ev.x-cx; val dy = ev.y-cy; val dist = sqrt(dx*dx+dy*dy)
                val cl = min(dist, max); val angle = atan2(-dy.toDouble(), dx.toDouble())
                if (dist > 0) { tx = cx+dx/dist*cl; ty = cy+dy/dist*cl }
                invalidate(); onMove?.invoke(angle, (cl/max).toFloat()); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false; tx = width/2f; ty = height/2f
                invalidate(); onRelease?.invoke(); return true
            }
        }
        return false
    }
}
