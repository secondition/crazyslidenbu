package com.example.nbu.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import com.journeyapps.barcodescanner.ViewfinderView

class ScanViewfinder(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private var position = 0f
    private val lineHeight = 2f * resources.displayMetrics.density
    private val sweep = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            position = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLaserVisibility(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = framingRect ?: return
        if (resultBitmap != null) return
        val top = frame.top + position * (frame.height() - lineHeight)
        paint.color = laserColor
        canvas.drawRect(frame.left.toFloat(), top, frame.right.toFloat(), top + lineHeight, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) sweep.start()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow && visibility == VISIBLE) sweep.start() else sweep.cancel()
    }

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }
}
