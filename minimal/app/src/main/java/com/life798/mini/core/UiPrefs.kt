package com.life798.mini.core

import android.content.Context

/**
 * 界面偏好：主题模式与配色方案。
 *
 * 用 SharedPreferences 而不是 DataStore —— 主题必须在第一帧就定下来，
 * DataStore 是异步 Flow，冷启动会先闪一下默认配色再变。
 */
class UiPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mini_ui", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    var accentMode: Int
        get() = sp.getInt(KEY_ACCENT, 0)
        set(value) = sp.edit().putInt(KEY_ACCENT, value).apply()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent_mode"
    }
}
