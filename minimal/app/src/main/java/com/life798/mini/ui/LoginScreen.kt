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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.life798.mini.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
            // 照财小通：极浅灰背景，比输入框浅一档，不用卡片
            .background(Color(0xFFF8F8F8))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(120.dp))

        // 财小通：超大字标题当视觉重心，不用 logo
        Text(
            text = "登录 798 小助手",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "扫码启动用水设备",
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(Modifier.height(40.dp))

        // 网关没配就直接挡在门口，别让人白试
        if (!state.configured) {
            Text(
                text = "未配置 API 网关，无法登录。请检查 secrets.properties 或仓库 Secrets。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
        }

        // 药丸输入框：直接落在背景上，没有卡片、没有分隔线
        TextField(
            value = phone,
            onValueChange = { if (it.length <= 11) phone = it.filter(Char::isDigit) },
            label = "手机号",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            cornerRadius = 16.dp,
            colors = TextFieldDefaults.textFieldColors(backgroundColor = Color(0xFFF2F2F2)),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = graphCode,
                onValueChange = { if (it.length <= 6) graphCode = it },
                label = "图形验证码",
                singleLine = true,
                cornerRadius = 16.dp,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color(0xFFF2F2F2)),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(width = 108.dp, height = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF2F2F2))
                    .clickable { onRefreshCaptcha() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    state.captchaLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp)
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

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = smsCode,
                onValueChange = { if (it.length <= 8) smsCode = it.filter(Char::isDigit) },
                label = "短信验证码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cornerRadius = 16.dp,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color(0xFFF2F2F2)),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = if (state.smsCountdown > 0) "${state.smsCountdown}s" else "获取验证码",
                onClick = { onSendSms(phone, graphCode) },
                enabled = state.smsCountdown == 0 && !state.busy && state.configured,
                colors = ButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary
                )
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onLogin(phone, smsCode) },
            enabled = !state.busy && state.configured,
            cornerRadius = 26.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
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
