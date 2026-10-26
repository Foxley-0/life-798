package com.life798.mini.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val THEME_OPTIONS = listOf("跟随系统", "浅色", "深色")
private val ACCENT_OPTIONS = listOf("MIUI 蓝", "跟随壁纸")

/**
 * 外观设置。
 *
 * 结构照 SonyPods 的 ThemeSettingsPage：两个 OverlayDropdownPreference，
 * 点开是 miuix 原生下拉弹窗，选中即生效并落盘，没有「确定」按钮。
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
            .statusBarsPadding()
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = "深浅模式",
                        summary = "决定界面跟随系统还是固定深浅",
                        items = THEME_OPTIONS,
                        selectedIndex = themeMode.coerceIn(THEME_OPTIONS.indices),
                        onSelectedIndexChange = onThemeMode
                    )
                    OverlayDropdownPreference(
                        title = "配色方案",
                        summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "跟随壁纸会从系统主题色提取主色"
                        } else {
                            "跟随壁纸需要 Android 12 及以上，当前系统会回落到 MIUI 蓝"
                        },
                        items = ACCENT_OPTIONS,
                        selectedIndex = accentMode.coerceIn(ACCENT_OPTIONS.indices),
                        onSelectedIndexChange = onAccentMode
                    )
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
}
