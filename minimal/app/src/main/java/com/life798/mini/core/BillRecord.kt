package com.life798.mini.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/**
 * 一笔服务端账单。
 *
 * 所有字段都来自 `/bill/lst-owner` 与 `/bill/view-full`，本地不编造任何数字。
 *
 * 关于「升数为什么是可空的」：列表接口不返回用量，只有详情接口的
 * `dev.subs[0].out` 才有。所以同步分两步 —— 列表先把账单落库（时间、金额、
 * 设备名立刻可显示），详情再逐条补升数。补到之前 [liters] 是 null。
 */
data class BillRecord(
    /** 服务端账单 ID，去重主键。 */
    val id: String,
    /** 结算时刻，毫秒。服务端 `utime` 优先，缺失时用 `ctime`。 */
    val at: Long,
    /** 实付金额（元），来自 `payment`。 */
    val yuan: Double,
    /** 设备名，列表阶段从 `msg` 括号里提取，详情阶段用 `dev.name` 覆盖。 */
    val deviceName: String?,
    /** 本次用量（升），来自详情 `dev.subs[0].out`。null 表示还没拉到详情。 */
    val liters: Double?,
    /** 本次出水时长（秒），来自详情 `dev.gene.secs`。 */
    val secs: Double?
)

/** 一段时间的合计。升数和金额分开累加，因为升数可能还没补全。 */
data class BillTotals(val liters: Double, val yuan: Double)

/**
 * 账单本地仓库。
 *
 * 服务端是唯一数据源，这里只是它的镜像：
 * - 合并时按 id 去重，服务端有就更新，服务端没有就**保留本地**（只增不减）
 * - 金额为 0 的账单在入口处就被丢弃 —— 那是「启动了但没出水」的空账单，
 *   抓包实证：这类会话 `gene.out` 全程不变，用量 0 升、扣费 0 元，不该记账
 *
 * **按账号隔离**：存储 key 是 `bills_<owner>`，owner 是当前登录的手机号。
 * 服务端不长期保存账单，本地账本就是唯一的历史，所以换账号绝不能共用一份 ——
 * 否则两个账号的账单会混在一个账本里，金额和升数全错。
 * [owner] 为空（未登录 / 刚登出）时整个仓库短路，不读也不写。
 *
 * 存 SharedPreferences，一个 JSON 数组。3000 条大约 600KB，解析一次几毫秒，
 * 对账本页完全够用，不值得为这点数据引入数据库。
 */
