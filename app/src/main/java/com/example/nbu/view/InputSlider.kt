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
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var start = 0f
    private var reachedRight = false
    private var moved = false

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
                if (translationX >= distance - slop && !reachedRight) {
                    reachedRight = true
                    onClear?.invoke()
                } else if (reachedRight && translationX <= slop) {
                    reachedRight = false
                    onRoundTrip?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        animate().cancel()
        translationX = 0f
        super.onDetachedFromWindow()
    }
}
