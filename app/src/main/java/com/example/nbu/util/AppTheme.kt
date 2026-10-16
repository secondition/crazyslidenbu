package com.example.nbu.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object AppTheme {
    enum class Mode { LIGHT, DARK, SYSTEM }

    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun mode(context: Context): Mode {
        val stored = prefs(context).getString("mode", null)
        return Mode.entries.firstOrNull { it.name == stored } ?: Mode.SYSTEM
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit { putString("mode", mode.name) }
        apply(context)
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.SYSTEM ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 非 AppCompat 的 Activity 不跟随 AppCompatDelegate 的夜间模式，需要按其配置包装 Context。
     */
    fun wrap(context: Context): Context {
        val night = when (mode(context)) {
            Mode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Mode.DARK -> Configuration.UI_MODE_NIGHT_YES
            Mode.SYSTEM -> return context
        }
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
        return context.createConfigurationContext(configuration)
    }
}
