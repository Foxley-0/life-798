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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.life798.mini.core.Platform
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import java.time.YearMonth

/** 一次读取的账单快照。 */
private data class BillSnapshot(
    val bills: List<BillRecord>,
    val monthTotals: BillTotals,
    val yearTotals: BillTotals,
    val activeDays: Int,
    val daily: Map<Int, Double>
)

/**
 * 用水账本页。
 *
 * **两个平台共用这一套版式**，只有数据源不同：
 * - 惠生活798：`/bill/lst-owner` + `/bill/view-full`
 * - 胖乖生活：`/order/list` + `/order/detail`（`waterUseValue` 是毫升）
 *
 * 账本按 `Platform.prefix + 手机号` 隔离，同一个手机号登两个平台也是两本账，
 * 切换 Tab 只是换一个 [BillStore] 实例，页面结构一行都不用改。
 *
 * 整页只有一个月的数据：顶部一个年月按钮，点了弹双滚轮，
 * 选完之后汇总卡、折线图、明细全部跟着切到那个月。
 */
@Composable
fun UsageScreen(
    state: UiState,
    bottomInset: Dp,
    onSyncHui798: () -> Unit,
    onSyncPangguai: () -> Unit,
) {
    val context = LocalContext.current

    // 当前平台。存 id 而不是枚举：rememberSaveable 只认基本类型，
    // 进程重建后能原样恢复。
    var platformId by rememberSaveable { mutableStateOf(Platform.HUI798.id) }
    val platform = Platform.fromId(platformId)
    val session = state.account(platform)

    // 两个平台的同步状态各一套，切过去看到的就是各自的进度，不会互相串
    val syncing: Boolean
    val revision: Int
    val syncError: String?
    val syncNow: () -> Unit
    if (platform == Platform.HUI798) {
        syncing = state.billsSyncing
        revision = state.billsRevision
        syncError = state.billsError
        syncNow = onSyncHui798
    } else {
        syncing = state.pgBillsSyncing
        revision = state.pgBillsRevision
        syncError = state.pgBillsError
        syncNow = onSyncPangguai
    }

    // 账本按「平台 + 手机号」隔离。key 用 ownerKey 而不是 phone：
    // 同一手机号两个平台的 phone 相同，只用 phone 不会重建仓库，会读错账本。
    val store = remember(session.ownerKey) { BillStore(context, session.phone, platform) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 有账单的年份 / 月份，滚轮里只出现这些，空月份转不到
    val years = remember(store, revision) { store.years() }

    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var month by rememberSaveable { mutableIntStateOf(LocalDate.now().monthValue) }
    var showPicker by remember { mutableStateOf(false) }

    // 一天只同步一次：账单是按天产生的，反复切进切出用量页没必要每次都打服务端。
    // 跨过零点 syncedToday() 自然变 false，第二天第一次进来会再拉一遍。
    // 「今天已同步」的落戳放在同步成功的分支里（见 AppViewModel.syncBills），
    // 失败不落戳，下次进来还能重试。
    LaunchedEffect(platform, session.isActive) {
        if (session.isActive && !store.syncedToday()) syncNow()
    }

    // 当前选中的年月可能没有账单（比如刚跨月、或者同步前是空的），
    // 自动落到最近一个有记录的月份，避免进来就看到空白
    LaunchedEffect(years) {
        if (years.isEmpty()) return@LaunchedEffect
        if (year !in years) {
            year = years.first()
            month = store.monthsOf(year).firstOrNull() ?: month
        } else {
            val months = store.monthsOf(year)
            if (month !in months) month = months.firstOrNull() ?: month
        }
    }

    val snapshot = remember(store, year, month, revision) {
        BillSnapshot(
            bills = store.monthBills(year, month),
            monthTotals = store.monthTotals(year, month),
            yearTotals = store.yearTotals(year),
            activeDays = store.monthDays(year, month),
            daily = store.monthDailyLiters(year, month)
        )
    }

    // 账单已按时间倒序，groupBy 保序，分组天然是最新的天在前
    val groups = remember(snapshot) {
        snapshot.bills.groupBy { it.at.toLocalDate() }.toList()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = "用量",
            largeTitle = "用量",
            color = Color.Transparent,
            scrollBehavior = scrollBehavior
        )

        // 平台切换：与登录页同一个控件（PlatformSwitcher），不另做一套
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            PlatformSwitcher(
                selected = platform,
                onSelect = { platformId = it.id }
            )
        }

        PullToRefresh(
            isRefreshing = syncing,
            onRefresh = syncNow,
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
                item(key = "picker") {
                    YearMonthButton(
                        year = year,
                        month = month,
                        count = snapshot.bills.size,
                        onClick = { showPicker = true }
                    )
                }

                // 当前平台没登录：账本属于这个账号，这里不展示任何数据，只说明去哪里登录。
                // 不回登录页 —— 另一个平台可能还在线，门禁由 UiState.loggedIn 统一判断。
                if (!session.isActive) {
                    item(key = "no-account") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = "${platform.label} 未登录",
                                    fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "账本属于 ${platform.label} 账号，请到设置页登录后查看。",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                        }
                    }
                }

                // 同步失败：本地数据照常显示，只在这里提一句
                syncError?.let { error ->
                    item(key = "error") { SyncErrorCard(error) }
                }

                item(key = "summary") {
                    UsageSummaryCard(
                        year = year,
                        month = month,
                        monthTotals = snapshot.monthTotals,
                        yearTotals = snapshot.yearTotals
                    )
                }

                item(key = "chart") {
                    DailyChartCard(
                        year = year,
                        month = month,
                        daily = snapshot.daily
                    )
                }

                if (groups.isEmpty()) {
                    item(key = "empty") {
                        UsageEmptyCard(
                            year = year,
                            month = month,
                            syncing = syncing
                        )
                    }
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

    if (showPicker) {
        YearMonthPickerDialog(
            years = years,
            initialYear = year,
            initialMonth = month,
            monthsOf = { y -> store.monthsOf(y) },
            onDismiss = { showPicker = false },
            onConfirm = { y, m ->
                showPicker = false
                year = y
                month = m
            }
        )
    }
}

