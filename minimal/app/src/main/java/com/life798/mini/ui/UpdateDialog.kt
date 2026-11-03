package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.BuildConfig
import com.life798.mini.core.UpdateInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现新版本时的提醒弹窗。
 *
 * 三个动作，语义分开：
 *   以后再说       —— 只关这一次，下次启动还会提醒
 *   不再提醒此版本 —— 永久跳过这个版本号（存 UiPrefs），下个版本照常提醒
 *   去下载         —— 探测可用线路后打开浏览器
 *
 * release 正文是一整段功能清单（每次发版都一样），塞进来只会变噪音，
 * 所以只显示版本号和日期。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo?,
    preparing: Boolean,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    if (info == null) return

    OverlayDialog(
        show = true,
        title = "发现新版本",
        onDismissRequest = onLater,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = info.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append("当前 ${BuildConfig.VERSION_NAME} → 最新 ${info.version}")
                    if (info.publishedAt.isNotBlank()) append("　·　${info.publishedAt}")
                },
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "以后再说",
                    onClick = onLater,
                    enabled = !preparing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                )
                TextButton(
                    text = if (preparing) "准备中…" else "去下载",
                    onClick = onDownload,
                    enabled = !preparing,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                )
            }

            // 次要动作：一行小字，不跟上面两个按钮抢视觉
            TextButton(
                text = "不再提醒此版本",
                onClick = onSkipVersion,
                enabled = !preparing,
                insideMargin = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    disabledColor = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                ),
                modifier = Modifier.height(40.dp),
            )
        }
    }
}
