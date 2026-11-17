package com.life798.mini.ui.devicecard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备卡片。**整套 App 只有这一种设备卡**。
 *
 * 三处共用：扫码页「正在使用」、常用设备列表、最近 7 天抽屉。
 * 卡片自己不认识平台、不认识设备类型 —— 它只认 [DeviceCardItem] 那一份数据，
 * 图标由 [DeviceKind] 决定，文字由调用方给的 `title` / `subtitle` 决定。
 *
 * 版式是固定的：`[前置槽位] [图标块] [标题 + 副行] [右侧槽位]`。
 * - `leading`：管理模式的勾选圈
 * - `trailing`：箭头 / 「+」/「已添加」/ 铅笔，由调用方按场景给
 *
 * 只有两个东西允许外部改：容器底色、圆角。因为「正在使用」那张卡嵌在一张更大的
 * 白卡里，需要用页面底色做出「挖槽」的凹陷感；列表里的卡则是独立的白色方块。
 * 结构本身一视同仁，不允许任何一处改布局。
 */
@Composable
fun DeviceCard(
    item: DeviceCardItem,
    modifier: Modifier = Modifier,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    cornerRadius: Dp = 16.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    // 只有「正在使用」的条目才起计时器；其余条目 active=false，ticker 直接不跑
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = item.startedAt,
    ) {
        while (item.startedAt != null) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()

        /*
         * 完成态换成琥珀色。
         *
         * 主题里**没有** warning 语义色（`Colors` 只有 error / tertiary 容器系列，
         * 没有裸 `tertiary`），所以底色取 `tertiaryContainer`、图标取它的
         * `onTertiaryContainer` —— 这一对是主题里唯一成对的琥珀系，深浅色都自带适配，
         * 不用硬编码色值，也就不会在深色模式下糊掉。
         */
        val done = item.kind == DeviceKind.WASHER_DONE
        val iconContainer = if (done) {
            cs.tertiaryContainer
        } else {
            cs.primary.copy(alpha = if (item.running) 0.15f else 0.10f)
        }
        val iconTint = if (done) cs.onTertiaryContainer else cs.primary

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconOf(item.kind),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = iconTint
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = cs.onSurface,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = subtitleOf(item, now)
            if (sub != null) {
                Spacer(Modifier.height(2.5.dp))
                Text(
                    text = sub,
                    color = cs.onSurfaceVariantSummary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.badge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.primary.copy(alpha = 0.11f))
                    .padding(horizontal = 8.dp, vertical = 3.5.dp),
                color = cs.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        when {
            trailing != null -> {
                Spacer(Modifier.width(6.dp))
                trailing()
            }

            showChevron -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = cs.onSurfaceVariantSummary
                )
            }
        }
    }
}

/** 类型 → 图标。加新设备只改这一处。 */
private fun iconOf(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.DRINK -> DeviceIcons.Drink
    DeviceKind.SHOWER -> DeviceIcons.Shower
    DeviceKind.WASHER -> DeviceIcons.Washer
    DeviceKind.WASHER_DONE -> DeviceIcons.Warning
}

/**
 * 副行文字。
 *
 * 「正在使用」的计时优先于任何静态副行 —— 同一张卡在三个地方，只有这一行的内容不同。
 */
private fun subtitleOf(item: DeviceCardItem, now: Long): String? {
    val startedAt = item.startedAt
    if (startedAt != null) return "正在使用 · ${elapsedLabel(now - startedAt)}"
    return item.subtitle
}

/** 已用时长：HH:mm:ss。时钟回拨/设备刚启动时按 0 处理，不显示负数。 */
private fun elapsedLabel(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    return String.format(
        java.util.Locale.US,
        "%02d:%02d:%02d",
        total / 3600,
        total % 3600 / 60,
        total % 60
    )
}
