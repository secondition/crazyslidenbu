package com.example.nbu.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 简单的 CookieJar 实现，用于管理 cookies
 */
class SimpleCookieJar : CookieJar {
    
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        if (cookieStore[host] == null) {
            cookieStore[host] = mutableListOf()
        }
        
        // 更新或添加 cookie
        for (newCookie in cookies) {
            cookieStore[host]?.removeAll { it.name == newCookie.name }
            cookieStore[host]?.add(newCookie)
        }
    }
    
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        return cookieStore[host]?.filter { !it.expiresAt.let { exp -> exp < System.currentTimeMillis() } } ?: emptyList()
    }
    
    /**
     * 获取指定域名的 JSESSIONID
     */
    fun getJSessionId(domain: String): String? {
        // 精确匹配
        val cookies = cookieStore[domain]
        val jsessionId = cookies?.find { it.name == "JSESSIONID" }?.value
        if (jsessionId != null) return jsessionId
        
        // 模糊匹配
        for ((host, hostCookies) in cookieStore) {
            if (host.contains(domain) || domain.contains(host)) {
                val cookie = hostCookies.find { it.name == "JSESSIONID" }
                if (cookie != null) return cookie.value
            }
        }
        
        return null
    }
    
    /**
     * 获取所有 cookies 用于调试
     */
    fun getAllCookies(): String {
        val sb = StringBuilder()
        for ((host, cookies) in cookieStore) {
            sb.append("[$host]:\n")
            for (cookie in cookies) {
                sb.append("  ${cookie.name}=${cookie.value.take(20)}...\n")
            }
        }
        return sb.toString()
    }
    
    /**
     * 清除所有 cookies
     */
    fun clear() {
        cookieStore.clear()
    }
}
