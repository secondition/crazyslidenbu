package com.example.nbu

import com.example.nbu.model.ActivityCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCodeTest {
    @Test
    fun parsesActivitySignAndClockinCodes() {
        val link = "${ActivityCode.BASE_URL}/mobile/entry-scan/index.html#/?uuid=qr&toolCode=1&taskWid=12&wid=34&signType=in"
        val sign = ActivityCode.parse(link)
        assertEquals("12", sign.taskWid)
        assertEquals("34", sign.wid)
        assertTrue(sign.isSignin)
        assertEquals(link, sign.url)
        val clockin = ActivityCode.parse(link.replace("toolCode=1", "toolCode=9").replace("signType=in", "signType=out"))
        assertEquals("9", clockin.toolCode)
        assertFalse(clockin.isSignin)
        assertThrows(IllegalArgumentException::class.java) { ActivityCode.parse(link.replace("nbu.campusphere.net", "example.com")) }
    }
}
