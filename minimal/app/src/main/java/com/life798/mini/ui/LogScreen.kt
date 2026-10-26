package com.life798.mini.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.life798.mini.core.AppLog
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志查看页。
 *
 * 出问题时：进来 → 导出 → 把 txt 发出去。
 * 导出走 FileProvider，只暴露应用自己的 logs 目录，不需要存储权限。
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf(AppLog.dump()) }
    var tip by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        SmallTopAppBar(
            title = "运行日志",
            navigationIcon = {
                TextButton(text = "返回", onClick = onBack)
            }
        )

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(
                text = "导出文件",
                onClick = {
                    val path = exportLogFile(context)
                    if (path == null) {
                        tip = "导出失败，请查看应用日志"
                    } else {
                        tip = "已导出 ${File(path).name}"
                        shareFile(context, path)
                    }
                }
            )
            TextButton(
                text = "复制",
                onClick = {
                    copyToClipboard(context, content)
                    tip = "已复制到剪贴板"
                }
            )
            TextButton(
                text = "刷新",
                onClick = {
                    content = AppLog.dump()
                    tip = "已刷新，共 ${AppLog.size()} 条"
                }
            )
            TextButton(
                text = "清空",
                onClick = {
                    AppLog.clear()
                    content = AppLog.dump()
                    tip = "已清空"
                }
            )
        }

        tip?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        HorizontalDivider()

        val lines = remember(content) { content.lines() }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
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
        // 外部目录不可用时回落到内部目录
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
