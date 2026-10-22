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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * 登录页：手机号 + 图形验证码 + 短信验证码，一屏完成，不做跳转。
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
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "798 小助手",
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "登录后即可扫码启动用水设备",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { if (it.length <= 11) phone = it.filter(Char::isDigit) },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = graphCode,
                onValueChange = { if (it.length <= 6) graphCode = it },
                label = { Text("图形验证码") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 56.dp)
                    .clickable { onRefreshCaptcha() },
                contentAlignment = Alignment.Center
            ) {
                if (captchaBitmap != null) {
                    Image(
                        bitmap = captchaBitmap.asImageBitmap(),
                        contentDescription = "图形验证码，点击刷新",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "点击加载",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = smsCode,
                onValueChange = { if (it.length <= 8) smsCode = it.filter(Char::isDigit) },
                label = { Text("短信验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                onClick = { onSendSms(phone, graphCode) },
                enabled = state.smsCountdown == 0 && !state.busy
            ) {
                Text(
                    text = if (state.smsCountdown > 0) "${state.smsCountdown}s" else "获取验证码",
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(phone, smsCode) },
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("登 录")
            }
        }

        if (state.message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!state.configured) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "未配置 API 网关，请检查 secrets.properties",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