class BillStore(
    context: Context,
    private val owner: String,
    private val platform: Platform = Platform.HUI798,
) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 这个账号自己的账本。不同账号互不可见。
     *
     * 平台前缀在最前面：798 是 `bills_<phone>`（历史 key 不变），
     * 胖乖是 `pangguai-bills_<phone>`。同一手机号登两个平台时，
     * 两本账物理隔离，不存在串号的路径。
     */
    private val key = "${platform.prefix}$KEY_PREFIX$owner"

    /** 没登录就没有账本 —— 防止登出后还读到上一个账号的数据。 */
    private val enabled: Boolean get() = owner.isNotBlank()

    /** 全部账单，按时间倒序 —— 账本惯例是最新的在最上面。 */
    @Synchronized
    fun all(): List<BillRecord> = read().sortedByDescending { it.at }

    /**
     * 合并服务端返回的一页账单。
     *
     * @return 本次新增的条数（已存在的 id 只更新，不算新增）
     */
    @Synchronized
    fun merge(incoming: List<BillRecord>): Int {
        if (!enabled || incoming.isEmpty()) return 0
        val current = read().associateBy { it.id }.toMutableMap()
        var added = 0
        incoming.forEach { bill ->
            // 0 元 = 没出水的空账单，不入库
            if (bill.yuan <= 0.0) return@forEach
            val old = current[bill.id]
            if (old == null) {
                current[bill.id] = bill
                added++
            } else {
                // 服务端版本优先，但要保住已经补到的升数（列表不带升数，别把详情冲掉）
                current[bill.id] = bill.copy(
                    liters = old.liters ?: bill.liters,
                    secs = old.secs ?: bill.secs,
                    deviceName = bill.deviceName ?: old.deviceName
                )
            }
        }
        write(current.values.toList())
        return added
    }

    /** 还没拿到升数的账单 id，用于逐条补详情。 */
    @Synchronized
    fun missingDetail(): List<String> =
        read().filter { it.liters == null }.sortedByDescending { it.at }.map { it.id }

    /** 详情拉回来之后补字段。 */
    @Synchronized
    fun updateDetail(id: String, liters: Double?, secs: Double?, deviceName: String?) {
        if (liters == null) return
        val list = read().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(
            liters = liters,
            secs = secs,
            deviceName = deviceName ?: list[index].deviceName
        )
        write(list)
    }

    // ---------------- 聚合查询 ----------------

    @Synchronized
    fun monthBills(year: Int, month: Int): List<BillRecord> =
        all().filter {
            val day = it.at.toLocalDate()
            day.year == year && day.monthValue == month
        }

    @Synchronized
    fun monthTotals(year: Int, month: Int): BillTotals {
        val bills = monthBills(year, month)
        return BillTotals(
            liters = bills.mapNotNull { it.liters }.sum(),
            yuan = bills.sumOf { it.yuan }
        )
    }

    @Synchronized
    fun yearTotals(year: Int): BillTotals {
        val bills = all().filter { it.at.toLocalDate().year == year }
        return BillTotals(
            liters = bills.mapNotNull { it.liters }.sum(),
            yuan = bills.sumOf { it.yuan }
        )
    }

    /** 某月有记录的天数。 */
    @Synchronized
    fun monthDays(year: Int, month: Int): Int =
        monthBills(year, month).map { it.at.toLocalDate() }.distinct().size

    /** 某月每天的用量，key 是「日」(1..31)，给日历上色用。 */
    @Synchronized
    fun monthDailyLiters(year: Int, month: Int): Map<Int, Double> =
        monthBills(year, month)
            .groupBy { it.at.toLocalDate().dayOfMonth }
            .mapValues { (_, bills) -> bills.mapNotNull { it.liters }.sum() }

    /** 最早一条账单的时间，没有记录返回 null。用于限制月份翻页范围。 */
    @Synchronized
    fun earliestAt(): Long? = read().minOfOrNull { it.at }

    /** 有账单的年份，倒序。年份轮只列这些，空年份不进轮子。 */
    @Synchronized
    fun years(): List<Int> =
        read().map { it.at.toLocalDate().year }.distinct().sortedDescending()

    /** 指定年份里有账单的月份，倒序。月份轮只列这些。 */
    @Synchronized
    fun monthsOf(year: Int): List<Int> =
        read()
            .map { it.at.toLocalDate() }
            .filter { it.year == year }
            .map { it.monthValue }
            .distinct()
            .sortedDescending()

    // ---------------- 内部 ----------------

    private fun read(): List<BillRecord> {
        if (!enabled) return emptyList()
        val raw = sp.getString(key, null) ?: return emptyList()
        // 数据损坏就当空账本，宁可少显示也不要让页面崩
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val at = item.optLong("at", 0L)
                    if (id.isBlank() || at <= 0L) continue
                    add(
                        BillRecord(
                            id = id,
                            at = at,
                            yuan = item.optDouble("y", 0.0),
                            deviceName = item.optString("n").takeIf { it.isNotBlank() },
                            liters = if (item.has("l")) item.optDouble("l") else null,
                            secs = if (item.has("s")) item.optDouble("s") else null
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(bills: Collection<BillRecord>) {
        if (!enabled) return
        // 超出上限丢最旧的，防止 JSON 无限膨胀
        val trimmed = bills.sortedByDescending { it.at }.take(MAX_BILLS)
        val array = JSONArray()
        trimmed.forEach { bill ->
            val obj = JSONObject()
                .put("id", bill.id)
                .put("at", bill.at)
                .put("y", round3(bill.yuan))
            bill.deviceName?.let { obj.put("n", it) }
            bill.liters?.let { obj.put("l", round3(it)) }
            bill.secs?.let { obj.put("s", it) }
            array.put(obj)
        }
        // 账单是用户唯一的历史记录，用 commit 同步落盘，进程被杀也不丢
        sp.edit().putString(key, array.toString()).commit()
    }

    private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        private const val PREFS = "bill_store"
        private const val KEY_PREFIX = "bills_"
        private const val MAX_BILLS = 3000

        /**
         * 删掉「按账号隔离」之前的单份账本。
         *
         * 那时候所有账号共用 `bills` 这一个 key，里面的数据已经分不清是谁的了，
         * 留着只会误导 —— 启动时清一次，之后永远走 `bills_<owner>`。
         */
        fun clearLegacy(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove("bills")
                .apply()
        }
    }
}
