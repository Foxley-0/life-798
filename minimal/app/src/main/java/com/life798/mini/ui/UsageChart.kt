package com.life798.mini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 折线图的一个点：某天用了多少升。 */
data class UsagePoint(val day: Int, val liters: Double)

/**
 * 每日用水折线图。
 *
 * 移植自 HUEL-yktwx 的 DailyBillChart，把「分」换成「升」、把日期换成「日」。
 * 用水量日间差异大但量级小，用折线比柱状更容易看出趋势。
 */
@Composable
fun DailyUsageChart(points: List<UsagePoint>, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val grid = MiuixTheme.colorScheme.dividerLine
    val surface = MiuixTheme.colorScheme.surface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val max = points.maxOfOrNull { it.liters } ?: 0.0

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("每日用水", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                // 没有数据时右侧留空：中间那块已经写了「暂无数据」，
                // 两处都写等于同一句话说了两遍
                text = if (max > 0.0) "最高 ${liters(max)} 升" else "",
                fontSize = 12.sp,
                color = summary
            )
        }
        Spacer(Modifier.height(12.dp))

        if (points.isEmpty() || max <= 0.0) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("暂无数据", fontSize = 13.sp, color = summary)
            }
            return@Column
        }

        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val chartTop = 8.dp.toPx()
            val chartHeight = size.height - chartTop
            val step = if (points.size == 1) 0f else size.width / (points.size - 1)

            fun point(index: Int): Offset {
                val x = if (points.size == 1) size.width / 2 else index * step
                val ratio = (points[index].liters / max).toFloat()
                return Offset(x, chartTop + chartHeight * (1f - ratio))
            }

            // 四条横线当刻度，不画数值轴，和账单页保持一致的克制风格
            for (i in 0..4) {
                val y = chartTop + chartHeight * i / 4f
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }

            if (points.size == 1) {
                drawCircle(primary, radius = 3.5.dp.toPx(), center = point(0))
                return@Canvas
            }

            val line = Path().apply {
                moveTo(point(0).x, point(0).y)
                for (index in 1 until points.size) lineTo(point(index).x, point(index).y)
            }
            val fill = Path().apply {
                moveTo(0f, size.height)
                lineTo(point(0).x, point(0).y)
                for (index in 1 until points.size) lineTo(point(index).x, point(index).y)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.26f), primary.copy(alpha = 0f)),
                    startY = chartTop,
                    endY = size.height
                )
            )
            drawPath(
                line,
                primary,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 最高点描个点，方便一眼看到峰值
            val maxIndex = points.indexOfFirst { it.liters == max }
            if (maxIndex >= 0) {
                val p = point(maxIndex)
                drawCircle(primary, radius = 4.5.dp.toPx(), center = p)
                drawCircle(surface, radius = 2.dp.toPx(), center = p)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${points.first().day}日", fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text("${points[points.size / 2].day}日", fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text("${points.last().day}日", fontSize = 11.sp, color = summary)
        }
    }
}
