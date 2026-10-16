package com.example.nbu.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.animation.doOnEnd
import com.example.nbu.R
import kotlin.math.abs

class LampCord(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val length = 64f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_primary)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var pull = 0f
    private var start = 0f
    private var returning: ValueAnimator? = null
    private var toggle = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        canvas.drawLine(x, 0f, x, length + pull, paint)
        canvas.drawCircle(x, length + pull, 7f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(event.y - length - pull) > 24f * density) return false
                toggle = false
                returning?.cancel()
                start = event.rawY - pull
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                pull = (event.rawY - start).coerceIn(0f, 52f * density)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                toggle = event.actionMasked == MotionEvent.ACTION_UP && pull >= 32f * density
                returning = ValueAnimator.ofFloat(pull, 0f).apply {
                    duration = 180
                    addUpdateListener {
                        pull = it.animatedValue as Float
                        invalidate()
                    }
                    doOnEnd {
                        if (toggle) {
                            toggle = false
                            performClick()
                        }
                    }
                    start()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        toggle = false
        returning?.cancel()
        super.onDetachedFromWindow()
    }
}
