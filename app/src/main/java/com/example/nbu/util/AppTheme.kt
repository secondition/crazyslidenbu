package com.example.nbu.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object AppTheme {
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun isDark(context: Context): Boolean = prefs(context).getBoolean("dark", false)

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDark(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun toggle(context: Context) {
        prefs(context).edit { putBoolean("dark", !isDark(context)) }
        apply(context)
    }
}
