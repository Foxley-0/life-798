package com.life798.mini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 全局主题。
 *
 * 使用 miuix（MIUI / HyperOS 设计系统）的配色与文字体系，
 * 深浅色跟随系统。默认主色是 MIUI 蓝。
 */
@Composable
fun MiniTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
