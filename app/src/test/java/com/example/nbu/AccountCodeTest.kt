package com.example.nbu

import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.DeviceProfile
import com.example.nbu.model.PlatformSession
import com.example.nbu.model.User
import com.example.nbu.util.AccountCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class AccountCodeTest {
    @Test
    fun transfersAuthenticatedDevice() {
        val user = User("20260001", " +口令🔑 ", deviceId = UUID.randomUUID().toString(), local = true)
        val session = PlatformSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "t".repeat(87),
            "i".repeat(120), "3", requireNotNull(user.deviceId))
        val browser = BrowserProfile("Mozilla/5.0 (Linux; Android 16; wv) Mobile cpdaily/9.9.10 wisedu/9.9.10",
            UUID.randomUUID().toString().replace("-", ""))
        val device = DeviceProfile("16", "shared tablet")
        val code = AccountCode.encode(user, session, browser, device)
        QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 768, 768)
        val received = AccountCode.decode(code)
        assertEquals(user.copy(local = false), received.user)
        assertEquals(session, received.session)
        assertEquals(browser, received.browser)
        assertEquals(device, received.device)
        assertThrows(IllegalArgumentException::class.java) {
            AccountCode.encode(received.user, session, browser, device)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountCode.encode(user, session.copy(deviceId = UUID.randomUUID().toString()), browser, device)
        }
    }
}
