package com.example.nbu

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginGestureTest {
    @Test
    fun repeatedSwipesOpenAccounts() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            for ((x, y) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
                val start = GeneralLocation.translate(GeneralLocation.CENTER, 0.25f, 0f)
                val end = GeneralLocation.translate(GeneralLocation.CENTER, 0.25f + x * 0.5f, y * 0.5f)
                onView(withId(R.id.tvTitle)).perform(*Array(5) {
                    GeneralSwipeAction(Swipe.FAST, start, end, Press.FINGER)
                })
                onView(withId(R.id.btnClose)).check(matches(isDisplayed())).perform(click())
            }
        }
    }
}
