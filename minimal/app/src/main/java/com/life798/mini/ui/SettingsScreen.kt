package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：账号 + 通用入口 + 退出登录。
 *
 * 全部使用 miuix 的 preference 组件（ArrowPreference / BasicComponent / SmallTitle），
 * 不再手写 Row —— 按下反馈、右侧箭头、分组标题都由组件库统一。
 */
@Composable
fun SettingsScreen(
    state: UiState,
    bottomInset: Dp,
    onOpenAppearance: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomInset + 20.dp
        )
    ) {
        item {
            Text(
                text = "设置",
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp)
            )
        }

        item {
            SmallTitle(text = "账号", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "手机号",
                    summary = state.phone.ifBlank { "未登录" }
                )
            }

            Spacer(Modifier.height(16.dp))

            SmallTitle(text = "通用", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "外观",
                    summary = "深浅模式与配色方案",
                    onClick = onOpenAppearance
                )
                ArrowPreference(
                    title = "运行日志",
                    summary = "查看与导出本次运行记录",
                    onClick = onOpenLog
                )
                ArrowPreference(
                    title = "关于",
                    summary = "版本信息与开源许可",
                    onClick = onOpenAbout
                )
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "退出登录",
                    onClick = { showLogoutConfirm = true }
                )
            }
        }
    }

    if (showLogoutConfirm) {
        OverlayDialog(
            show = true,
            title = "确定退出登录吗？",
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
