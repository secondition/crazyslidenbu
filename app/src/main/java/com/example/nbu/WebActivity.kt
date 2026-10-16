package com.example.nbu

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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
import com.example.nbu.network.NbuApiService
import com.example.nbu.network.SessionExpiredException
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.example.nbu.util.requestVerificationCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_URL = "url"
        private const val KEY_WEB = "web"
    }

    private lateinit var binding: ActivityWebBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))
        binding.toolbar.setNavigationOnClickListener { finish() }

        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val code = try {
            require(username.isNotBlank()) { "请先完成账号登录" }
            ActivityCode.parse(intent.getStringExtra(EXTRA_URL).orEmpty())
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: getString(R.string.activity_failed))
            return
        }
        val manager = UserManager(this)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = manager.getBrowserProfile(username).userAgent
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.scheme !in listOf("https", "http")
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(getString(R.string.activity_page_failed))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) showError(getString(R.string.activity_http_error, response.statusCode))
            }
        }
        lifecycleScope.launch {
            try {
                val cookies = NbuApiService(username, manager).authorizeActivity { message ->
                    requestVerificationCode(message, binding.content, binding.verification)
                }.getOrThrow()
                val webCookies = CookieManager.getInstance()
                suspendCancellableCoroutine { continuation ->
                    webCookies.removeAllCookies { continuation.resume(Unit) }
                }
                for (cookie in cookies) {
                    val accepted = suspendCancellableCoroutine { continuation ->
                        webCookies.setCookie("https://${cookie.domain}/", cookie.toString()) {
                            continuation.resume(it)
                        }
                    }
                    check(accepted) { getString(R.string.activity_cookie_failed) }
                }
                binding.tvStatus.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                val saved = savedInstanceState?.getBundle(KEY_WEB)
                if (saved == null || binding.webView.restoreState(saved) == null) {
                    binding.webView.loadUrl(code.url)
                }
            } catch (e: CancellationException) {
                if (!isFinishing && !isDestroyed) finish()
                throw e
            } catch (e: Exception) {
                showError(if (e is SessionExpiredException) getString(R.string.activity_auth_required)
                    else e.message ?: getString(R.string.activity_failed))
            }
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
        val state = Bundle()
        if (binding.webView.saveState(state) != null) outState.putBundle(KEY_WEB, state)
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
        binding.webView.stopLoading()
        (binding.webView.parent as ViewGroup).removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
