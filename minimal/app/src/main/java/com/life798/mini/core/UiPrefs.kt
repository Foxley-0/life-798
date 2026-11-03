package com.life798.mini.core

import android.content.Context

/**
 * 界面偏好：主题模式、配色方案、底栏样式。
 *
 * 用 SharedPreferences 而不是 DataStore —— 这些值必须在第一帧就定下来，
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

    /** 底栏样式：0 = 胶囊悬浮（默认），1 = 经典长条。 */
    var bottomBarStyle: Int
        get() = sp.getInt(KEY_BOTTOM_BAR, BOTTOM_BAR_PILL)
        set(value) = sp.edit().putInt(KEY_BOTTOM_BAR, value).apply()

    /**
     * 用户点过「不再提醒此版本」的版本号；空串表示没有跳过任何版本。
     *
     * 只记一个版本：下个版本发布后（版本号不同）照常提醒。
     */
    var skippedUpdateVersion: String
        get() = sp.getString(KEY_SKIPPED_UPDATE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SKIPPED_UPDATE, value).apply()

    companion object {
        const val BOTTOM_BAR_PILL = 0
        const val BOTTOM_BAR_BAR = 1
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent_mode"
        private const val KEY_BOTTOM_BAR = "bottom_bar_style"
        private const val KEY_SKIPPED_UPDATE = "skipped_update_version"
    }
}
