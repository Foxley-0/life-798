package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.UsageEntry
import com.life798.mini.core.UsageLedger
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import java.time.YearMonth

private val USAGE_TABS = listOf("明细", "日历", "统计")

private const val TAB_DETAIL = 0
private const val TAB_CALENDAR = 1
private const val TAB_STATS = 2

/** 一次读取的账本快照。同一屏内三个 tab 共用，避免反复解析 JSON。 */
private data class UsageSnapshot(
    val entries: List<UsageEntry>,
    val monthTotal: Double,
    val yearTotal: Double,
    val activeDays: Int,
    val daily: Map<Int, Double>,
    val earliest: Long?
)

/**
 * 用水账本页。
 *
 * 结构对齐设置页：`TopAppBar`（大标题 + 滚动收起）+ `LazyColumn`，
 * 视觉参照 HUEL-yktwx 的账单页 —— 双列汇总、按天分组的流水、折线趋势。
 *
 * 数据全在本地（[UsageLedger]），没有网络请求，因此没有加载态和错误态。
 * `state.ledgerRevision` 每落一笔 +1，拿它当刷新键。
 */
@Composable
fun UsageScreen(state: UiState, bottomInset: Dp) {
    val context = LocalContext.current
    val ledger = remember { UsageLedger(context) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var tab by rememberSaveable { mutableIntStateOf(TAB_DETAIL) }
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var month by rememberSaveable { mutableIntStateOf(LocalDate.now().monthValue) }

    val snapshot = remember(year, month, state.ledgerRevision) {
        UsageSnapshot(
            entries = ledger.monthEntries(year, month),
            monthTotal = ledger.monthTotal(year, month),
            yearTotal = ledger.yearTotal(year),
            activeDays = ledger.monthDays(year, month),
            daily = ledger.monthDailyTotals(year, month),
            earliest = ledger.earliestAt()
        )
    }

    // 流水已经按时间倒序，groupBy 保序，所以分组天然是最新的天在前
    val groups = remember(snapshot) {
        snapshot.entries.groupBy { it.at.toLocalDate() }.toList()
    }

    val cursor = YearMonth.of(year, month)
    val thisMonth = YearMonth.now()
    val earliestMonth = snapshot.earliest?.let { YearMonth.from(it.toLocalDate()) }
    val canForward = cursor < thisMonth
    val canBackward = earliestMonth != null && cursor > earliestMonth

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = "用量",
            largeTitle = "用量",
            color = Color.Transparent,
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset + 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "tabs") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TabRowWithContour(
                        tabs = USAGE_TABS,
                        selectedTabIndex = tab,
                        onTabSelected = { tab = it },
                        modifier = Modifier.width(240.dp),
                        minWidth = 76.dp,
                        maxWidth = 76.dp
                    )
                }
            }

            item(key = "month") {
                MonthSwitcher(
                    year = year,
                    month = month,
                    canBackward = canBackward,
                    canForward = canForward,
                    onBackward = { cursor.minusMonths(1).let { year = it.year; month = it.monthValue } },
                    onForward = { cursor.plusMonths(1).let { year = it.year; month = it.monthValue } }
                )
            }

            when (tab) {
                TAB_CALENDAR -> item(key = "calendar") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            UsageCalendar(year = year, month = month, daily = snapshot.daily)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (snapshot.activeDays > 0) {
                                    "本月 ${snapshot.activeDays} 天有记录，合计 ${liters(snapshot.monthTotal)} 升"
                                } else {
                                    "本月还没有用水记录"
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                TAB_STATS -> {
                    item(key = "stats") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "${month}月用水合计",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    text = "${liters(snapshot.monthTotal)} 升",
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (snapshot.activeDays > 0) {
                                        "共 ${snapshot.activeDays} 天有记录 · 本年 ${liters(snapshot.yearTotal)} 升"
                                    } else {
                                        "暂无记录 · 本年 ${liters(snapshot.yearTotal)} 升"
                                    },
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    item(key = "trend") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                DailyUsageChart(points = chartPoints(year, month, snapshot.daily))
                            }
                        }
                    }
                }

                else -> {
                    item(key = "summary") {
                        UsageSummaryCard(
                            monthTotal = snapshot.monthTotal,
                            yearTotal = snapshot.yearTotal
                        )
                    }

                    if (groups.isEmpty()) {
                        item(key = "empty") { UsageEmptyCard() }
                    } else {
                        groups.forEach { (date, entries) ->
                            item(key = "day-$date") {
                                DayGroup(date = date, entries = entries)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 折线点：从 1 号铺到今天（或月末），没记录的天补 0，曲线才不会断。 */
private fun chartPoints(year: Int, month: Int, daily: Map<Int, Double>): List<UsagePoint> {
    val cursor = YearMonth.of(year, month)
    val today = LocalDate.now()
    val lastDay = if (cursor == YearMonth.from(today)) today.dayOfMonth else cursor.lengthOfMonth()
    return (1..lastDay).map { day -> UsagePoint(day, daily[day] ?: 0.0) }
}

@Composable
private fun MonthSwitcher(
    year: Int,
    month: Int,
    canBackward: Boolean,
    canForward: Boolean,
    onBackward: () -> Unit,
    onForward: () -> Unit
) {
    val enabledTint = MiuixTheme.colorScheme.onSurface
    val disabledTint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBackward, enabled = canBackward) {
            Icon(
                imageVector = MiuixIcons.ChevronBackward,
                contentDescription = "上个月",
                modifier = Modifier.size(20.dp),
                tint = if (canBackward) enabledTint else disabledTint
            )
        }
        Text(
            text = monthTitle(year, month),
            modifier = Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onForward, enabled = canForward) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "下个月",
                modifier = Modifier.size(20.dp),
                tint = if (canForward) enabledTint else disabledTint
            )
        }
    }
}

/** 双列汇总：本月 / 本年，大数字放在下面，和账单页的支出/收入一致。 */
@Composable
private fun UsageSummaryCard(monthTotal: Double, yearTotal: Double) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "本月用水",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${liters(monthTotal)} 升",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "本年用水",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${liters(yearTotal)} 升",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 一天一组：组头带日期和当日小计，卡内逐笔列流水。 */
@Composable
private fun DayGroup(date: LocalDate, entries: List<UsageEntry>) {
    val dayTotal = entries.sumOf { it.liters }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dayLabel(entries.first().at),
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = "${liters(dayTotal)} 升",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            entries.forEachIndexed { index, entry ->
                UsageRow(entry)
                if (index != entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

/** 一笔流水：左「接水」+ 时刻，右升数。 */
@Composable
private fun UsageRow(entry: UsageEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("接水", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                text = timeLabel(entry.at),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${liters(entry.liters)} 升",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

/**
 * 空状态。
 *
 * 文案必须说清楚「历史拿不回来」：服务端只有设备累计值，没有按天的时间序列，
 * 账本是从这功能上线那天起自己记的。不写这句用户会以为数据丢了。
 */
@Composable
private fun UsageEmptyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.ListView,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text("本月还没有记录", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "每次关闭设备后会自动记下一笔。服务端不提供历史用量，只能从本功能上线后开始累计。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center
            )
        }
    }
}
