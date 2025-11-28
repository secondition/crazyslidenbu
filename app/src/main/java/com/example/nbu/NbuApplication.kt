package com.example.nbu

import android.app.Application
import android.util.Log
import com.example.nbu.network.NbuApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 类
 * 在 App 启动时预热网络连接（DNS + TLS），减少登录等待时间
 */
class NbuApplication : Application() {

    companion object {
        private const val TAG = "NbuApplication"
        
        // 全局共享的 ApiService 实例
        lateinit var apiService: NbuApiService
            private set
    }

    // Application 级别的协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        // 初始化全局 ApiService
        apiService = NbuApiService()
        
        // 启动时预热网络连接（DNS + TLS）
        Log.d(TAG, "App 启动，开始预热网络连接...")
        applicationScope.launch(Dispatchers.IO) {
            apiService.preloadLoginPage()
        }
    }
}
