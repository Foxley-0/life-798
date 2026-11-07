package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.PlatformSession
import com.life798.mini.core.SessionState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页的一张账号卡片。
 *
 * 只显示状态与一个主操作，不做平台切换 ——
 * 两个账号是并列关系，不是「二选一」。
 */
@Composable
fun AccountCard(
    account: PlatformSession,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.platform.label,
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(account.state)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    account.phone.isNotBlank() -> maskPhone(account.phone)
                    account.state == SessionState.EXPIRED -> "登录已失效"
                    else -> "未登录"
                },
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }

        when (account.state) {
            SessionState.ACTIVE -> TextButton(
                text = "退出登录",
                onClick = onLogout,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.error,
                ),
            )

            SessionState.EXPIRED -> TextButton(
                text = "重新登录",
                onClick = onLogin,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary,
                ),
            )

            SessionState.EMPTY -> TextButton(
                text = "登录",
                onClick = onLogin,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun StatusBadge(state: SessionState) {
    val (label, color) = when (state) {
        SessionState.ACTIVE -> "已登录" to MiuixTheme.colorScheme.primary
        SessionState.EXPIRED -> "已失效" to MiuixTheme.colorScheme.error
        SessionState.EMPTY -> "未登录" to MiuixTheme.colorScheme.onSurfaceSecondary
    }
    Text(
        text = label,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 设置页不展示完整号码：截图、录屏、给别人看设置时都不泄号。 */
private fun maskPhone(phone: String): String =
    if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone
