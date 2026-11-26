package com.life798.mini.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.life798.mini.core.BillBook
import com.life798.mini.core.BillImportCount
import com.life798.mini.core.BillTransfer
import com.life798.mini.core.BookCheck
import com.life798.mini.core.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账单数据页：导出 / 导入本地账本。
 *
 * ## 为什么导出要带手机号
 *
 * 账本是按手机号分 key 存的（`bills_<手机号>`），文件本身不带身份的话，
 * 把 A 的文件导进 B 的账本，两边的用水量、金额会混成一本再也分不开 ——
 * 而服务端不长期保存账单，混了就没有第二份数据可以校对。
 *
 * 所以文件最外层第一个字段就是 `phone`，每本账里还各自带一份；
 * 导入时**逐本比对当前登录手机号，对不上整份拒绝**，不给「强行导入」的入口。
 *
 * ## 文件读写
 *
 * 走系统文件选择器（SAF），不申请存储权限：
 * - 导出 → `CreateDocument`，用户自己挑目录和文件名（默认名带了手机号和日期）
 * - 导入 → `OpenDocument`，MIME 放宽到任意类型：不少文件管理器把 .json 报成
 *   `application/octet-stream`，只认 application/json 会让文件灰掉选不中。
 *   真正的安全边界不是 MIME，是文件里的 `tag` 和手机号校验。
 */
@Composable
fun BillTransferScreen(
    hui798Phone: String,
    pangguaiPhone: String,
    onLoadBooks: () -> List<BillBook>,
    onImportBooks: (List<BillBook>) -> BillImportCount,
    onBack: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 导入后要立刻反映新条数，所以账本快照放在 state 里，导完重读一次
    var books by remember { mutableStateOf(onLoadBooks()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val phones = mapOf(
        Platform.HUI798 to hui798Phone,
        Platform.PANGGUAI to pangguaiPhone,
    )

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BillTransfer.MIME)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val snapshot = onLoadBooks()
            val text = runCatching { BillTransfer.encode(snapshot) }.getOrNull()
            val ok = if (text == null) {
                false
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
            }
            busy = false
            if (ok) {
                val total = snapshot.sumOf { it.bills.size }
                messageIsError = false
                message = "已导出 $total 条账单（${snapshot.joinToString("、") { it.platform.label }}）"
                toast("导出完成")
            } else {
                messageIsError = true
                message = "导出失败：无法写入所选位置"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            if (raw == null) {
                busy = false
                messageIsError = true
                message = "读取失败：无法打开这个文件"
                return@launch
            }

            when (val parsed = BillTransfer.parse(raw)) {
                is BillTransfer.ParseResult.Invalid -> {
                    messageIsError = true
                    message = parsed.reason
                }

                is BillTransfer.ParseResult.Ok -> {
                    val checks = BillTransfer.check(parsed.books, phones)
                    val rejection = BillTransfer.rejection(checks)
                    if (rejection != null) {
                        // 手机号对不上：整份拒绝，不导任何一本
                        messageIsError = true
                        message = rejection
                    } else {
                        val importable = checks.filterIsInstance<BookCheck.Importable>()
                            .map { it.book }
                            .filter { it.bills.isNotEmpty() }
                        val skipped = checks.filterIsInstance<BookCheck.NoAccount>()
                        if (importable.isEmpty()) {
                            messageIsError = true
                            message = "当前没有已登录的账号，先登录再导入"
                        } else {
                            val result = withContext(Dispatchers.IO) { onImportBooks(importable) }
                            books = onLoadBooks()
                            messageIsError = false
                            message = buildString {
                                append("导入完成：新增 ${result.added} 条，已存在 ${result.existed} 条")
                                if (skipped.isNotEmpty()) {
                                    append("；")
                                    append(skipped.joinToString("、") { it.book.platform.label })
                                    append(" 未登录，已跳过")
                                }
                            }
                        }
                    }
                }
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = "账单数据",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SmallTitle(text = "当前账本", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                BookRow(
                    label = Platform.HUI798.label,
                    phone = hui798Phone,
                    count = books.firstOrNull { it.platform == Platform.HUI798 }?.bills?.size
                )
                HorizontalDivider()
                BookRow(
                    label = Platform.PANGGUAI.label,
                    phone = pangguaiPhone,
                    count = books.firstOrNull { it.platform == Platform.PANGGUAI }?.bills?.size
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "账单按手机号分开存，导入的文件必须与当前登录手机号一致。",
                fontSize = 12.sp,
                color = cs.onSurfaceSecondary,
            )

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = "导出与导入", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "导出账单",
                    summary = if (busy) "处理中…" else "保存为一个 JSON 文件，可换手机恢复",
                    onClick = {
                        if (!busy) {
                            val who = hui798Phone.ifBlank { pangguaiPhone }
                            exportLauncher.launch(BillTransfer.defaultFileName(who))
                        }
                    }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = "导入账单",
                    summary = if (busy) "处理中…" else "从文件恢复，按账单编号去重",
                    onClick = {
                        if (!busy) {
                            // 放宽 MIME：很多文件管理器把 .json 报成 octet-stream
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    }
                )
            }

            message?.let { text ->
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = if (messageIsError) cs.error else cs.onSurface
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一本账的当前状态：平台 + 手机号（打码）+ 条数。 */
@Composable
private fun BookRow(label: String, phone: String, count: Int?) {
    val cs = MiuixTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = cs.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                phone.isBlank() -> "未登录"
                count == null -> BillTransfer.mask(phone)
                else -> "${BillTransfer.mask(phone)} · $count 条"
            },
            fontSize = 12.sp,
            color = cs.onSurfaceVariantSummary
        )
    }
}
