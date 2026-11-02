package com.life798.mini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

private val HEADERS = arrayOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 月历网格。
 *
 * miuix 没有日历组件，这里手写，但排版和配色全部走 `MiuixTheme`。
 * 一周从周一开始（国内习惯），固定 6 行，避免月份切换时高度跳动。
 * 不做农历。
 *
 * @param daily 该月每天的用量，key 是「日」。没有记录的日期不出现在 map 里。
 */
@Composable
fun UsageCalendar(
    year: Int,
    month: Int,
    daily: Map<Int, Double>,
    modifier: Modifier = Modifier
) {
    val first = LocalDate.of(year, month, 1)
    val length = first.lengthOfMonth()
    // DayOfWeek.value：周一 = 1 … 周日 = 7，减 1 就是首行要空几格
    val leading = first.dayOfWeek.value - 1
    val today = LocalDate.now()
    val max = daily.values.maxOrNull() ?: 0.0

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            HEADERS.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        for (row in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - leading + 1
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (day in 1..length) {
                            DayCell(
                                day = day,
                                liters = daily[day],
                                max = max,
                                isToday = today.year == year &&
                                    today.monthValue == month &&
                                    today.dayOfMonth == day
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, liters: Double?, max: Double, isToday: Boolean) {
    val primary = MiuixTheme.colorScheme.primary
    // 三档强度，够区分「少量 / 中等 / 大量」，再细就看不出来了
    val intensity = when {
        liters == null || liters <= 0.0 -> 0f
        max <= 0.0 -> 0f
        liters <= max / 3.0 -> 0.18f
        liters <= max * 2.0 / 3.0 -> 0.38f
        else -> 0.62f
    }
    val background = if (intensity > 0f) Modifier.background(primary.copy(alpha = intensity), CircleShape) else Modifier
    val ring = if (isToday) Modifier.border(1.dp, primary, CircleShape) else Modifier
    val textColor = when {
        intensity >= 0.38f -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.size(30.dp).then(background).then(ring),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$day",
            fontSize = 13.sp,
            fontWeight = if (intensity > 0f) FontWeight.Medium else FontWeight.Normal,
            color = textColor
        )
    }
}
