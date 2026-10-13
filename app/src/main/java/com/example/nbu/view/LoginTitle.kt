package com.example.nbu.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

class LoginTitle(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {

    private var swipeCount = 0
    private var lastSwipeTime = 0L

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            val textLayout = layout ?: return false
            val start = text.indexOf("宁大")
            if (start < 0) return false
            val x = event.x - totalPaddingLeft + scrollX
            val y = event.y - totalPaddingTop + scrollY
            val line = textLayout.getLineForOffset(start)
            val withinTitle = x >= textLayout.getPrimaryHorizontal(start) &&
                x <= textLayout.getPrimaryHorizontal(start + 2) &&
                y >= textLayout.getLineTop(line) && y <= textLayout.getLineBottom(line)
            if (withinTitle) parent.requestDisallowInterceptTouchEvent(true)
            return withinTitle
        }

        override fun onFling(
            start: MotionEvent?,
            end: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            swipeCount = if (end.eventTime - lastSwipeTime <= 1000L) swipeCount + 1 else 1
            lastSwipeTime = end.eventTime
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        if (swipeCount >= 5) performClick()
        return handled
    }

    override fun performClick(): Boolean {
        swipeCount = 0
        return super.performClick()
    }
}
