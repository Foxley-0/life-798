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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 扫码成功后进入，显示设备编号，并根据设备是否在出水切换底部按钮：
 * 未出水 → 「启动」，出水中 → 「关闭」。
 *
 * 状态来源有两处：进页面时向服务端同步一次（[UiState.deviceRunning]），
 * 以及启动/关闭成功后本地立即翻转。两边都写同一个字段，UI 只认它。
 *
 * 返回确认由上层统一处理：这里只把「想返回」报上去。
 * 之前在这里挂 BackHandler + 本地弹窗，而 App 又没有 Scaffold 托管弹窗，
 * 结果就是按返回毫无反应。
 */
@Composable
fun StartScreen(
    state: UiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val running = state.deviceRunning
    // 三个动作互斥：任一在跑，按钮都不该再被点
    val working = state.busy || state.stopping || state.settling

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = when {
                state.settling -> "结算中"
                running -> "设备运行中"
                else -> "启动设备"
            },
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

            when {
                // 结算中：服务端还没给出最终 out，此时报任何数字都是猜的
                state.settling -> {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "正在结算…",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }

                // 结算完成：这是本次真正用了多少水
                state.lastUsageLiters != null -> {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "本次用量 ${liters(state.lastUsageLiters)} 升",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                }

                running -> {
                    Spacer(Modifier.height(20.dp))
                    RunningBadge()
                }
            }

            // 操作结果必须可见：之前点了「启动」界面毫无变化，用户只能连点
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
                onClick = if (running) onStop else onStart,
                enabled = !working && state.deviceId.isNotBlank(),
                // 默认 buttonColors() 是 secondaryVariant（浅灰），会和 #F2F3F5 背景糊在一起。
                // 关闭是真会把水停掉的动作，沿用设置页退出登录那套红底白字。
                colors = if (running) {
                    ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                } else {
                    ButtonDefaults.buttonColorsPrimary()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                when {
                    state.settling || state.stopping || state.busy -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp)
                    )

                    running -> Text("关闭设备", fontSize = 17.sp)

                    else -> Text("启 动", fontSize = 17.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = when {
                    state.settling -> "正在等待服务端结算，请稍候…"
                    state.lastUsageLiters != null && !running -> "结算完成，可在「用量」页查看账本。"
                    running -> "关闭后设备停止出水，请在设备上结算。"
                    else -> "启动后请在设备上使用，用完在设备上结算。"
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 「运行中」指示：主题色圆点 + 文案，和页面其余部分同一套配色。 */
@Composable
private fun RunningBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(MiuixTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "运行中",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.primary
        )
    }
}
