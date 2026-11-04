package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.BuildConfig
import com.life798.mini.core.UpdateInfo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现新版本时的提醒弹窗。
 *
 * 结构从简到繁三层：标题定性质、版本行给具体号、说明框讲改了什么。
 * 只有两个动作：以后再说（只关这一次）/ 立即更新。
 *
 * 说明框是主体，不是附属品 —— 封顶 140dp、内容超出时内部滚动，
 * 所以 release 正文写多长都不会把弹窗撑破。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo?,
    preparing: Boolean,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    if (info == null) return

    val notes = remember(info.body) { parseUpdateNotes(info.body) }

    OverlayDialog(
        show = true,
        onDismissRequest = onLater,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 兜底：小屏 + 大字号时也不会顶出屏幕。
                // 弹窗自身还有 24dp 上下内边距，所以这里留 400 而不是 480
                .heightIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "发现新版本",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "${BuildConfig.VERSION_NAME} → ${info.version}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            // 一条说明都没有（老 release 的正文是固定模板）时整块隐藏，
            // 不留一个空框
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                NotesBox(notes)
            }

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "以后再说",
                    onClick = onLater,
                    enabled = !preparing,
                    cornerRadius = 16.dp,
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        disabledColor = MiuixTheme.colorScheme.surfaceContainer,
                        textColor = MiuixTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                )
                Button(
                    onClick = onDownload,
                    enabled = !preparing,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    cornerRadius = 16.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text(
                        text = if (preparing) "准备中…" else "立即更新",
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/**
 * 更新说明框。
 *
 * `heightIn(max)` 而不是固定 `height`：只有两条说明时框自然变矮，
 * 不会留一大片空白；写长了封顶 140dp 并出现滚动。
 */
@Composable
private fun NotesBox(notes: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 先滚动后 padding，否则最后一条会被裁掉半行
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            notes.forEach { note ->
                Row {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = note,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private val BULLET_PREFIXES = listOf("- ", "* ", "• ")
private val ORDERED_PREFIX = Regex("""^\d+[.、)]\s*""")

/**
 * 从 release 正文里抽出变更条目。
 *
 * 只认列表行首：`- ` / `* ` / `• ` / `1. `。其余（标题、空行、分隔线、
 * 正文段落）一律跳过 —— 宁可少显示，也不要把一整段功能清单塞进弹窗。
 *
 * 条数不设上限，多了靠滚动。
 */
private fun parseUpdateNotes(body: String): List<String> {
    if (body.isBlank()) return emptyList()

    val out = mutableListOf<String>()
    body.lineSequence().forEach { raw ->
        val line = raw.trim()

        val stripped = when {
            BULLET_PREFIXES.any { line.startsWith(it) } -> line.substring(2)
            ORDERED_PREFIX.containsMatchIn(line) -> ORDERED_PREFIX.replace(line, "")
            else -> return@forEach
        }

        val text = stripped
            .replace("**", "")
            .replace("`", "")
            .trim()

        if (text.isNotEmpty()) out += text
    }
    return out
}
