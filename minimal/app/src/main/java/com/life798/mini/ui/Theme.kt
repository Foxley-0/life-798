package com.life798.mini.ui

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 主题模式：跟随系统 / 浅色 / 深色。 */
const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

/** 配色方案：MIUI 蓝 / 跟随系统壁纸取色。 */
const val ACCENT_MIUI = 0
const val ACCENT_MONET = 1

/**
 * 全局主题。
 *
 * 能力来自 miuix 自带的 [ThemeController]：
 * - [ColorSchemeMode.System] / [ColorSchemeMode.Light] / [ColorSchemeMode.Dark] 控制深浅
 * - [ColorSchemeMode.MonetSystem] / [ColorSchemeMode.MonetLight] / [ColorSchemeMode.MonetDark]
 *   在深浅之上再叠加系统壁纸取色（Android 12+，低版本由 miuix 内部降级）
 *
 * 两个容易踩的点：
 * 1. 手动指定深浅时必须把 uiMode 同步进 CompositionLocal，否则 `isSystemInDarkTheme()`
 *    仍然返回系统的值，和主题自相矛盾。
 * 2. 这层 provider 必须**恒定存在**，不能按模式分支包/不包 —— 分支会让整棵子树换
 *    composition slot，所有 remember 状态丢失。
 */
@Composable
fun MiniTheme(
    themeMode: Int = THEME_SYSTEM,
    accentMode: Int = ACCENT_MIUI,
    content: @Composable () -> Unit
) {
    val base = when (themeMode) {
        THEME_LIGHT -> ColorSchemeMode.Light
        THEME_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    val mode = if (accentMode == ACCENT_MONET) {
        when (base) {
            ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
            ColorSchemeMode.Dark -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }
    } else {
        base
    }

    val controller = remember(mode) { ThemeController(mode) }

    val config = LocalConfiguration.current
    val nightMode = when (mode) {
        ColorSchemeMode.Light -> Configuration.UI_MODE_NIGHT_NO
        ColorSchemeMode.Dark -> Configuration.UI_MODE_NIGHT_YES
        else -> null
    }
    val providedConfig = remember(config, nightMode) {
        if (nightMode == null) {
            config
        } else {
            Configuration(config).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
        }
    }

    CompositionLocalProvider(LocalConfiguration provides providedConfig) {
        MiuixTheme(controller = controller, content = content)
    }
}

/** 当前是否深色。用于状态栏图标颜色，需与主题实际效果一致。 */
@Composable
fun isDarkTheme(themeMode: Int): Boolean = when (themeMode) {
    THEME_LIGHT -> false
    THEME_DARK -> true
    else -> isSystemInDarkTheme()
}
