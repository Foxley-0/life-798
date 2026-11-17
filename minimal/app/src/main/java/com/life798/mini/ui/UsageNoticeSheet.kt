package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.UsageNotice
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 《使用须知与数据说明》抽屉。
 *
 * 和 yktwx 的同名组件同一套写法：miuix 的 [OverlayBottomSheet] +
 * `renderInRootScaffold = true`。
 *
 * 那个参数是这个组件唯一的保命配置 —— 不传时浮层宿主会就地挂在登录页的节点下，
 * 抽屉收起后那层全屏容器还在，把「获取验证码」的点击整片接走（2.1.0 首版就是这么挂的）。
 * 传 true 后浮层被丢到根 Scaffold 的窗口层，超出登录表单的触摸范围。
 *
 * 只有滑到底「同意并继续」才可点 —— 判断不看滚动比例，直接看 `canScrollForward`：
 * 到底了它就是 false，内容短到不足以滚动时也是 false（那种情况本来就该直接可点）。
 */
@Composable
fun UsageNoticeSheet(
    show: Boolean,
    onAgree: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    // 每次重新打开都换一个 session，key 一变，下面的滚动位置和「已读到底」就跟着重置
    val session = rememberNoticeSession(show)

    OverlayBottomSheet(
        show = show,
        startAction = {
            Text(
                text = UsageNotice.TITLE,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        },
        onDismissRequest = onDismissRequest,
        allowDismiss = true,
        // 抽屉底色显式指定成卡片色，并让背后压暗一层：
        // 不压暗时它与登录页那片浅灰几乎同色，面板边界看不出来（就是「糊到一块」）
        backgroundColor = cs.surfaceContainer,
        enableWindowDim = true,
        renderInRootScaffold = true,
    ) {
        key(session) {
            NoticeBody(onAgree = onAgree, onDismissRequest = onDismissRequest)
        }
    }
}

@Composable
private fun NoticeBody(
    onAgree: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val listState = rememberLazyListState()

    val reachedEnd by remember {
        derivedStateOf {
            !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // 正文区固定成半屏多一点：不同机型上抽屉高度不至于忽高忽低
    val bodyHeight = (screenHeight * 0.52f).coerceAtLeast(180.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(bodyHeight)
                .overScrollVertical(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            itemsIndexed(UsageNotice.CLAUSES) { index, clause ->
                Column {
                    Text(
                        text = "${index + 1}. ${clause.heading}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = emphasized(clause.body, clause.emphasis),
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = cs.onSurfaceSecondary
                    )
                }
            }

            item {
                Text(
                    text = UsageNotice.FOOTER,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                text = "关闭",
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onAgree,
                enabled = reachedEnd,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1.4f)
            ) {
                Text(if (reachedEnd) "我已阅读并同意" else "上滑阅读全文", fontSize = 15.sp)
            }
        }
    }
}

/** 抽屉每次从关到开都换一个对象，配合 `key` 让正文回到顶部、重判是否读完。 */
@Composable
private fun rememberNoticeSession(shown: Boolean): Any {
    var wasShown by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(Any()) }
    if (shown && !wasShown) session = Any()
    wasShown = shown
    return session
}

/**
 * 把 [words] 里的词在 [text] 中加粗。
 *
 * 按字符打标而不是逐段分割子串 —— 关键词可能首尾重叠，逐段拼接会漏掉后一个。
 * 打完标再按连续区间切，重叠也只会被加粗一次。
 */
private fun emphasized(text: String, words: List<String>): AnnotatedString = buildAnnotatedString {
    if (words.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }

    val bold = BooleanArray(text.length)
    words.forEach { word ->
        if (word.isEmpty()) return@forEach
        var from = text.indexOf(word)
        while (from >= 0) {
            for (i in from until from + word.length) bold[i] = true
            from = text.indexOf(word, from + word.length)
        }
    }

    var i = 0
    while (i < text.length) {
        val isBold = bold[i]
        var j = i
        while (j < text.length && bold[j] == isBold) j++
        val chunk = text.substring(i, j)
        if (isBold) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(chunk) }
        } else {
            append(chunk)
        }
        i = j
    }
}
