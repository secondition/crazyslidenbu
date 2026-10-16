package com.example.nbu

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ActivityResultTest {
    @Test
    fun pageResultIsReportedAfterDomChanges() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            val reported = CountDownLatch(1)
            var received = ""
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                val script = activity.assets.open("activity-result.js").bufferedReader().use { it.readText() }
                webView = WebView(activity)
                webView.settings.javaScriptEnabled = true
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun complete(account: String, success: Boolean, message: String) {
                        received = "$account/$success/$message"
                        reported.countDown()
                    }
                }, "SignResult")
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("$script('member');", null)
                        view.evaluateJavascript("""
                            const status = document.createElement('p');
                            status.setAttribute('uno-font', 'bold');
                            status.setAttribute('uno-text', '16px green');
                            status.textContent = '已签到';
                            document.querySelector('#app').appendChild(status);
                        """.trimIndent(), null)
                    }
                }
                webView.loadDataWithBaseURL("https://example.test/", "<div id='app'></div>", "text/html", "UTF-8", null)
            }
            try {
                assertTrue(reported.await(5, TimeUnit.SECONDS))
                assertEquals("member/true/已签到", received)
            } finally {
                scenario.onActivity { webView.destroy() }
            }
        }
    }
}
