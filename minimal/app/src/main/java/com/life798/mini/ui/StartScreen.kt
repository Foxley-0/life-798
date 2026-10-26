package com.life798.mini.ui

import androidx.activity.compose.BackHandler
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 启动页（二级页面）。
 *
 * 扫码成功后进入，显示设备编号与启动按钮。
 * 返回（按钮或系统返回键）都弹确认——确认后设备编号被清除，只能重新扫。
 */
@Composable
fun StartScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    var showBackConfirm by remember { mutableStateOf(false) }

    // 系统返回键与页面返回按钮走同一套确认逻辑
    BackHandler { showBackConfirm = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showBackConfirm = true }) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "设备编号",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.deviceId.ifBlank { "—" },
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = !state.busy && state.deviceId.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("启 动", fontSize = 17.sp)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "启动后请在设备上使用，用完在设备上结算。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(36.dp))
        }
    }

    if (showBackConfirm) {
        OverlayDialog(
            show = showBackConfirm,
            title = "确定返回吗？",
            summary = "返回后设备编号会被清除，需要重新扫描二维码。",
            onDismissRequest = { showBackConfirm = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showBackConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        showBackConfirm = false
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }
}
