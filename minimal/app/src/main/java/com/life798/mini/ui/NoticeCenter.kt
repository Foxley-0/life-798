package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.Platform
import com.life798.mini.core.SessionState
import com.life798.mini.ui.devicecard.DeviceIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一条「重要情况」。
 *
 * 只有一行文字：36dp 的高度放不下第二行，而「扫码后无法启动设备」这类补充说明
 * 几乎每条都一样，重复提示没有意义。
 *
 * [id] 必须稳定且可读（`auth.<平台>.<原因>`），方便以后按 id 做「已读」「去重」，
 * 但**不允许**拼时间戳 —— 那样每次重组都会变成新的一条。
 */
data class AppNotice(
    val id: String,
    val level: NoticeLevel,
    val text: String,
    val actionLabel: String,
    val onAction: () -> Unit,
)

enum class NoticeLevel { ERROR, WARN }

/** 同时最多平铺两条。再多会把 HeroCard 挤出第一屏，剩下的折成一行计数。 */
private const val MAX_VISIBLE = 2

/**
 * 把「当前有哪些重要情况」从界面状态里算出来。
 *
 * 纯函数、零请求、只读 [UiState] 里的账号与洗衣会话，任何一次重组都能安全调用。
 *
 * 排序：ERROR 在前（已失效比未登录更急），同级按 798 → 胖乖。
 */
fun buildNotices(state: UiState, onFix: (Platform) -> Unit): List<AppNotice> {
    val out = ArrayList<AppNotice>(2)

    val hui = state.account(Platform.HUI798)
    when (hui.state) {
        SessionState.EXPIRED -> out += AppNotice(
            id = "auth.798.expired",
            level = NoticeLevel.ERROR,
            text = "798 登录已失效",
            actionLabel = "去登录",
            onAction = { onFix(Platform.HUI798) },
        )

        SessionState.EMPTY -> out += AppNotice(
            id = "auth.798.empty",
            level = NoticeLevel.WARN,
            text = "798 未登录，无法启动设备",
            actionLabel = "去登录",
            onAction = { onFix(Platform.HUI798) },
        )

        SessionState.ACTIVE -> Unit
    }

    val pg = state.account(Platform.PANGGUAI)
    when (pg.state) {
        SessionState.EXPIRED -> out += AppNotice(
            id = "auth.pg.expired",
            level = NoticeLevel.ERROR,
            text = "胖乖 登录已失效",
            actionLabel = "去登录",
            onAction = { onFix(Platform.PANGGUAI) },
        )

        // 未登录本身不算大事：扫码时会自己跳登录。
        // 只有本地还挂着**正在跑**的洗衣订单时才提示 —— 那些记录查不到状态了。
        // 只看 running：已完成的历史记录不需要网络，挂在这里只会让提示条永远消不掉。
        SessionState.EMPTY -> if (state.laundrySessions.any { it.running }) {
            out += AppNotice(
                id = "auth.pg.empty",
                level = NoticeLevel.WARN,
                text = "胖乖 未登录，洗衣状态无法查询",
                actionLabel = "去登录",
                onAction = { onFix(Platform.PANGGUAI) },
            )
        }

        SessionState.ACTIVE -> Unit
    }

    return out
}

/**
 * 扫描页顶部的重要情况提示条。
 *
 * 视觉全部交给 miuix：容器是 [Surface]（自带 MIUI 的圆角、按压反馈与高度），
 * 底色取主题的 `errorContainer` / `secondaryContainer`，文字色取对应的 `on*Container`。
 * **不自己拼 `background` + `clip`**，也不手调 `alpha` —— 深色模式下由主题自己适配。
 *
 * 无内容时整块不占高度，页面和没有这个组件时完全一致。
 */
@Composable
fun NoticeStack(
    notices: List<AppNotice>,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notices.take(MAX_VISIBLE).forEach { notice ->
            NoticeRow(notice)
        }
        val rest = notices.size - MAX_VISIBLE
        if (rest > 0) {
            Text(
                text = "还有 $rest 条重要提示",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: AppNotice) {
    val cs = MiuixTheme.colorScheme
    // 底色统一走浅红容器：以前 WARN 用 secondaryContainer(#F0F0F0)，
    // 跟页面底色 #F2F3F5 几乎一样，等于没有底色 —— 这就是「太浅看不清楚」的根因。
    // 文字用 onSurface（浅色纯黑 / 深色纯白），不再用 on*Container 的灰调。
    val container = cs.errorContainer
    val onContainer = cs.onSurface
    val accent = cs.error

    Surface(
        onClick = notice.onAction,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = onContainer,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 红色感叹号：复用洗衣完成卡的三角警告图标（24 网格，15dp 下线条仍清楚）
            Icon(
                imageVector = DeviceIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = notice.text,
                color = onContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = notice.actionLabel,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = accent,
            )
        }
    }
}
