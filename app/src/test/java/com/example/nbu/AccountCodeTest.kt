package com.example.nbu

import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.DeviceProfile
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
    fun transfersAccountIdentity() {
        val user = User("20260001", " +口令🔑 ", deviceId = UUID.randomUUID().toString(), local = true)
        val browser = BrowserProfile("Mozilla/5.0 (Linux; Android 16; wv) Mobile cpdaily/9.9.10 wisedu/9.9.10",
            UUID.randomUUID().toString().replace("-", ""))
        val device = DeviceProfile("16", "shared tablet")
        val code = AccountCode.encode(user, browser, device)
        QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 768, 768)
        val received = AccountCode.decode(code)
        assertEquals(user.copy(local = false), received.user)
        assertEquals(browser, received.browser)
        assertEquals(device, received.device)
        assertThrows(IllegalArgumentException::class.java) {
            AccountCode.encode(received.user, browser, device)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountCode.encode(user.copy(deviceId = "invalid"), browser, device)
        }
    }
}
