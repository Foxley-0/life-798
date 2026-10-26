package com.life798.mini.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观设置：深浅模式 + 配色方案。
 *
 * 点击即时生效并落盘，没有「确定」按钮 —— MIUI 的设置项就是这个手感。
 */
@Composable
fun AppearanceScreen(
    themeMode: Int,
    accentMode: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        SmallTopAppBar(
            title = "外观",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("深浅模式")
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp)
            ) {
                Column {
                    ChoiceRow(
                        title = "跟随系统",
                        summary = "跟随手机的深色模式设置",
                        selected = themeMode == THEME_SYSTEM,
                        onClick = { onThemeMode(THEME_SYSTEM) }
                    )
                    HorizontalDivider()
                    ChoiceRow(
                        title = "浅色",
                        selected = themeMode == THEME_LIGHT,
                        onClick = { onThemeMode(THEME_LIGHT) }
                    )
                    HorizontalDivider()
                    ChoiceRow(
                        title = "深色",
                        selected = themeMode == THEME_DARK,
                        onClick = { onThemeMode(THEME_DARK) }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("配色")
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp)
            ) {
                Column {
                    ChoiceRow(
                        title = "MIUI 蓝",
                        summary = "miuix 默认主色，稳定统一",
                        selected = accentMode == ACCENT_MIUI,
                        onClick = { onAccentMode(ACCENT_MIUI) }
                    )
                    HorizontalDivider()
                    ChoiceRow(
                        title = "跟随壁纸",
                        summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "从系统壁纸提取主色"
                        } else {
                            "需要 Android 12 及以上，当前系统会自动回落到 MIUI 蓝"
                        },
                        selected = accentMode == ACCENT_MONET,
                        onClick = { onAccentMode(ACCENT_MONET) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "配色变化会立即应用到按钮、开关与高亮项。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    summary: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                }
            )
            if (summary != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                fontSize = 17.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}
