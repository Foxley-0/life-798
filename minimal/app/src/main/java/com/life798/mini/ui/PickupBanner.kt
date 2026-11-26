package com.life798.mini.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.laundry.LaundryPhase
import com.life798.mini.core.laundry.LaundrySession
import com.life798.mini.ui.devicecard.DeviceIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「请取衣」在首页最多显示多久。
 *
 * 取衣是用户自己的动作，时间只能兜底：第二天才去拿也正常。所以横幅本身不写倒计时，
 * 只设一个上限 —— 超过 24 小时就不再占首页，记录仍留在本地和状态页。
 */
internal const val PICKUP_WINDOW_MS = 24L * 60 * 60 * 1000

/**
 * 待取件的会话：已完成、且在 [PICKUP_WINDOW_MS] 之内，最近完成的排前面。
 *
 * 完成时刻优先用服务端的 `finishTime`，没有才退到预计结束、最后才是开始时间。
 */
internal fun pendingPickup(
    sessions: List<LaundrySession>,
    now: Long = System.currentTimeMillis(),
): List<LaundrySession> = sessions
    .asSequence()
    .filter { it.phase == LaundryPhase.Ended }
    .filter { now - pickupAt(it) <= PICKUP_WINDOW_MS }
    .sortedByDescending { pickupAt(it) }
    .toList()

private fun pickupAt(s: LaundrySession): Long =
    s.finishedAtMillis ?: s.expectedEndMillis ?: s.startedAtMillis

/**
 * 待取件横幅。
 *
 * 它**不是设备卡**。设备卡回答「哪台机器在忙、忙了多久」，而洗完之后机器已经不忙了，
 * 剩下的是一件事：「衣服还在里面，去拿」。所以这里不进 [HeroCard]、不参与「正在使用」
 * 的排序，独立摆一行，给一个明确的出清动作。
 *
 * | 情况 | 主行 | 副行 | 右侧 |
 * |---|---|---|---|
 * | 单台 | 洗衣已完成，请取衣 | 设备名 · 18:32 完成 | 已取衣 |
 * | 多台 | 2 台洗衣已完成 | 最近 18:32 · 设备名 | 全部标记已取衣 |
 *
 * 点整条进洗衣状态页（多台进最近完成的那台），点右侧一次性清掉全部。
 */
@Composable
internal fun PickupBanner(
    sessions: List<LaundrySession>,
    onOpen: (LaundrySession) -> Unit,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    val cs = MiuixTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    // 主题里没有成对的琥珀语义色（`onTertiaryContainer` 在 MIUI 蓝下也是蓝的，
    // 洗衣完成卡上就踩过这个坑），所以这边显式取一套，深浅色各一份。
    val accent = showerAccent()
    val container = if (dark) Color(0xFF3A2B12) else Color(0xFFFDF1DC)
    val titleColor = if (dark) Color(0xFFF3C57F) else Color(0xFF7A4D00)
    val subColor = titleColor.copy(alpha = 0.72f)

    val latest = sessions.first()
    val multi = sessions.size > 1
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(pickupAt(latest)))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable { onOpen(latest) }
            .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 洗衣机轮廓 + 右下角小对勾：对勾表示「洗好了」，不用感叹号 ——
        // 这个 App 里感叹号已经归「凭证失效 / 未登录」所有，再用会读成出错了。
        Box(modifier = Modifier.size(32.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = DeviceIcons.Washer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accent
                )
            }
            Canvas(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
            ) {
                drawCircle(color = accent)
                val w = size.minDimension
                val tick = Path().apply {
                    moveTo(w * 0.27f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.69f)
                    lineTo(w * 0.75f, w * 0.33f)
                }
                drawPath(
                    path = tick,
                    color = Color.White,
                    style = Stroke(
                        width = w * 0.16f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (multi) "${sessions.size} 台洗衣已完成" else "洗衣已完成，请取衣",
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (multi) {
                    "最近 $time · ${latest.deviceName.ifBlank { "胖乖洗衣机" }}"
                } else {
                    "${latest.deviceName.ifBlank { "胖乖洗衣机" }} · $time 完成"
                },
                color = subColor,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(4.dp))

        // 文字按钮：明确表示「我拿走了」。不用 × —— 那是「忽略提醒」的语义，
        // 点了之后记录还在，用户会以为没生效。
        Text(
            text = if (multi) "全部标记已取衣" else "已取衣",
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onDismissAll)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            color = accent,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
