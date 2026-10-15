package com.example.nbu

import com.example.nbu.network.PlatformCipher
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformCipherTest {
    @Test
    fun matchesNativeEncoding() {
        val plain = "{\"c\":\"nbu\",\"d\":\"" + "token".repeat(12) + "\"}"
        val encrypted = "uNfzkNlhyXZmEN6MyiS4gBzpmYlJsk3u/uwDXx21boOjAQNbgUgvhLItVnIX wYKS7BPBAQ4E9PoKYdHa5vd7i4vLEEtXyMTmbyHHxhh6NKE="
        assertEquals(encrypted, PlatformCipher.encrypt(plain, "12345678"))
        assertEquals(plain, PlatformCipher.decrypt(encrypted, "12345678"))
        assertEquals("g1jOAmRw6IzKq5emaPVyDZpbAq2Yf1Rh", PlatformCipher.des("iQOO Neo7 竞速版", "ST83=@XV"))
        val secret = "LGXqxq72Hsa/X9YsN8jXQUXY1NtrxoR6AbJJtrU8sJD9lAgYgOyLtj3OCKQb +T0bjZOar4jXQ+XtJOlo5b3+eXU2ZBkLF09MyayAg9AhFPKDf7HQ7GR9aPGc lj39AS8Imc+AIHfyBYr6RqKsaGc+NMl9ZT9l/P4P1PB3f1wmXYA="
        assertEquals(listOf("first_v4", "12345678", "abcdefgh"), PlatformCipher.secretResponse(secret))
    }
}
