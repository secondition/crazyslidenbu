package com.example.nbu.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView

class InputSlider(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {
    var onClear: (() -> Unit)? = null
    var onRoundTrip: (() -> Unit)? = null
    var onHoldRight: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var start = 0f
    private var reachedRight = false
    private var moved = false
    private var waiting = false
    private val holdRight = Runnable {
        waiting = false
        onHoldRight?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                start = event.rawX - translationX
                reachedRight = false
                moved = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = ((parent as View).width - width).toFloat()
                if (distance <= 0f) return true
                translationX = (event.rawX - start).coerceIn(0f, distance)
                moved = moved || translationX > slop
                val atRight = translationX >= distance - slop
                if (atRight && !reachedRight) {
                    reachedRight = true
                    onClear?.invoke()
                } else if (reachedRight && translationX <= slop) {
                    reachedRight = false
                    onRoundTrip?.invoke()
                }
                if (atRight && !waiting) {
                    waiting = true
                    postDelayed(holdRight, HOLD_DURATION)
                } else if (!atRight && waiting) {
                    waiting = false
                    removeCallbacks(holdRight)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                waiting = false
                removeCallbacks(holdRight)
                parent.requestDisallowInterceptTouchEvent(false)
                animate().translationX(0f).setDuration(180).start()
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClear?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        waiting = false
        removeCallbacks(holdRight)
        animate().cancel()
        translationX = 0f
        super.onDetachedFromWindow()
    }

    private companion object {
        const val HOLD_DURATION = 3_000L
    }
}
