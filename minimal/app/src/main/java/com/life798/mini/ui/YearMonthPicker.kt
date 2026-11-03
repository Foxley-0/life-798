package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.ui.components.WheelPicker
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 年月双滚轮。
 *
 * 用的是自绘的 [WheelPicker]（从 compose-miuix-ui 的 NumberPicker 改的），
 * 不是 miuix 自带的 `NumberPicker` —— 后者每一列带一条独立高亮条，
 * 两个并排就是两条断开的横线，怎么调都难看。
 *
 * 两列都是「显示范围宽、可选范围窄」：
 * - 年份：显示 `最早年-1 .. 最晚年+1`，只能停在有账单的年份上
 * - 月份：显示 `01..12`，只能停在当年有账单月份的区间内
 *
 * 这样滚轮视觉连续（不会出现「滚一下跳两个月」），又停不到没有记录的地方。
 *
 * @param years 有账单的年份，顺序不限（内部会排序）
 * @param initialYear 当前选中的年份
 * @param initialMonth 当前选中的月份
 * @param monthsOf 给定年份返回该年有账单的月份
 * @param onConfirm 点「确定」时回调最终选中的年月
 */
@Composable
fun YearMonthPickerDialog(
    years: List<Int>,
    initialYear: Int,
    initialMonth: Int,
    monthsOf: (Int) -> List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int) -> Unit
) {
    if (years.isEmpty()) return

    val sortedYears = remember(years) { years.sorted() }
    val minYear = sortedYears.first()
    val maxYear = sortedYears.last()

    var year by remember { mutableIntStateOf(initialYear.coerceIn(minYear, maxYear)) }
    var month by remember { mutableIntStateOf(initialMonth.coerceIn(1, 12)) }

    val months = remember(year, years) { monthsOf(year) }
    // 没有数据的年份给个兜底区间，避免 selectableRange 为空直接崩
    val monthRange = remember(months) {
        if (months.isEmpty()) 1..12 else months.min()..months.max()
    }

    // 换年之后月份可能越界：比如 2026 年只有 9-11 月，而当前停在上一年选中的 12 月
    LaunchedEffect(monthRange) {
        if (month !in monthRange) {
            month = month.coerceIn(monthRange.first, monthRange.last)
        }
    }

    OverlayDialog(
        show = true,
        title = "选择月份",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WheelColumn(label = "年", modifier = Modifier.weight(1f)) {
                    WheelPicker(
                        value = year,
                        onValueChange = { year = it },
                        selectableRange = minYear..maxYear,
                        displayRange = (minYear - 1)..(maxYear + 1),
                        label = { it.toString() }
                    )
                }
                WheelColumn(label = "月", modifier = Modifier.weight(1f)) {
                    WheelPicker(
                        value = month,
                        onValueChange = { month = it },
                        selectableRange = monthRange,
                        displayRange = 1..12,
                        label = { it.toString().padStart(2, '0') }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    minHeight = 50.dp
                )
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(year, month) },
                    modifier = Modifier.weight(1f),
                    minHeight = 50.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/** 滚轮上方的小标题。只有两列且都是数字，标一下「年 / 月」比让用户猜更省事。 */
@Composable
private fun WheelColumn(
    label: String,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}
