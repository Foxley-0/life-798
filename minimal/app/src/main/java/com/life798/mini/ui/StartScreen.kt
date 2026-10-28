package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 启动页（二级页）。
 *
 * 扫码成功后进入，显示设备编号与启动按钮。
 *
 * 返回确认由上层统一处理：这里只把「想返回」报上去。
 * 之前在这里挂 BackHandler + 本地弹窗，而 App 又没有 Scaffold 托管弹窗，
 * 结果就是按返回毫无反应。
 */
@Composable
fun StartScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = "启动设备",
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

            // 启动结果必须可见：之前点了「启动」界面毫无变化，用户只能连点
            state.message?.let { msg ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = msg,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    color = if (state.messageIsError) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = !state.busy && state.deviceId.isNotBlank(),
                // 默认 buttonColors() 是 secondaryVariant（浅灰），会和 #F2F3F5 背景糊在一起
                colors = ButtonDefaults.buttonColorsPrimary(),
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

            Spacer(Modifier.height(14.dp))

            Text(
                text = "启动后请在设备上使用，用完在设备上结算。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}
