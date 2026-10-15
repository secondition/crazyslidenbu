package com.example.nbu.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.User
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlin.random.Random

/**
 * 用户管理器 - 用于管理多用户账号信息和 JSESSIONID 缓存
 */
class UserManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val PREF_NAME = "nbu_users"
        private const val KEY_USERS = "users"
        private const val KEY_PREFIX_JSESSIONID = "jsessionid_"
        private const val KEY_PREFIX_SAVE_TIME = "save_time_"
        private const val KEY_LAST_USERNAME = "last_username"
        private const val KEY_LAST_PASSWORD = "last_password"
        
        private const val KEY_PREFIX_BROWSER = "browser_"
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
