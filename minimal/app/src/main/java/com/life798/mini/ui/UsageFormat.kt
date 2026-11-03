package com.life798.mini.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * 账本的文案格式化。
 *
 * 单位统一用「升」，和设备侧 `bm.unit="升"` 保持一致，不用 L / ml 混着来。
 */

/** 升数：统一两位小数，0.299 → "0.30"。 */
fun liters(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

/** 金额："¥0.02"。0 元账单在同步阶段就被过滤了，这里不会出现 0。 */
fun yuan(value: Double): String = String.format(Locale.CHINA, "¥%.2f", value)

/** 出水时长："10秒" / "17分11秒" / "1小时3分"。 */
fun durationLabel(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return when {
        total < 60 -> "${total}秒"
        total < 3600 -> "${total / 60}分${total % 60}秒"
        else -> "${total / 3600}小时${(total % 3600) / 60}分"
    }
}

/** 月份标题："2026年11月"。 */
fun monthTitle(year: Int, month: Int): String = "${year}年${month}月"

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

/** 分组头："今天 · 11月2日 周一" / "昨天 · 11月1日 周日" / "11月2日 周一"。 */
fun dayLabel(at: Long): String {
    val date = at.toLocalDate()
    val today = LocalDate.now()
    val base = "${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAYS[date.dayOfWeek.value - 1]}"
    return when (date) {
        today -> "今天 · $base"
        today.minusDays(1) -> "昨天 · $base"
        else -> base
    }
}

/** 行内时刻："22:25"。 */
fun timeLabel(at: Long): String {
    val value = at.toLocalDateTime()
    return String.format(Locale.CHINA, "%02d:%02d", value.hour, value.minute)
}
