package com.life798.mini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9EBD4),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    background = Color(0xFFF7FBF7),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD7B8),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF005232),
    onPrimaryContainer = Color(0xFFB9EBD4),
    secondary = Color(0xFFB4CCBB),
    background = Color(0xFF101410),
    surface = Color(0xFF191D19),
    error = Color(0xFFFFB4AB)
)

@Composable
fun MiniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