/** 顶部年月按钮：`2026年11月 ⌄`，右边带这个月的笔数。 */
@Composable
private fun YearMonthButton(year: Int, month: Int, count: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MiuixIcons.Months,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${year}年${month}月",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            // 库里没有向下的箭头（只有 ExpandMore，18dp 下会渲染成断角方块），
            // 用 ChevronForward 转 90° 得到同样的视觉
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "选择月份",
                modifier = Modifier
                    .size(14.dp)
                    .rotate(90f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (count > 0) "$count 笔" else "无记录",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
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

/** 双列汇总：所选月 / 所选年。升数在上，金额在下。 */
@Composable
private fun UsageSummaryCard(
    year: Int,
    month: Int,
    monthTotals: BillTotals,
    yearTotals: BillTotals
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryColumn(
                label = "${month}月用水",
                totals = monthTotals,
                highlight = false,
                modifier = Modifier.weight(1f)
            )
            SummaryColumn(
                label = "${year}年用水",
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

/** 每日用水折线。标题由 [DailyUsageChart] 自带，这里只负责卡片外壳和内边距。 */
@Composable
private fun DailyChartCard(year: Int, month: Int, daily: Map<Int, Double>) {
    val points = remember(year, month, daily) {
        val cursor = YearMonth.of(year, month)
        val today = LocalDate.now()
        val lastDay = if (cursor == YearMonth.from(today)) today.dayOfMonth else cursor.lengthOfMonth()
        (1..lastDay).map { day -> UsagePoint(day, daily[day] ?: 0.0) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            DailyUsageChart(points = points)
        }
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

/** 空状态：所选月份没有账单。 */
@Composable
private fun UsageEmptyCard(year: Int, month: Int, syncing: Boolean) {
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
                text = if (syncing) "正在同步账单…" else "${year}年${month}月没有用水记录",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
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
