package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：账号 + 通用入口 + 退出登录。
 *
 * 顶栏照 SonyPods 的 SettingsTabPage：`TopAppBar` 带 largeTitle + scrollBehavior，
 * 滚动时大标题收起、标题淡入。列表全部使用 miuix 的 preference 组件。
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
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Column(modifier = Modifier.fillMaxSize()) {
        // defaultWindowInsetsPadding 默认 true，状态栏 inset 由它自己补，外层不要再加
        TopAppBar(
            title = "设置",
            largeTitle = "设置",
            color = Color.Transparent,
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomInset + 20.dp
            )
        ) {
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
                        summary = "深浅模式、配色与底栏样式",
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

}