package com.example.nbu.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 按域名、路径和有效期管理认证 Cookie。
 */
class SimpleCookieJar : CookieJar {

    private val cookieStore = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            cookieStore.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) cookieStore.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookieStore.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookieStore.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    fun getJSessionId(url: HttpUrl): String? =
        loadForRequest(url).firstOrNull { it.name == "JSESSIONID" }?.value
}
