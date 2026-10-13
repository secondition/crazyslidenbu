package com.example.nbu

import com.example.nbu.network.SimpleCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleCookieJarTest {
    @Test
    fun cookiesRespectScopeAndSession() {
        val url = "https://attendance.nbu.edu.cn/api/getIndex".toHttpUrl()
        val first = SimpleCookieJar()
        val second = SimpleCookieJar()
        first.saveFromResponse(url, listOf(
            Cookie.parse(url, "JSESSIONID=first; Path=/; Secure")!!,
            Cookie.parse(url, "JSESSIONID=api; Path=/api; Secure")!!
        ))
        second.saveFromResponse(url, listOf(Cookie.parse(url, "JSESSIONID=second; Path=/")!!))

        assertEquals("api", first.getJSessionId(url))
        assertEquals("second", second.getJSessionId(url))
        assertTrue(first.loadForRequest("https://uis.nbu.edu.cn/authserver/login".toHttpUrl()).isEmpty())
        assertTrue(first.loadForRequest("http://attendance.nbu.edu.cn/api/getIndex".toHttpUrl()).isEmpty())
        assertEquals("first", first.getJSessionId("https://attendance.nbu.edu.cn/h5/".toHttpUrl()))

        first.saveFromResponse(url, listOf(Cookie.parse(url, "JSESSIONID=; Path=/api; Max-Age=0")!!))
        assertEquals("first", first.getJSessionId(url))
        assertEquals("second", second.getJSessionId(url))
    }
}
