package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：账号、当前设备、日志入口、退出登录。
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onOpenLog: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        SmallTopAppBar(title = "设置")

        Column(modifier = Modifier.padding(16.dp)) {
            // 账号
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "账号",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.phone.ifBlank { "未登录" },
                        fontSize = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 当前设备
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "当前设备",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.deviceId.ifBlank { "未绑定，扫码后自动记录" },
                        fontSize = if (state.deviceId.isBlank()) 14.sp else 17.sp,
                        fontFamily = if (state.deviceId.isBlank()) null else FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            TextButton(
                text = "运行日志",
                onClick = onOpenLog,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            TextButton(
                text = "退出登录",
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
