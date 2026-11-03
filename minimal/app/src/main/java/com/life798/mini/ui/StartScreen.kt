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
 * 只有一个职责：把这台设备的出水打开。**它不负责关闭，也不显示用量**。
 * 关水是另一件事，在「正在用水」页（[UsingScreen]）里做 —— 之前这里
 * 一个按钮兼两职，还得靠 `deviceRunning` 猜当前该显示哪个，状态一乱就点错。
 *
 * 用量、金额一概不在这里出现：账全部来自服务端账单，关闭后由账单接口给出，
 * 本页猜任何数字都是错的。
 *
 * 返回确认由上层统一处理（见 MainActivity.requestBack），这里只上报「想返回」。
 */
@Composable
fun StartScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val name = state.deviceName.ifBlank { "未知设备" }

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
            // 设备名是用户唯一能确认「扫对了没」的信息，给足字号
            Text(
                text = name,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.deviceId.ifBlank { "—" },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            // 操作结果必须可见：之前点了「启动」界面毫无变化，用户只能连点
            state.message?.let { msg ->
                Spacer(Modifier.height(22.dp))
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
                text = "启动后请在设备上使用，用完回到 App 关闭。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}
