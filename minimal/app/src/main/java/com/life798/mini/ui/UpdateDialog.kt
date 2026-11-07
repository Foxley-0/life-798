package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.BuildConfig
import com.life798.mini.core.UpdateInfo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现新版本时的提醒弹窗。
 *
 * 结构从简到繁三层：标题行（标题 + 版本号同一行）、说明框、两个动作。
 *
 * **标题和版本号合并成一行**：原来分两行居中排，光标题区就吃掉 60dp。
 * 合并后省下的 24dp 全部让给说明框 —— 那才是用户真正要看的内容。
 *
 * **说明框是主体，不是附属品**：封顶 240dp、内容超出时内部滚动，
 * 并且带一条 miuix 标准滚动条。没有滚动条时用户根本不知道下面还有十几条。
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
                // 弹窗自身还有 24dp 上下内边距，所以这里留 440 而不是 480
                .heightIn(max = 440.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TitleRow(from = BuildConfig.VERSION_NAME, to = info.version)

            // 一条说明都没有（老 release 的正文是固定模板）时整块隐藏，
            // 不留一个空框
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
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
 * 标题行：左边「发现新版本」，右边「旧版本 → 新版本」。
 *
 * 标题 [Modifier.weight] 占满剩余宽度、单行省略；版本串不参与 weight，
 * 所以版本号再长也只会让标题被省略号收尾，不会把版本号挤掉。
 *
 * 两者按**基线**对齐而不是居中：26sp 和 11.5sp 差了一倍多，
 * 居中对齐会让版本号浮在标题半腰，基线对齐才是排版上的正确关系。
 */
@Composable
private fun TitleRow(from: String, to: String) {
    val cs = MiuixTheme.colorScheme

    // 版本串用单条 Text + AnnotatedString 分段上色。
    // 拆成三个 Text 塞进嵌套 Row 的话拿不到可靠基线，alignByBaseline 会失效。
    val version = buildAnnotatedString {
        withStyle(SpanStyle(color = cs.onSurfaceVariantSummary)) { append(from) }
        withStyle(SpanStyle(color = cs.onSurfaceVariantSummary.copy(alpha = 0.5f))) {
            append(" → ")
        }
        // 新版本号用主色加粗：这一行里唯一需要被「看见」的信息就是它
        withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.SemiBold)) {
            append(to)
        }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "发现新版本",
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = version,
            fontSize = 11.5.sp,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * 更新说明框。
 *
 * `heightIn(max)` 而不是固定 `height`：只有两条说明时框自然变矮，
 * 不会留一大片空白；写长了封顶 240dp 并出现滚动。
 *
 * 滚动条只在**真的能滚**时挂上去（`maxValue > 0`），内容短时不会留一条空轨道。
 * 右边距 5dp + 条宽仍在 16dp 内边距之内，正文不会被压住。
 */
@Composable
private fun NotesBox(notes: List<String>) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 先滚动后 padding，否则最后一条会被裁掉半行
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (scrollState.maxValue > 0) {
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 5.dp, top = 10.dp, bottom = 10.dp),
            )
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
