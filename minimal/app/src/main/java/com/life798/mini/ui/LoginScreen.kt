package com.life798.mini.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 登录页：手机号 + 图形验证码 + 短信验证码，一屏完成。
 *
 * 验证码区域分三态：加载中 / 已加载 / 失败（附原因）。
 */
@Composable
fun LoginScreen(
    state: UiState,
    onRefreshCaptcha: () -> Unit,
    onSendSms: (phone: String, graphCode: String) -> Unit,
    onLogin: (phone: String, smsCode: String) -> Unit
) {
    var phone by rememberSaveable { mutableStateOf(state.phone) }
    var graphCode by rememberSaveable { mutableStateOf("") }
    var smsCode by rememberSaveable { mutableStateOf("") }

    val captchaBitmap = remember(state.captchaImage) {
        state.captchaImage?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            // 键盘弹出时整页可滚，否则短信验证码那一栏会把登录按钮顶出屏幕
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "798 小助手",
            fontSize = 28.sp,
            color = MiuixTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "登录后即可扫码启动用水设备",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(Modifier.height(24.dp))

        // 网关没配就直接挡在门口，别让人白试
        if (!state.configured) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "未配置 API 网关，无法登录。请检查 secrets.properties 或仓库 Secrets。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        TextField(
            value = phone,
            onValueChange = { if (it.length <= 11) phone = it.filter(Char::isDigit) },
            label = "手机号",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = graphCode,
                onValueChange = { if (it.length <= 6) graphCode = it },
                label = "图形验证码",
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(width = 108.dp, height = 56.dp)
                    .clickable { onRefreshCaptcha() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    state.captchaLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp)
                    )

                    captchaBitmap != null -> Image(
                        bitmap = captchaBitmap.asImageBitmap(),
                        contentDescription = "图形验证码，点击刷新",
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> Text(
                        text = if (state.captchaError != null) "加载失败\n点此重试" else "点此加载",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MiuixTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.captchaError.orEmpty(),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "换一张",
                onClick = onRefreshCaptcha,
                enabled = !state.captchaLoading
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = smsCode,
                onValueChange = { if (it.length <= 8) smsCode = it.filter(Char::isDigit) },
                label = "短信验证码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = if (state.smsCountdown > 0) "${state.smsCountdown}s" else "获取验证码",
                onClick = { onSendSms(phone, graphCode) },
                enabled = state.smsCountdown == 0 && !state.busy && state.configured
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(phone, smsCode) },
            enabled = !state.busy && state.configured,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("登 录", fontSize = 17.sp)
            }
        }

        if (state.message != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.message,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
