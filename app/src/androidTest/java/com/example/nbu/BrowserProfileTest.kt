package com.example.nbu

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nbu.util.UserManager
import com.example.nbu.model.User
import com.example.nbu.model.PlatformSession
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        context.getSharedPreferences("nbu_users", Context.MODE_PRIVATE).edit {
            putString("device_id", UUID.randomUUID().toString())
        }
        val ordinary = User(UUID.randomUUID().toString(), "ordinary")
        val first = User(UUID.randomUUID().toString(), "first", deviceId = UUID.randomUUID().toString())
        val second = User(UUID.randomUUID().toString(), "second", deviceId = UUID.randomUUID().toString())
        manager.saveUser(ordinary)
        manager.saveUser(first)
        manager.saveUser(second)
        val restored = UserManager(context)
        assertNull(restored.getDeviceId(ordinary.username))
        assertEquals(first.deviceId, restored.getDeviceId(first.username))
        assertEquals(second.deviceId, restored.getDeviceId(second.username))
        manager.saveSession(first.username, "course")
        manager.savePlatformSession(first.username, PlatformSession("token", "user", "tgc", "ids", "3"))
        val rebound = first.copy(deviceId = UUID.randomUUID().toString())
        manager.saveUser(rebound)
        assertEquals(rebound.deviceId, restored.getDeviceId(first.username))
        assertNull(restored.getPlatformSession(first.username))
        assertEquals("course", restored.getCachedSession(first.username))
    }
}
