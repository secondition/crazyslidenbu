package com.example.nbu.network

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PlatformCipher {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getMimeDecoder()
    private val keyFactory = KeyFactory.getInstance("RSA")
    private val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(decoder.decode(PlatformKeys.SERVER_PUBLIC_KEY)))
    private val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(PlatformKeys.CLIENT_PRIVATE_KEY))) as RSAPrivateKey

    fun secretRequest(): Map<String, String> {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val payload = "${UUID.randomUUID()}|first_v4".toByteArray(Charsets.UTF_8)
        val encrypted = encode(cipher.doFinal(payload))
        val signature = MessageDigest.getInstance("MD5")
            .digest("p=$encrypted&${PlatformKeys.SIGN_SECRET}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        return mapOf("p" to encrypted, "s" to signature)
    }

    fun secretResponse(value: String): List<String> {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val bytes = decoder.decode(value)
        val size = (privateKey.modulus.bitLength() + 7) / 8
        val plain = bytes.asList().chunked(size).flatMap { cipher.doFinal(it.toByteArray()).asList() }.toByteArray()
        return plain.toString(Charsets.UTF_8).split('|')
    }

    fun encrypt(value: String, secret: String): String {
        val cipher = aes(Cipher.ENCRYPT_MODE, secret)
        return encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String, secret: String): String =
        aes(Cipher.DECRYPT_MODE, secret).doFinal(decoder.decode(value)).toString(Charsets.UTF_8)

    fun des(value: String, key: String): String {
        val cipher = Cipher.getInstance("DES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "DES"), IvParameterSpec(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        return encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun aes(mode: Int, secret: String): Cipher {
        val bytes = (PlatformKeys.AES_PREFIX + secret).toByteArray(Charsets.UTF_8)
        val order = (bytes.indices step 2) + (1 until bytes.size step 2)
        val key = order.take(16).map { bytes[it] }.toByteArray()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(PlatformKeys.IV))
        }
    }

    private fun encode(bytes: ByteArray): String {
        val value = encoder.encodeToString(bytes).chunked(60).joinToString(" ")
        return if (bytes.isNotEmpty() && bytes.size % 45 == 0) "$value " else value
    }
}
