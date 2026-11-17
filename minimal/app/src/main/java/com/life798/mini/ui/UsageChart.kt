package com.life798.mini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 折线图的一个点：某天用了多少升。 */
data class UsagePoint(val day: Int, val liters: Double)

/**
 * 淋浴的强调色。
 *
 * 主题里没有第二个强调色：`tertiaryContainer` 那一对在这套配色下渲出来也是蓝色
 * （实测图例两个圆点同色），拿来做第二条线分不清。所以这里给一对固定琥珀色，
 * 按页面背景亮度选 —— 深色背景用亮琥珀，浅色背景用深琥珀，monet 的深浅切换也跟着走。
 */
@Composable
fun showerAccent(): Color {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
}

/**
 * 每日用水折线图：饮水、淋浴各一条。
 *
 * 两条线共用一根 y 轴，量级直接可比；轴顶取「某天两类之和的峰值」，
 * 右上角「最高 X 升」是同一口径，所以曲线和数字对得上。
 *
 * 颜色：饮水用主题 `primary`（蓝），淋浴用 `onTertiaryContainer`（琥珀，
 * 和洗衣完成卡的感叹号同一色系）。两者都是成对的主题色，深浅色模式各自适配，
 * 不手调 alpha 去凑对比度。
 */
@Composable
fun DailyUsageChart(
    drink: List<UsagePoint>,
    shower: List<UsagePoint>,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    val drinkColor = cs.primary
    val showerColor = showerAccent()
    val grid = cs.dividerLine
    val summary = cs.onSurfaceVariantSummary

    val count = drink.size
    // 轴顶 = 单日两类之和的最大值（不是单条线的最大值），这样「最高」和曲线同尺度
    val max = (0 until count).maxOfOrNull { index ->
        drink[index].liters + (shower.getOrNull(index)?.liters ?: 0.0)
    } ?: 0.0

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

        if (count == 0 || max <= 0.0) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("暂无数据", fontSize = 13.sp, color = summary)
            }
            return@Column
        }

        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val chartTop = 8.dp.toPx()
            val chartHeight = size.height - chartTop
            val step = if (count == 1) 0f else size.width / (count - 1)

            fun xAt(index: Int): Float = if (count == 1) size.width / 2 else index * step
            fun yAt(liters: Double): Float =
                chartTop + chartHeight * (1f - (liters / max).toFloat())

            // 四条横线当刻度，不画数值轴，和账单页保持一致的克制风格
            for (i in 0..4) {
                val y = chartTop + chartHeight * i / 4f
                drawLine(
                    grid.copy(alpha = 0.45f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            fun drawSeries(points: List<UsagePoint>, color: Color, fillAlpha: Float) {
                if (points.isEmpty()) return
                // 这一类整月没有用水，就不要画一条贴着底边的 0 线 —— 那是噪声，
                // 有没有这条线由图例说明
                if ((points.maxOfOrNull { it.liters } ?: 0.0) <= 0.0) return
                if (count == 1) {
                    drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(xAt(0), yAt(points[0].liters)))
                    return
                }
                val line = Path().apply {
                    moveTo(xAt(0), yAt(points[0].liters))
                    for (index in 1 until count) lineTo(xAt(index), yAt(points[index].liters))
                }
                val fill = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(xAt(0), yAt(points[0].liters))
                    for (index in 1 until count) lineTo(xAt(index), yAt(points[index].liters))
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)),
                        startY = chartTop,
                        endY = size.height
                    )
                )
                drawPath(
                    line,
                    color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 先画淋浴再画饮水：饮水单笔量小但笔数多，放上层更容易看见
            drawSeries(shower, showerColor, 0.14f)
            drawSeries(drink, drinkColor, 0.22f)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(drinkColor, "饮水")
            Spacer(Modifier.width(14.dp))
            LegendDot(showerColor, "淋浴")
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${drink.first().day}日", fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text("${drink[count / 2].day}日", fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text("${drink.last().day}日", fontSize = 11.sp, color = summary)
        }
    }
}

/** 图例：6dp 圆点 + 名称。 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
