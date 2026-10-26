package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：账号 + 外观 / 日志 / 关于 + 退出登录。
 *
 * 流动背景已经搬到「关于」页，这里保持干净的列表。
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onOpenAppearance: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        SmallTopAppBar(title = "设置", color = androidx.compose.ui.graphics.Color.Transparent)

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = 0.78f)
                )
            ) {
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = 0.78f)
                ),
                insideMargin = PaddingValues(0.dp)
            ) {
                Column {
                    ListRow(text = "外观", onClick = onOpenAppearance)
                    HorizontalDivider()
                    ListRow(text = "运行日志", onClick = onOpenLog)
                    HorizontalDivider()
                    ListRow(text = "关于", onClick = onOpenAbout)
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = 0.78f)
                ),
                insideMargin = PaddingValues(0.dp)
            ) {
                ListRow(text = "退出登录", onClick = { showLogoutConfirm = true })
            }
        }
    }

    if (showLogoutConfirm) {
        OverlayDialog(
            show = true,
            title = "确定退出吗？",
            summary = "退出后需要重新登录，设备编号会被清除。",
            onDismissRequest = { showLogoutConfirm = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showLogoutConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun ListRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}
