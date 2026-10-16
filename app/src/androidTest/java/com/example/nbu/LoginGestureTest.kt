package com.example.nbu

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nbu.view.InputSlider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginGestureTest {
    @Test
    fun manualRoundTripsOpenAccounts() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            onView(withId(R.id.usernameSlider)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val slider = activity.findViewById<InputSlider>(R.id.usernameSlider)
                val username = activity.findViewById<EditText>(R.id.etUsername)
                val accounts = activity.findViewById<View>(R.id.accounts)
                username.setText("20260001")
                val start = slider.width / 2f
                val end = start + (slider.parent as View).width - slider.width
                val time = SystemClock.uptimeMillis()
                var elapsed = 0L
                fun touch(action: Int, x: Float) {
                    val event = MotionEvent.obtain(time, time + elapsed, action, x, slider.height / 2f, 0)
                    slider.dispatchTouchEvent(event)
                    event.recycle()
                    elapsed += 16
                }
                touch(MotionEvent.ACTION_DOWN, start)
                repeat(3) { round ->
                    touch(MotionEvent.ACTION_MOVE, end)
                    assertEquals("", username.text.toString())
                    assertEquals(View.GONE, accounts.visibility)
                    touch(MotionEvent.ACTION_MOVE, start)
                    assertEquals(if (round == 2) View.VISIBLE else View.GONE, accounts.visibility)
                }
                touch(MotionEvent.ACTION_UP, start)
            }
        }
    }
}
