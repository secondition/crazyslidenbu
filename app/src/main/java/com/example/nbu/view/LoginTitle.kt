package com.example.nbu.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.hypot

class LoginTitle(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {

    private var swipeCount = 0
    private var lastSwipeTime = 0L
    private var directionX = 1f
    private var directionY = 0f
    private var opening = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onFling(
            start: MotionEvent?,
            end: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            swipeCount = if (end.eventTime - lastSwipeTime <= 1000L) swipeCount + 1 else 1
            lastSwipeTime = end.eventTime
            val speed = hypot(velocityX, velocityY)
            directionX = velocityX / speed
            directionY = velocityY / speed
            return true
        }
    }).apply { setOnDoubleTapListener(null) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (opening) return true
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        if (swipeCount >= 2) performClick()
        return handled
    }

    override fun performClick(): Boolean {
        if (!isEnabled || opening) return false
        swipeCount = 0
        opening = true
        val distance = hypot(rootView.width.toFloat(), rootView.height.toFloat())
        animate()
            .translationX(directionX * distance)
            .translationY(directionY * distance)
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (isEnabled && isAttachedToWindow) super.performClick() else reset()
            }
            .start()
        return true
    }

    fun reset() {
        animate().cancel()
        translationX = 0f
        translationY = 0f
        swipeCount = 0
        lastSwipeTime = 0L
        opening = false
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }
}
