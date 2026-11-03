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
import java.time.LocalDate

/**
 * 正在用水页（二级页）。
 *
 * 只做三件事：告诉用户「水在流」、流了多久、以及给一个关闭按钮。
 *
 * 刻意**不显示升数和金额**：这两个数只有服务端结算后才存在，
 * 中途算出来的都是估的。用户要看的数字在「用量」页，那里每一笔都来自账单。
 *
 * 页面自身不轮询 —— 水是设备在放，App 只是开关。关闭成功即结束，不做结算等待。
 */
@Composable
fun UsingScreen(
    state: UiState,
    onBack: () -> Unit,
    onStop: () -> Unit
) {
    val session = state.session
    val closed = state.closed && session == null
    val stopping = state.stopping
    val name = (session?.deviceName ?: state.deviceName).orEmpty().ifBlank { "未知设备" }
    val deviceId = session?.deviceId ?: state.deviceId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = when {
                stopping -> "正在关闭"
                closed -> "已关闭"
                else -> "正在用水"
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
                text = name,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = deviceId.ifBlank { "—" },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            Spacer(Modifier.height(22.dp))

            if (closed) {
                Text(
                    text = "本次用水已结束",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            } else {
                RunningBadge(stopping = stopping)
                session?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = startedLabel(it.startedAt),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }

            // 关闭失败之类的原因必须看得见，否则用户只会反复点按钮
            state.message?.let { msg ->
                Spacer(Modifier.height(20.dp))
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
            if (closed) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("完 成", fontSize = 17.sp)
                }
            } else {
                Button(
                    onClick = onStop,
                    enabled = !stopping,
                    // 关闭是真会把水停掉的动作，沿用设置页退出登录那套红底白字。
                    // 默认 buttonColors() 是浅灰，会和背景糊在一起。
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    if (stopping) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("关闭设备", fontSize = 17.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = when {
                    stopping -> "正在关闭，请稍候…"
                    closed -> "本次用量可在「用量」页查看，账单由服务端结算。"
                    else -> "关闭后水会立即停止，用量稍后在「用量」页可见。"
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

/** 状态指示：主题色圆点 + 文案，和页面其余部分同一套配色。 */
@Composable
private fun RunningBadge(stopping: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (stopping) {
                        MiuixTheme.colorScheme.onSurfaceSecondary
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    CircleShape
                )
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (stopping) "正在停止出水" else "正在出水",
            fontSize = 15.sp,
            color = if (stopping) {
                MiuixTheme.colorScheme.onSurfaceSecondary
            } else {
                MiuixTheme.colorScheme.primary
            }
        )
    }
}

/** "22:24 开始"；跨天时补上日期，避免以为是今天开的。 */
private fun startedLabel(at: Long): String {
    val date = at.toLocalDate()
    val hm = timeLabel(at)
    return if (date == LocalDate.now()) {
        "$hm 开始"
    } else {
        "${date.monthValue}月${date.dayOfMonth}日 $hm 开始"
    }
}
