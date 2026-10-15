package com.example.nbu.util

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

    fun encode(user: User): String {
        require(user.local) { "仅可分享已登录的本机账号" }
        val deviceId = requireNotNull(user.deviceId) { "账号缺少设备标识" }
        val data = gson.toJson(Payload(user.username, user.password, deviceId)).toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(data))
    }

    fun decode(value: String): User {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 28) { "账号二维码内容不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        val data = requireNotNull(gson.fromJson(plain, Payload::class.java)) { "账号二维码内容为空" }
        val username = requireNotNull(data.username?.takeIf { it.isNotBlank() }) { "账号二维码缺少学号" }
        val password = requireNotNull(data.password?.takeIf { it.isNotEmpty() }) { "账号二维码缺少密码" }
        val deviceId = requireNotNull(data.deviceId) { "账号二维码缺少设备标识" }
        require(deviceId.length == 36) { "账号二维码设备标识无效" }
        UUID.fromString(deviceId)
        return User(username, password, deviceId = deviceId)
    }

    private data class Payload(val username: String?, val password: String?, val deviceId: String?)
}
