package com.life798.mini.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import java.time.YearMonth

private val USAGE_TABS = listOf("明细", "统计")

private const val TAB_DETAIL = 0
private const val TAB_STATS = 1

/** 一次读取的账单快照。 */
private data class BillSnapshot(
    /** 全部账单，时间倒序。明细就是把它从头到尾铺开。 */
    val bills: List<BillRecord>,
    /** 本月（当前自然月）合计。 */
    val monthTotals: BillTotals,
    /** 本年合计。 */
    val yearTotals: BillTotals,
    val activeDays: Int,
    val daily: Map<Int, Double>
)

/**
 * 时间线上的一项：要么是月份标题，要么是一天的账单组。
 *
 * 明细不再按月份切换查看，而是从最新一路往下铺，跨月时插一个月份标题分隔。
 */
private sealed interface TimelineItem {
    data class Month(val ym: YearMonth, val totals: BillTotals) : TimelineItem
    data class Day(val date: LocalDate, val bills: List<BillRecord>) : TimelineItem
}

/**
 * 用水账本页。
 *
 * 数据全部来自服务端账单（[BillStore] 只是它的本地镜像），本地不自己算用量：
 * - 时间、金额、设备名来自 `/bill/lst-owner`
 * - 升数、时长来自 `/bill/view-full`
 *
 * 明细是一条连续时间线：月份标题 → 当天卡片 → 下一组，跨月自动插入新标题。
 * 点月份标题可以弹出月份选择，直接跳到那个月。
 */
@Composable
fun UsageScreen(state: UiState, bottomInset: Dp, onSync: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BillStore(context) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(TAB_DETAIL) }
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }

    // 进页面同步一次。key 用 Unit，只在首次进入时触发，切 tab 不重复请求
    LaunchedEffect(Unit) { onSync() }

    val snapshot = remember(state.billsRevision) {
        val now = YearMonth.now()
        BillSnapshot(
            bills = store.all(),
            monthTotals = store.monthTotals(now.year, now.monthValue),
            yearTotals = store.yearTotals(now.year),
            activeDays = store.monthDays(now.year, now.monthValue),
            daily = store.monthDailyLiters(now.year, now.monthValue)
        )
    }

    // 扁平化成「月份标题 + 每天一组」，LazyColumn 才能按下标跳转
    val timeline = remember(snapshot) { buildTimeline(snapshot.bills) }

    // 每个月标题在 timeline 里的下标，供月份选择跳转用
    val monthIndex = remember(timeline) {
        buildMap {
            timeline.forEachIndexed { index, item ->
                if (item is TimelineItem.Month) put(item.ym, index)
            }
        }
    }

    // 时间线前面还有固定 item（tabs / 错误条 / 汇总卡），跳转时要补上这段偏移
    val headerCount = if (tab == TAB_DETAIL) {
        if (state.billsError != null) 3 else 2
    } else {
        if (state.billsError != null) 2 else 1
    }

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
                state = listState,
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
                            modifier = Modifier.width(160.dp),
                            minWidth = 76.dp,
                            maxWidth = 76.dp
                        )
                    }
                }

                // 同步失败：本地数据照常显示，只在这里提一句
                state.billsError?.let { error ->
                    item(key = "error") { SyncErrorCard(error) }
                }

                when (tab) {
                    TAB_STATS -> {
                        item(key = "stats") {
                            MonthStatsCard(
                                monthTotals = snapshot.monthTotals,
                                yearTotals = snapshot.yearTotals,
                                activeDays = snapshot.activeDays
                            )
                        }
                        item(key = "trend") {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp)) {
                                    val thisMonth = YearMonth.now()
                                    val today = LocalDate.now()
                                    val lastDay = if (YearMonth.from(today) == thisMonth) {
                                        today.dayOfMonth
                                    } else {
                                        thisMonth.lengthOfMonth()
                                    }
                                    DailyUsageChart(
                                        points = (1..lastDay).map { day ->
                                            UsagePoint(day, snapshot.daily[day] ?: 0.0)
                                        }
                                    )
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

                        if (timeline.isEmpty()) {
                            item(key = "empty") { UsageEmptyCard(syncing = state.billsSyncing) }
                        } else {
                            itemsIndexed(
                                items = timeline,
                                key = { _, item ->
                                    when (item) {
                                        is TimelineItem.Month -> "month-${item.ym}"
                                        is TimelineItem.Day -> "day-${item.date}"
                                    }
                                }
                            ) { _, item ->
                                when (item) {
                                    is TimelineItem.Month -> MonthHeader(
                                        ym = item.ym,
                                        totals = item.totals,
                                        onClick = { showMonthPicker = true }
                                    )

                                    is TimelineItem.Day -> DayGroup(
                                        date = item.date,
                                        bills = item.bills
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            months = timeline.filterIsInstance<TimelineItem.Month>().map { it.ym },
            onDismiss = { showMonthPicker = false },
            onPick = { ym ->
                showMonthPicker = false
                monthIndex[ym]?.let { index ->
                    scope.launch { listState.animateScrollToItem(headerCount + index) }
                }
            }
        )
    }
}

/** 把账单按「月 → 天」摊平成时间线。账单本身已按时间倒序，groupBy 保序。 */
private fun buildTimeline(bills: List<BillRecord>): List<TimelineItem> = buildList {
    val byMonth = bills.groupBy { YearMonth.from(it.at.toLocalDate()) }
        .toList()
        .sortedByDescending { it.first }
    byMonth.forEach { (ym, monthBills) ->
        add(
            TimelineItem.Month(
                ym = ym,
                totals = BillTotals(
                    liters = monthBills.mapNotNull { it.liters }.sum(),
                    yuan = monthBills.sumOf { it.yuan }
                )
            )
        )
        monthBills.groupBy { it.at.toLocalDate() }.forEach { (date, dayBills) ->
            add(TimelineItem.Day(date, dayBills))
        }
    }
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

/**
 * 月份标题。
 *
 * 比日期标题大一号，用来在连续时间线里切开月份。右侧带该月合计，
 * 整行可点，点了弹出月份选择。
 */
@Composable
private fun MonthHeader(ym: YearMonth, totals: BillTotals, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${ym.year}年${ym.monthValue}月",
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${liters(totals.liters)} 升 · ${yuan(totals.yuan)}",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** 月份选择：只列有记录的月份，点一下滚到那个月。 */
@Composable
private fun MonthPickerDialog(
    months: List<YearMonth>,
    onDismiss: () -> Unit,
    onPick: (YearMonth) -> Unit
) {
    OverlayDialog(
        show = true,
        title = "选择月份",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            months.forEachIndexed { index, ym ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(ym) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${ym.year}年${ym.monthValue}月",
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp
                    )
                }
                if (index != months.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

/** 统计页顶部：本月用水 + 本年合计。 */
@Composable
private fun MonthStatsCard(monthTotals: BillTotals, yearTotals: BillTotals, activeDays: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${YearMonth.now().monthValue}月用水合计",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = "${liters(monthTotals.liters)} 升",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (activeDays > 0) {
                    "共 $activeDays 天 · 花费 ${yuan(monthTotals.yuan)} · 本年 ${liters(yearTotals.liters)} 升"
                } else {
                    "暂无记录 · 本年 ${liters(yearTotals.liters)} 升"
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                text = if (syncing) "正在同步账单…" else "还没有账单",
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
