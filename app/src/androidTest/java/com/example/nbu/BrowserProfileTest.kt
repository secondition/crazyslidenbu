package com.example.nbu

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.DeviceProfile
import com.example.nbu.model.User
import com.example.nbu.util.AccountCode
import com.example.nbu.util.UserManager
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun importedAccountRestoresSharedIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manager = UserManager(context)
        val user = User(UUID.randomUUID().toString(), "shared", deviceId = UUID.randomUUID().toString())
        manager.saveUser(user.copy(password = "old", remark = "group"))
        manager.saveSession(user.username, "old")
        val browser = BrowserProfile("shared browser", "shared fingerprint")
        val device = DeviceProfile("16", "shared tablet")
        manager.importAccount(AccountCode.Account(user, browser, device))

        val restored = UserManager(context)
        assertEquals(user.copy(remark = "group"), restored.getUser(user.username))
        assertEquals(browser, restored.getBrowserProfile(user.username))
        assertEquals(device, restored.getDeviceProfile(user.username))
        assertNull(restored.getCachedSession(user.username))
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
        assertEquals(manager.getLocalDeviceId(), restored.readDeviceId())
        assertNull(restored.getDeviceId(ordinary.username))
        assertEquals(first.deviceId, restored.getDeviceId(first.username))
        assertEquals(second.deviceId, restored.getDeviceId(second.username))
        manager.saveSession(first.username, "course")
        val rebound = first.copy(deviceId = UUID.randomUUID().toString())
        manager.saveUser(rebound)
        assertEquals(rebound.deviceId, restored.getDeviceId(first.username))
        assertEquals("course", restored.getCachedSession(first.username))

        val bound = ordinary.copy(deviceId = UUID.randomUUID().toString(), local = true)
        manager.saveUser(bound)
        assertEquals(bound, restored.getUser(bound.username))
    }

    @Test
    fun updatesKeepBoundAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manager = UserManager(context)
        val bound = User(UUID.randomUUID().toString(), "bound", deviceId = UUID.randomUUID().toString(), local = true)
        val unbound = User(UUID.randomUUID().toString(), "unbound")
        for (user in listOf(bound, unbound)) {
            manager.saveUser(user)
            manager.saveSession(user.username, "course")
            manager.getBrowserProfile(user.username)
            manager.getDeviceProfile(user.username)
            manager.saveCookies(user.username, emptyList())
        }
        val browser = manager.getBrowserProfile(bound.username)
        val prefs = context.getSharedPreferences("nbu_users", Context.MODE_PRIVATE)
        prefs.edit { putString("device_id", bound.deviceId) }
        manager.saveLastLogin(unbound.username, unbound.password)

        manager.removeUnboundAccounts()

        val restored = UserManager(context)
        assertEquals(bound, restored.getUser(bound.username))
        assertEquals("course", restored.getCachedSession(bound.username))
        assertEquals(browser, restored.getBrowserProfile(bound.username))
        assertEquals(bound.deviceId, restored.getLocalDeviceId())
        assertNull(restored.getUser(unbound.username))
        assertNull(restored.getCachedSession(unbound.username))
        assertNull(restored.getLastLogin())
        for (prefix in listOf("browser_", "device_", "cookies_")) {
            assertFalse(prefs.contains(prefix + unbound.username))
        }
    }
}
