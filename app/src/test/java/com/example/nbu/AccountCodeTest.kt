package com.example.nbu

import com.example.nbu.model.User
import com.example.nbu.util.AccountCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class AccountCodeTest {
    @Test
    fun transfersAccountIdentity() {
        val user = User("20260001", " +口令🔑 ", deviceId = UUID.randomUUID().toString(), local = true)
        val received = AccountCode.decode(AccountCode.encode(user))
        assertEquals(user.username, received.username)
        assertEquals(user.password, received.password)
        assertEquals(user.deviceId, received.deviceId)
        assertFalse(received.local)
        assertThrows(IllegalArgumentException::class.java) { AccountCode.encode(received) }
    }
}
