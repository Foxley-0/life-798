package com.life798.mini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.laundry.LaundryPhase
import com.life798.mini.core.laundry.LaundrySession
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 胖乖洗衣的「运行状态」页。
 *
 * 只在**本地已有这台洗衣机的进行中会话**时进入（卡片点击由 MainActivity 分流），
 * 所以页面拿到的 [session] 一定是自己下的单，不需要再查占用。
 *
 * 数据刷新策略（与首页轮询分工）：
 * - 进页面时由 ViewModel 查一次 `/order/detail`，页面拿到的是最新值；
 * - 停留期间**不自动轮询**，倒计时只用本地 [LaundrySession.expectedEndMillis] 走秒；
 * - 倒计时归零补查一次，服务端回 `orderStatus=3` 才切「已完成」；
 * - 其余时间只靠「刷新状态」手动拉。
 *
 * 没有「再来一单」：重新下单必须走扫码，避免误用到别的设备。
 *
 * 终态（已完成 / 已关闭）多一个「清除记录」：首页那张卡只有在这里点过之后才会消失，
 * 清的是本地会话记录，**不碰服务端订单**。
 */
@Composable
fun PangguaiWashingScreen(
    session: LaundrySession?,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val cs = MiuixTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        SmallTopAppBar(
            title = "洗衣状态",
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

        if (session == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "这条洗衣记录已被移除",
                    fontSize = 14.sp,
                    color = cs.onSurfaceVariantSummary
                )
            }
        } else {
            WashingContent(
                session = session,
                refreshing = refreshing,
                onRefresh = onRefresh,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun WashingContent(
    session: LaundrySession,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }

    // 本地走秒：不联网，只用来显示剩余
    var now by remember(session.sessionId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.sessionId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val ended = session.phase == LaundryPhase.Ended
    val closed = session.phase == LaundryPhase.Closed
    val expected = session.expectedEndMillis
    val remain = expected?.let { (it - now).coerceAtLeast(0L) }
    val zero = !ended && !closed && expected != null && remain == 0L

    // 倒计时归零补查一次：key 不变就不会重复触发，刷新成功后会随状态变化重建
    LaunchedEffect(session.sessionId, zero, ended, closed) {
        if (zero) onRefresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // 设备信息
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.defaultColors(color = cs.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MiuixIcons.Timer,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = session.deviceName.ifBlank { "洗衣设备" },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface
                    )
                    if (session.shopName.isNotBlank()) {
                        Text(
                            text = session.shopName,
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }

        // 进度环 + 中心倒计时
        val total = if (expected != null) expected - session.startedAtMillis else 0L
        val progress = if (total > 0) {
            ((now - session.startedAtMillis).toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }
        val sweep = when {
            ended -> 1f
            else -> progress
        }

        val centerMain: String
        val centerSub: String
        when {
            ended -> {
                centerMain = "已完成"
                centerSub = session.finishedAtMillis?.let { "实际完成 ${formatter.format(Date(it))}" }
                    ?: "服务端已确认完成"
            }

            closed -> {
                centerMain = "已关闭"
                centerSub = "订单已关闭，未完成洗衣"
            }

            remain == null -> {
                centerMain = "洗衣中"
                centerSub = "正在同步结束时间"
            }

            remain == 0L -> {
                centerMain = "即将结束"
                centerSub = if (refreshing) "正在确认…" else "等待服务端确认"
            }

            else -> {
                centerMain = formatRemain(remain)
                centerSub = "剩余时间"
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            val trackColor = cs.surfaceContainer
            val arcColor = if (ended || closed) cs.onSurfaceVariantSummary else cs.primary
            Canvas(modifier = Modifier.size(210.dp)) {
                val strokePx = 14.dp.toPx()
                val side = size.minDimension - strokePx
                val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(side, side),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                if (sweep > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(side, side),
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerMain,
                    fontSize = if (ended || closed) 30.sp else 38.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = centerSub,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariantSummary
                )
            }
        }

        // 明细
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cs.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (session.skuName.isNotBlank()) {
                    InfoRow("洗衣程序", session.skuName)
                }
                if (session.minutes > 0) {
                    InfoRow("程序时长", "约 ${session.minutes} 分钟")
                }
                if (session.price.isNotBlank() && session.price != "0.00") {
                    InfoRow("金额", "¥${session.price}")
                }
                InfoRow("开始时间", formatter.format(Date(session.startedAtMillis)))
                if (expected != null) {
                    InfoRow("预计结束", formatter.format(Date(expected)))
                }
                if (ended && session.finishedAtMillis != null) {
                    InfoRow("实际完成", formatter.format(Date(session.finishedAtMillis)))
                }
                InfoRow("订单号", session.orderNo)
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            colors = ButtonDefaults.buttonColors(
                color = cs.surfaceContainer,
                contentColor = cs.onSurface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                tint = if (refreshing) cs.onSurfaceVariantSummary else cs.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (refreshing) "刷新中…" else "刷新状态",
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        /*
         * 终态才有的「清除记录」。
         *
         * 首页那张「洗衣已完成，请取衣」卡就是靠它消失的 —— 不在首页给删除按钮，
         * 是因为卡片本身还要承担「点进去看详情」的职责，两个动作挤在一张卡上容易误删。
         *
         * 已关闭（Closed）也放进来：同样是终态，没有清不掉的道理。
         */
        if (ended || closed) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    color = cs.surfaceContainer,
                    contentColor = cs.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "清除记录", fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            text = when {
                ended -> "洗衣已完成，离开本页不会影响订单记录。"
                closed -> "订单已关闭，如有疑问请联系商家。"
                else -> "状态每 20 秒自动核对一次，也可手动刷新。"
            },
            fontSize = 12.sp,
            color = cs.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = cs.onSurfaceVariantSummary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

/** 剩余时间：超过 1 小时显示 `1:05:30`，否则 `18:24`。 */
private fun formatRemain(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", m, s)
    }
}
