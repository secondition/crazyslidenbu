package com.example.nbu

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nbu.view.LoginTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LoginGestureTest {
    @Test
    fun repeatedSwipesOpenAccounts() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            for ((x, y) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
                val opened = CountDownLatch(1)
                it.onActivity { activity ->
                    activity.findViewById<LoginTitle>(R.id.tvTitle).setOnClickListener { opened.countDown() }
                }
                val swipe = GeneralSwipeAction(
                    Swipe.FAST,
                    GeneralLocation.CENTER,
                    GeneralLocation.translate(GeneralLocation.CENTER, x * 0.5f, y * 0.5f),
                    Press.FINGER
                )
                repeat(3) {
                    onView(withId(R.id.tvTitle)).perform(swipe)
                    assertEquals(1L, opened.count)
                }
                onView(withId(R.id.tvTitle)).perform(swipe)
                assertTrue(opened.await(2, TimeUnit.SECONDS))
                it.onActivity { activity ->
                    val title = activity.findViewById<LoginTitle>(R.id.tvTitle)
                    if (x != 0f) assertTrue(title.translationX * x >= activity.window.decorView.width)
                    if (y != 0f) assertTrue(title.translationY * y >= activity.window.decorView.height)
                    val prefix = activity.findViewById<android.view.View>(R.id.tvTitlePrefix)
                    assertEquals(0f, prefix.translationX, 0f)
                    assertEquals(0f, prefix.translationY, 0f)
                    title.reset()
                    assertEquals(0f, title.translationX, 0f)
                    assertEquals(0f, title.translationY, 0f)
                }
            }
        }
    }
}
