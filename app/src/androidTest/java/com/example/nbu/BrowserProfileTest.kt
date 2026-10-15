package com.example.nbu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nbu.util.UserManager
import com.example.nbu.model.User
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserProfileTest {
    @Test
    fun browserPersistsPerAccountAcrossSessions() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manager = UserManager(context)
        val username = UUID.randomUUID().toString()
        val browser = manager.getBrowserProfile(username)
        val otherBrowser = manager.getBrowserProfile(UUID.randomUUID().toString())

        manager.saveSession(username, "session")
        manager.clearSession(username)

        assertEquals(browser, UserManager(context).getBrowserProfile(username))
        assertNotEquals(browser.fingerprint, otherBrowser.fingerprint)
    }

    @Test
    fun accountsKeepTheirDeviceIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manager = UserManager(context)
        val first = User(UUID.randomUUID().toString(), "first", deviceId = UUID.randomUUID().toString())
        val second = User(UUID.randomUUID().toString(), "second", deviceId = UUID.randomUUID().toString())
        manager.saveUser(first)
        manager.saveUser(second)
        val restored = UserManager(context)
        assertEquals(first.deviceId, restored.getDeviceId(first.username))
        assertEquals(second.deviceId, restored.getDeviceId(second.username))
    }
}
