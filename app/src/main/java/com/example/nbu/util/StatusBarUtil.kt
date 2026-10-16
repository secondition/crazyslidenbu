package com.example.nbu.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.annotation.ColorInt

/**
 * 状态栏工具类
 */
object StatusBarUtil {
    
    /**
     * 设置状态栏颜色，兼容所有 Android 版本
     * @param activity Activity
     * @param color 状态栏颜色
     * @param lightStatusBar 是否使用亮色状态栏图标（深色图标）
     */
    fun setStatusBarColor(
        activity: Activity,
        @ColorInt color: Int,
        lightStatusBar: Boolean = AppTheme.isDark(activity)
    ) {
        activity.window.statusBarColor = color
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.setSystemBarsAppearance(
                if (lightStatusBar) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            if (lightStatusBar) {
                activity.window.decorView.systemUiVisibility = 
                    activity.window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                activity.window.decorView.systemUiVisibility = 
                    activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }
}
