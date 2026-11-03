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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.BillRecord
import com.life798.mini.core.BillStore
import com.life798.mini.core.BillTotals
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
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

/** 一次读取的账单快照。同一屏内三个 tab 共用，避免反复解析 JSON。 */
private data class BillSnapshot(
    val bills: List<BillRecord>,
    val monthTotals: BillTotals,
    val yearTotals: BillTotals,
    val activeDays: Int,
    val daily: Map<Int, Double>,
    val earliest: Long?
)

/**
 * 用水账本页。
 *
 * 数据全部来自服务端账单（[BillStore] 只是它的本地镜像），本地不自己算用量：
 * - 时间、金额、设备名来自 `/bill/lst-owner`
 * - 升数、时长来自 `/bill/view-full`
 *
 * 进页面自动同步一次，下拉也能手动刷新。同步失败时本地数据照常显示，
 * 只在顶部提一句，不清空。
 */
@Composable
fun UsageScreen(state: UiState, bottomInset: Dp, onSync: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BillStore(context) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    var tab by rememberSaveable { mutableIntStateOf(TAB_DETAIL) }
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var month by rememberSaveable { mutableIntStateOf(LocalDate.now().monthValue) }

    // 进页面同步一次。key 用 Unit，只在首次进入时触发，切 tab 不重复请求
    LaunchedEffect(Unit) { onSync() }

    val snapshot = remember(year, month, state.billsRevision) {
        BillSnapshot(
            bills = store.monthBills(year, month),
            monthTotals = store.monthTotals(year, month),
            yearTotals = store.yearTotals(year),
            activeDays = store.monthDays(year, month),
            daily = store.monthDailyLiters(year, month),
            earliest = store.earliestAt()
        )
    }

    // 账单已经按时间倒序，groupBy 保序，所以分组天然是最新的天在前
    val groups = remember(snapshot) {
        snapshot.bills.groupBy { it.at.toLocalDate() }.toList()
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

        PullToRefresh(
            isRefreshing = state.billsSyncing,
            onRefresh = onSync,
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = bottomInset + 20.dp
                ),
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

                // 同步失败：本地数据照常显示，只在这里提一句
                state.billsError?.let { error ->
                    item(key = "error") { SyncErrorCard(error) }
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
                                        "本月 ${snapshot.activeDays} 天有记录，合计 ${liters(snapshot.monthTotals.liters)} 升"
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
                                Column(
                                    Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${month}月用水合计",
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Text(
                                        text = "${liters(snapshot.monthTotals.liters)} 升",
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (snapshot.activeDays > 0) {
                                            "共 ${snapshot.activeDays} 天 · 花费 ${yuan(snapshot.monthTotals.yuan)} · 本年 ${liters(snapshot.yearTotals.liters)} 升"
                                        } else {
                                            "暂无记录 · 本年 ${liters(snapshot.yearTotals.liters)} 升"
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
                                monthTotals = snapshot.monthTotals,
                                yearTotals = snapshot.yearTotals
                            )
                        }

                        if (groups.isEmpty()) {
                            item(key = "empty") { UsageEmptyCard(syncing = state.billsSyncing) }
                        } else {
                            groups.forEach { (date, bills) ->
                                item(key = "day-$date") {
                                    DayGroup(date = date, bills = bills)
                                }
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
private fun SyncErrorCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text = "账单同步失败：$message\n显示的是本地已同步的记录，下拉可重试。",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.error
        )
    }
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

/** 双列汇总：本月 / 本年。升数在上，金额在下，一眼看到两个维度。 */
@Composable
private fun UsageSummaryCard(monthTotals: BillTotals, yearTotals: BillTotals) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryColumn(
                label = "本月用水",
                totals = monthTotals,
                highlight = false,
                modifier = Modifier.weight(1f)
            )
            SummaryColumn(
                label = "本年用水",
                totals = yearTotals,
                highlight = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryColumn(
    label: String,
    totals: BillTotals,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${liters(totals.liters)} 升",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = yuan(totals.yuan),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** 一天一组：组头带日期和当日小计，卡内逐笔列账单。 */
@Composable
private fun DayGroup(date: LocalDate, bills: List<BillRecord>) {
    val dayLiters = bills.mapNotNull { it.liters }.sum()
    val dayYuan = bills.sumOf { it.yuan }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dayLabel(bills.first().at),
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = "${liters(dayLiters)} 升 · ${yuan(dayYuan)}",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            bills.forEachIndexed { index, bill ->
                BillRow(bill)
                if (index != bills.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

/**
 * 一笔账单。
 *
 * 左边设备名 + 时刻/时长，右边升数 + 金额。
 * 升数要等详情接口补上，没补到之前显示破折号，不猜数字。
 */
@Composable
private fun BillRow(bill: BillRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = bill.deviceName ?: "接水",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(timeLabel(bill.at))
                    bill.secs?.let { append(" · ${durationLabel(it)}") }
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = bill.liters?.let { "${liters(it)} 升" } ?: "—",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = yuan(bill.yuan),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/**
 * 空状态。
 *
 * 数据来自服务端账单，所以文案不能再说「历史拿不回来」——
 * 只要服务器上有记录，同步下来就能看到。
 */
@Composable
private fun UsageEmptyCard(syncing: Boolean) {
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
            Text(
                text = if (syncing) "正在同步账单…" else "本月还没有账单",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "账单来自服务器，下拉可以重新同步。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center
            )
        }
    }
}
