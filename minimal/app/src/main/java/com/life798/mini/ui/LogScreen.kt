package com.life798.mini.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.life798.mini.core.AppLog
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条日志的结构化表示；解析不出来的行 fallback 成原文。 */
private data class LogEntry(
    val time: String,
    val level: Char,
    val tag: String,
    val msg: String,
    val raw: String
)

/** 形如 `10-26 19:10:49.186 I/Scan: 开始扫描` */
private val LOG_RE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+([VDIWEF])/(\w+):\s*(.*)$""")

private fun parseLine(line: String): LogEntry? {
    val m = LOG_RE.find(line) ?: return null
    return LogEntry(
        time = m.groupValues[1],
        level = m.groupValues[2].first(),
        tag = m.groupValues[3],
        msg = m.groupValues[4],
        raw = line
    )
}

/**
 * 日志查看页（二级页面）。
 *
 * 顶部：返回 + 标题；底部：四个图标操作。
 * 列表按日志级别着色，错误一眼可见。
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf(AppLog.dump()) }
    var tip by remember { mutableStateOf<String?>(null) }

    // 进页刷新一次，否则看到的是上次离开时的日志
    LaunchedEffect(Unit) { content = AppLog.dump() }

    // 返回由根级统一处理。这里以前挂过 BackHandler，会和根级同时激活，
    // 过渡期连按两次返回会一次退两层。

    val entries = remember(content) {
        content.lines().mapNotNull { line ->
            if (line.isBlank()) null else parseLine(line) ?: LogEntry("", ' ', "", "", line)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = "运行日志",
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

        tip?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(entries) { entry -> LogRow(entry) }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolButton(MiuixIcons.Download, "导出") {
                val path = exportLogFile(context)
                if (path == null) {
                    tip = "导出失败"
                } else {
                    tip = "已导出 ${File(path).name}"
                    shareFile(context, path)
                }
            }
            ToolButton(MiuixIcons.Copy, "复制") {
                copyToClipboard(context, content)
                tip = "已复制"
            }
            ToolButton(MiuixIcons.Refresh, "刷新") {
                content = AppLog.dump()
                tip = "已刷新，共 ${AppLog.size()} 条"
            }
            ToolButton(MiuixIcons.Delete, "清空") {
                AppLog.clear()
                content = AppLog.dump()
                tip = "已清空"
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val secondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val levelColor = when (entry.level) {
        'E', 'F' -> MiuixTheme.colorScheme.error
        'W' -> Color(0xFFE08B2D)
        else -> secondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        if (entry.level != ' ') {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.time.substringAfter(' '),
                    fontSize = 10.sp,
                    color = secondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.level.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    fontSize = 10.sp,
                    color = secondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = entry.msg,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = if (entry.level == 'E' || entry.level == 'F') levelColor else {
                    MiuixTheme.colorScheme.onSurface
                }
            )
        } else {
            Text(
                text = entry.raw,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary
            )
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("798log", text))
    }
}

/** 把当前日志写成 txt，返回绝对路径；失败返回 null。 */
private fun exportLogFile(context: Context): String? {
    return runCatching {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "798log-$stamp.txt")
        file.writeText(AppLog.dump())
        file.absolutePath
    }.getOrNull()
}

/** 拉起系统分享，把 txt 发出去。 */
private fun shareFile(context: Context, path: String) {
    runCatching {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出日志")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
