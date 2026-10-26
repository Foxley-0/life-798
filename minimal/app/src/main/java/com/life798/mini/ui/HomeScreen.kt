package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页：设备卡片 + 启动。
 *
 * 设备启动后由用户在设备本体上结算，App 不提供停止与计时。
 * 启动会产生实际消费，所以点按钮先弹确认。
 */
@Composable
fun HomeScreen(
    state: UiState,
    onScan: () -> Unit,
    onStart: () -> Unit,
    onLogout: () -> Unit,
    onClearMessage: () -> Unit,
    onOpenLog: () -> Unit
) {
    var toast by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        toast = state.message
        if (state.message != null) {
            delay(3000)
            toast = null
            onClearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        SmallTopAppBar(
            title = "798 小助手",
            subtitle = state.phone.ifBlank { "已登录" },
            actions = {
                TextButton(text = "日志", onClick = onOpenLog)
                TextButton(text = "退出", onClick = onLogout)
            }
        )

        Column(modifier = Modifier.padding(20.dp)) {
            Spacer(Modifier.height(12.dp))

            // 设备卡片
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "当前设备",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.deviceId.ifBlank { "尚未添加" },
                        fontSize = if (state.deviceId.isBlank()) 16.sp else 22.sp,
                        fontFamily = if (state.deviceId.isBlank()) null else FontFamily.Monospace,
                        color = if (state.deviceId.isBlank()) {
                            MiuixTheme.colorScheme.onSurfaceSecondary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        text = if (state.deviceId.isBlank()) "扫描设备二维码" else "重新扫描",
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { showConfirm = true },
                enabled = !state.busy && state.deviceId.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("启 动", fontSize = 17.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (toast != null) {
                Text(
                    text = toast!!,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showConfirm) {
        OverlayDialog(
            show = showConfirm,
            title = "启动设备",
            summary = "设备编号：${state.deviceId}\n\n启动后请在设备上使用，用完在设备上结算。",
            onDismissRequest = { showConfirm = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        showConfirm = false
                        onStart()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认启动")
                }
            }
        }
    }
}
