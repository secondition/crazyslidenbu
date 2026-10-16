package com.example.nbu

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityWebBinding
import com.example.nbu.model.ActivityCode
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.network.SessionExpiredException
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.example.nbu.util.requestVerificationCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

class WebActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_USERNAMES = "usernames"
        const val EXTRA_URL = "url"
        private const val PAGE_TIMEOUT = 30_000L
    }

    private lateinit var binding: ActivityWebBinding
    private lateinit var users: List<User>
    private val results = linkedMapOf<String, String>()
    private var currentUser: String? = null
    private var pageInFlight = false
    private var response: CompletableDeferred<String>? = null
    private var blankPage: CompletableDeferred<Unit>? = null
    private val resultScript by lazy { assets.open("activity-result.js").bufferedReader().use { it.readText() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))
        binding.toolbar.setNavigationOnClickListener { finish() }
        val manager = UserManager(this)
        val usernames = intent.getStringArrayListExtra(EXTRA_USERNAMES).orEmpty().distinct()
        users = usernames.mapNotNull(manager::getUser)
        val code = try {
            require(users.isNotEmpty() && users.size == usernames.size) { getString(R.string.group_login_required) }
            ActivityCode.parse(intent.getStringExtra(EXTRA_URL).orEmpty())
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: getString(R.string.activity_failed))
            return
        }
        val savedResults = savedInstanceState?.getBundle("results")
        for (username in usernames) savedResults?.getString(username)?.let { results[username] = it }
        savedInstanceState?.getString("active")?.takeIf { it in usernames && it !in results }?.let {
            results[it] = getString(R.string.activity_interrupted)
        }
        setupWebView()
        renderResults()
        lifecycleScope.launch {
            for ((index, user) in users.withIndex()) {
                if (user.username in results) continue
                currentUser = user.username
                binding.webView.visibility = View.INVISIBLE
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                binding.tvStatus.text = getString(R.string.group_activity_authorizing, user.displayName, index + 1, users.size)
                binding.progress.visibility = View.VISIBLE
                renderResults()
                try {
                    withTimeout(PAGE_TIMEOUT) {
                        binding.webView.stopLoading()
                        val blank = CompletableDeferred<Unit>()
                        blankPage = blank
                        binding.webView.loadUrl("about:blank")
                        blank.await()
                        blankPage = null
                    }
                    val cookies = NbuApiService(user.username, manager).authorizeActivity { message ->
                        requestVerificationCode("${user.displayName}\n$message", binding.content, binding.verification)
                    }.getOrThrow()
                    val webCookies = CookieManager.getInstance()
                    suspendCancellableCoroutine { continuation ->
                        webCookies.removeAllCookies { continuation.resume(Unit) }
                    }
                    for (cookie in cookies) {
                        val accepted = suspendCancellableCoroutine { continuation ->
                            webCookies.setCookie("https://${cookie.domain}/", cookie.toString()) { continuation.resume(it) }
                        }
                        check(accepted) { getString(R.string.activity_cookie_failed) }
                    }
                    binding.webView.settings.userAgentString = manager.getBrowserProfile(user.username).tabletUserAgent
                    binding.tvStatus.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                    val completion = CompletableDeferred<String>()
                    response = completion
                    pageInFlight = true
                    binding.webView.loadUrl(code.url)
                    results[user.username] = withTimeout(PAGE_TIMEOUT) { completion.await() }
                } catch (e: TimeoutCancellationException) {
                    results[user.username] = getString(R.string.activity_no_result)
                    showError(getString(R.string.activity_no_result))
                } catch (e: CancellationException) {
                    if (!isFinishing && !isDestroyed) finish()
                    throw e
                } catch (e: Exception) {
                    val message = if (e is SessionExpiredException) getString(R.string.activity_auth_required)
                        else e.message ?: getString(R.string.activity_failed)
                    results[user.username] = getString(R.string.sign_failed, message)
                    showError(message)
                } finally {
                    pageInFlight = false
                    response = null
                    renderResults()
                }
            }
            currentUser = null
            renderResults()
            if (binding.webView.visibility != View.VISIBLE) {
                binding.progress.visibility = View.GONE
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = if (users.size == 1) results[users.first().username]
                    else getString(R.string.group_activity_complete)
            }
        }
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.webView.addJavascriptInterface(ResultBridge(), "SignResult")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in listOf("https", "http")

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != "about:blank") binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank") {
                    blankPage?.complete(Unit)
                    return
                }
                if (url != view.url || response == null) return
                binding.progress.visibility = View.GONE
                if (isActivityPage(url)) {
                    val account = currentUser ?: return
                    view.evaluateJavascript("$resultScript(${JSONObject.quote(account)});", null)
                } else {
                    response?.completeExceptionally(SessionExpiredException())
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    response?.completeExceptionally(IOException(getString(R.string.activity_page_failed)))
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, result: WebResourceResponse) {
                if (request.isForMainFrame) {
                    response?.completeExceptionally(IOException(getString(R.string.activity_http_error, result.statusCode)))
                }
            }
        }
    }

    private fun isActivityPage(url: String?): Boolean = try {
        ActivityCode.parse(url.orEmpty())
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private inner class ResultBridge {
        @JavascriptInterface
        fun complete(account: String, success: Boolean, message: String) {
            runOnUiThread {
                if (account == currentUser && isActivityPage(binding.webView.url)) {
                    response?.complete(if (success) "✓ $message" else getString(R.string.sign_failed, message))
                }
            }
        }
    }

    private fun renderResults() {
        binding.tvGroupStatus.visibility = if (users.size > 1) View.VISIBLE else View.GONE
        binding.tvGroupStatus.text = users.joinToString("\n") { user ->
            val status = results[user.username] ?: getString(if (user.username == currentUser)
                R.string.activity_processing else R.string.activity_waiting)
            "${user.displayName}：$status"
        }
    }

    private fun showError(message: String) {
        binding.progress.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(R.color.status_error))
        binding.tvStatus.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("results", Bundle().apply { results.forEach { (username, message) -> putString(username, message) } })
        if (pageInFlight) outState.putString("active", currentUser)
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        currentUser = null
        binding.webView.removeJavascriptInterface("SignResult")
        binding.webView.stopLoading()
        (binding.webView.parent as ViewGroup).removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
