package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 年月双滚轮选择器。
 *
 * 左右两列都是「有数据才出现」的：
 * - 年份轮只列有账单的年份
 * - 月份轮只列该年有账单的月份，拨动年份后立刻跟着换
 *
 * 所以不会出现「2025年 + 11月」这种空组合，用户也转不到没有记录的月份。
 *
 * @param years 有账单的年份，倒序
 * @param initialYear 当前选中的年份
 * @param initialMonth 当前选中的月份
 * @param monthsOf 给定年份返回该年有账单的月份，倒序
 * @param onConfirm 点「完成」时回调最终选中的年月
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

    var yearIndex by remember { mutableIntStateOf(years.indexOf(initialYear).coerceAtLeast(0)) }
    var monthIndex by remember { mutableIntStateOf(0) }

    val year = years.getOrNull(yearIndex) ?: years.first()
    val months = remember(year, years) { monthsOf(year) }

    // 换年之后，月份轮要落到该年存在的月份上：
    // 当前月还在就保持，不在就回到该年最新的月份（列表第一项）
    LaunchedEffect(year) {
        val current = months.getOrNull(monthIndex)
        if (current == null || current !in months) monthIndex = 0
    }

    val safeMonthIndex = monthIndex.coerceIn(0, (months.size - 1).coerceAtLeast(0))
    val month = months.getOrNull(safeMonthIndex) ?: initialMonth

    OverlayDialog(
        show = true,
        title = "选择月份",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                NumberPicker(
                    value = yearIndex,
                    onValueChange = { yearIndex = it },
                    modifier = Modifier.weight(1f),
                    range = 0..(years.size - 1).coerceAtLeast(0),
                    label = { index -> "${years.getOrNull(index) ?: ""}年" }
                )
                NumberPicker(
                    value = safeMonthIndex,
                    onValueChange = { monthIndex = it },
                    modifier = Modifier.weight(1f),
                    range = 0..(months.size - 1).coerceAtLeast(0),
                    label = { index -> "${months.getOrNull(index) ?: ""}月" }
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { onConfirm(year, month) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Text("完成")
            }
        }
    }
}
