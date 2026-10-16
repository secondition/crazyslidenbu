package com.example.nbu.util

import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.DeviceProfile
import com.example.nbu.model.User
import com.google.gson.Gson
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AccountCode {
    private val gson = Gson()
    private val random = SecureRandom()
    private val key = SecretKeySpec(Base64.getDecoder().decode(ACCOUNT_KEY), "AES")

    data class Account(
        val user: User,
        val browser: BrowserProfile,
        val device: DeviceProfile
    )

    fun encode(user: User, browser: BrowserProfile, device: DeviceProfile): String {
        require(user.local) { "仅可分享已登录的本机账号" }
        validate(Account(user, browser, device))
        val data = gson.toJson(Payload(user.username, user.password, user.deviceId, browser, device))
            .toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(data))
    }

    fun decode(value: String): Account {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 28) { "账号二维码内容不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        val data = requireNotNull(gson.fromJson(plain, Payload::class.java)) { "账号二维码内容为空" }
        val username = requireNotNull(data.username?.takeIf { it.isNotBlank() }) { "账号二维码缺少学号" }
        val password = requireNotNull(data.password?.takeIf { it.isNotEmpty() }) { "账号二维码缺少密码" }
        val deviceId = requireNotNull(data.deviceId) { "账号二维码缺少设备标识" }
        val browser = requireNotNull(data.browser) { "二维码缺少浏览器资料" }
        val device = requireNotNull(data.device) { "二维码缺少设备资料" }
        return Account(User(username, password, deviceId = deviceId), browser, device).also(::validate)
    }

    private fun validate(account: Account) {
        val deviceId = requireNotNull(account.user.deviceId) { "账号缺少设备标识" }
        require(deviceId.length == 36) { "账号二维码设备标识无效" }
        UUID.fromString(deviceId)
        require(!account.browser.userAgent.isNullOrBlank() && !account.browser.fingerprint.isNullOrBlank()) {
            "账号浏览器资料不完整"
        }
        require(!account.device.systemVersion.isNullOrBlank() && !account.device.model.isNullOrBlank()) {
            "账号设备资料不完整"
        }
    }

    private data class Payload(
        val username: String?,
        val password: String?,
        val deviceId: String?,
        val browser: BrowserProfile?,
        val device: DeviceProfile?
    )
}
