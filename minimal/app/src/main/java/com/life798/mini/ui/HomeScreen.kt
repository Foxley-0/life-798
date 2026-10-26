package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 首页：设备卡片 + 启动/停止 + 计时。
 * 只有一块设备、一个按钮，不做列表、不做历史。
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
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        // 顶部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("798 小助手", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = state.phone.ifBlank { "已登录" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                TextButton(onClick = onOpenLog) {
                    Text("日志", fontSize = 13.sp)
                }
                TextButton(onClick = onLogout) {
                    Text("退出登录", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // 设备卡片
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "当前设备",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.deviceId.ifBlank { "尚未添加" },
                    fontSize = if (state.deviceId.isBlank()) 16.sp else 22.sp,
                    fontFamily = if (state.deviceId.isBlank()) null else FontFamily.Monospace,
                    color = if (state.deviceId.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.deviceId.isBlank()) "扫描设备二维码" else "重新扫描")
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // 只有启动。设备启动后由用户在设备本体上结算，App 不提供停止与计时。
        // 启动会产生实际消费，先弹窗确认，避免误触。
        Button(
            onClick = { showConfirm = true },
            enabled = !state.busy && state.deviceId.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("启 动", fontSize = 17.sp)
            }
        }

        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("启动设备") },
                text = {
                    Text(
                        "设备编号：${state.deviceId}\n\n" +
                            "启动后请在设备上使用，用完在设备上结算。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirm = false
                            onStart()
                        }
                    ) { Text("确认启动") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) { Text("取消") }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        if (toast != null) {
            Text(
                text = toast!!,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
