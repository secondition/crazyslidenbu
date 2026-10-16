package com.example.nbu.util

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.PlatformSession
import com.example.nbu.model.User
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.DataInputStream
import java.util.UUID
import kotlin.random.Random

/**
 * 用户管理器 - 用于管理多用户账号信息和 JSESSIONID 缓存
 */
class UserManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val resolver = context.applicationContext.contentResolver
    
    companion object {
        private const val PREF_NAME = "nbu_users"
        private const val KEY_USERS = "users"
        private const val KEY_PREFIX_JSESSIONID = "jsessionid_"
        private const val KEY_PREFIX_SAVE_TIME = "save_time_"
        private const val KEY_LAST_USERNAME = "last_username"
        private const val KEY_LAST_PASSWORD = "last_password"
        private const val KEY_SPLASH_AD = "splash_ad"
        private const val KEY_SPLASH_AD_INDEX = "splash_ad_index"
        
        private const val KEY_PREFIX_BROWSER = "browser_"
        private const val KEY_PREFIX_PLATFORM = "platform_"
        private const val KEY_PREFIX_COOKIES = "cookies_"
        private const val KEY_DEVICE_ID = "device_id"
        private const val SESSION_EXPIRE_TIME = 24 * 60 * 60 * 1000L
    }
    
    // ==================== 用户管理 ====================
    
    /**
     * 获取所有已保存的用户
     */
    fun getAllUsers(): List<User> {
        val json = prefs.getString(KEY_USERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<User>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 保存用户（如果已存在则更新）
     */
    fun saveUser(user: User) {
        val users = getAllUsers().toMutableList()
        val existingIndex = users.indexOfFirst { it.username == user.username }
        
        if (existingIndex >= 0) {
            if (users[existingIndex].deviceId != user.deviceId &&
                (user.deviceId == null || getPlatformSession(user.username)?.deviceId != user.deviceId)) {
                clearPlatformSession(user.username)
                saveCookies(user.username, getCookies(user.username).filterNot {
                    it.name in setOf("sessionToken", "MOD_AUTH_CAS")
                })
            }
            users[existingIndex] = user
        } else {
            users.add(user)
        }
        
        saveUsers(users)
    }
    
    /**
     * 删除用户
     */
    fun deleteUser(username: String) {
        val users = getAllUsers().toMutableList()
        users.removeAll { it.username == username }
        saveUsers(users)
        
        // 同时清除该用户的 Session 缓存
        clearSession(username)
        clearPlatformSession(username)
        prefs.edit { remove(KEY_PREFIX_COOKIES + username) }
    }
    
    /**
     * 根据用户名获取用户
     */
    fun getUser(username: String): User? {
        return getAllUsers().find { it.username == username }
    }
    
    /**
     * 保存用户列表
     */
    private fun saveUsers(users: List<User>) {
        val json = gson.toJson(users)
        prefs.edit { putString(KEY_USERS, json) }
    }
    
    fun getBrowserProfile(username: String): BrowserProfile {
        val key = KEY_PREFIX_BROWSER + username
        prefs.getString(key, null)?.let { return gson.fromJson(it, BrowserProfile::class.java) }
        val profile = BrowserProfile(
            userAgent = "Mozilla/5.0 (Linux; Android 17; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/155.0.${Random.nextInt(8000)}.${Random.nextInt(200)} " +
                "Mobile Safari/537.36 cpdaily/9.9.10 wisedu/9.9.10",
            fingerprint = UUID.randomUUID().toString().replace("-", "").uppercase()
        )
        prefs.edit { putString(key, gson.toJson(profile)) }
        return profile
    }

    fun getLocalDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun getDeviceId(username: String): String? = getUser(username)?.deviceId

    fun readDeviceId(): String? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uri = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%cpdaily_external_did%"),
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)))
        } ?: error("无法读取系统媒体库")
        val input = resolver.openInputStream(uri) ?: error("无法读取共享设备标识")
        val deviceId = DataInputStream(input).use {
            val bytes = ByteArray(36)
            it.readFully(bytes)
            check(it.read() == -1) { "共享设备标识格式无效" }
            bytes.toString(Charsets.UTF_8)
        }
        UUID.fromString(deviceId)
        prefs.edit { putString(KEY_DEVICE_ID, deviceId) }
        return deviceId
    }

    fun clearAuthentication(username: String) {
        clearSession(username)
        clearPlatformSession(username)
        val names = setOf("sessionToken", "CASTGC", "AUTHTGC", "JSESSIONID", "MOD_AUTH_CAS")
        saveCookies(username, getCookies(username).filterNot { it.name in names })
    }

    fun getPlatformSession(username: String): PlatformSession? {
        val json = prefs.getString(KEY_PREFIX_PLATFORM + username, null) ?: return null
        return gson.fromJson(json, PlatformSession::class.java)
    }

    fun savePlatformSession(username: String, session: PlatformSession) {
        prefs.edit { putString(KEY_PREFIX_PLATFORM + username, gson.toJson(session)) }
    }

    fun clearPlatformSession(username: String) {
        prefs.edit { remove(KEY_PREFIX_PLATFORM + username) }
    }

    fun getCookies(username: String): List<Cookie> {
        val json = prefs.getString(KEY_PREFIX_COOKIES + username, null) ?: return emptyList()
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        val domains: Map<String, List<String>> = gson.fromJson(json, type)
        return domains.flatMap { (domain, values) ->
            val url = "https://$domain/".toHttpUrl()
            values.mapNotNull { Cookie.parse(url, it) }
        }
    }

    fun saveCookies(username: String, cookies: List<Cookie>) {
        val domains = cookies.groupBy { it.domain }.mapValues { (_, values) -> values.map { it.toString() } }
        prefs.edit { putString(KEY_PREFIX_COOKIES + username, gson.toJson(domains)) }
    }

    // ==================== Session 缓存管理 ====================
    
    /**
     * 保存 JSESSIONID（按用户名存储）
     */
    fun saveSession(username: String, jsessionId: String) {
        prefs.edit {
            putString(KEY_PREFIX_JSESSIONID + username, jsessionId)
            putLong(KEY_PREFIX_SAVE_TIME + username, System.currentTimeMillis())
        }
    }
    
    /**
     * 获取指定用户缓存的 JSESSIONID（如果未过期）
     */
    fun getCachedSession(username: String): String? {
        val jsessionId = prefs.getString(KEY_PREFIX_JSESSIONID + username, null) ?: return null
        val saveTime = prefs.getLong(KEY_PREFIX_SAVE_TIME + username, 0)
        
        // 检查是否过期
        if (System.currentTimeMillis() - saveTime >= SESSION_EXPIRE_TIME) {
            clearSession(username)
            return null
        }
        
        return jsessionId
    }
    
    /**
     * 清除指定用户的 Session
     */
    fun clearSession(username: String) {
        prefs.edit {
            remove(KEY_PREFIX_JSESSIONID + username)
            remove(KEY_PREFIX_SAVE_TIME + username)
        }
    }
    
    // ==================== 最后登录账号管理 ====================
    
    /**
     * 保存最后登录的账号密码
     */
    fun saveLastLogin(username: String, password: String) {
        prefs.edit {
            putString(KEY_LAST_USERNAME, username)
            putString(KEY_LAST_PASSWORD, password)
        }
    }
    
    // ==================== 开屏跳转 ====================

    fun isSplashAdEnabled(): Boolean = prefs.getBoolean(KEY_SPLASH_AD, true)

    fun setSplashAdEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SPLASH_AD, enabled) }
    }

    fun nextSplashAdIndex(count: Int): Int {
        val index = prefs.getInt(KEY_SPLASH_AD_INDEX, 0) % count
        prefs.edit { putInt(KEY_SPLASH_AD_INDEX, (index + 1) % count) }
        return index
    }

    /**
     * 获取最后登录的账号密码
     */
    fun getLastLogin(): Pair<String, String>? {
        val username = prefs.getString(KEY_LAST_USERNAME, null)
        val password = prefs.getString(KEY_LAST_PASSWORD, null)
        return if (username != null && password != null) {
            Pair(username, password)
        } else {
            null
        }
    }
}
