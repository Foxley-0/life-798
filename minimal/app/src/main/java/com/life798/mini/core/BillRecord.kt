package com.life798.mini.core

import android.content.Context
import android.content.SharedPreferences
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
    val secs: Double?,
    /**
     * 服务端已经查不到这笔账单的用量（详情返回 `liters = null`，例如服务端清了历史）。
     *
     * 打上这个标记就不再重复补详情 —— 否则这类账单会永远排在
     * [BillStore.missingDetail] 队头，每轮同步都白跑一遍，把真正能补的挤掉。
     * 界面上的表现和不标记时一样：升数显示「—」。
     */
    val dead: Boolean = false,
    /**
     * 设备类型码，服务端原样存的 `goodsCategory`：`"04"` 饮水、`"08"` 淋浴、`"00"` 洗衣。
     *
     * 这里存**码**而不是 `DeviceKind`：本类在 `core` 包，`DeviceKind` 在
     * `ui.devicecard` 包，反过来依赖会让 core 依赖 UI。码只有 2 个字符，
     * 存进去几乎不占空间，映射成图标交给界面层做。
     *
     * 798 的账单在构造时就写死 `"08"`（惠生活全是淋浴/热水龙头）；
     * 胖乖的账单来自订单接口，老记录可能拿不到码，此时为 null。
     */
    val category: String? = null
) {
    companion object {
        /** 服务端 `goodsCategory` 的取值：饮水。 */
        const val CATEGORY_DRINK = "04"

        /** 服务端 `goodsCategory` 的取值：淋浴。 */
        const val CATEGORY_SHOWER = "08"
    }
}

/** 一段时间的合计。升数和金额分开累加，因为升数可能还没补全。 */
data class BillTotals(val liters: Double, val yuan: Double)

/** 一次导入的结果：新增多少条、已有多少条（已存在的只补字段，不重复计数）。 */
data class BillImportCount(val added: Int, val existed: Int) {
    val total: Int get() = added + existed
}

/**
 * 按用水类型拆开的合计：饮水、淋浴各一份。
 *
 * 账本页的总用水/月份用水都要分这两类显示 —— 两个平台单价不同、单价也随类型不同，
 * 混在一起的「24.65 升」看不出是接的水还是洗的澡。
 */
data class SplitTotals(val drink: BillTotals, val shower: BillTotals) {
    /** 两类相加，等于不分类型时的合计。 */
    val all: BillTotals
        get() = BillTotals(drink.liters + shower.liters, drink.yuan + shower.yuan)
}

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

    /**
     * 上次同步成功的日期（ISO 日期字符串）。
     *
     * 跟账本同一个 xml、同一套平台前缀，但**不带** `bills_` 前缀 ——
     * 遗留 key 清理逻辑按 `bills_<手机号>` 的形状匹配，这条不能长得像账本，
     * 否则会被当成老数据合并或删掉。
     */
    private val syncDayKey get() = "${platform.prefix}lastSync_$owner"

    /**
     * 上次同步失败的时刻（毫秒），0 / 不存在表示没失败过。
     *
     * 和 [syncDayKey] 一样带平台前缀、不带 `bills_`，不会被遗留 key 清理逻辑误伤。
     */
    private val failAtKey get() = "${platform.prefix}lastFail_$owner"

    /** 没登录就没有账本 —— 防止登出后还读到上一个账号的数据。 */
    private val enabled: Boolean get() = owner.isNotBlank()

    /** 全部账单，按时间倒序 —— 账本惯例是最新的在最上面。 */
    @Synchronized
    fun all(): List<BillRecord> = read().sortedByDescending { it.at }

    /** 本地已有的全部 id。增量同步用它判断「这一页是不是全是老单」。 */
    @Synchronized
    fun existingIds(): Set<String> = read().mapTo(HashSet()) { it.id }

    /**
     * 合并服务端返回的一页账单。
     *
     * @return 本次新增的条数（已存在的 id 只更新，不算新增）
     */
    fun merge(incoming: List<BillRecord>): Int = mergeReport(incoming).size

    /**
     * 合并并返回**新增的 id**。
     *
     * 同步流程要区分「这轮新出现的账单」和「历史缺口」：新增的必须当场补详情，
     * 历史缺口只按预算补。只返回条数分不出这两拨，所以这里把 id 带出来。
     *
     * @return 新增账单的 id（已存在的 id 只更新，不返回）
     */
    @Synchronized
    fun mergeReport(incoming: List<BillRecord>): List<String> {
        if (!enabled || incoming.isEmpty()) return emptyList()
        val current = read().associateBy { it.id }.toMutableMap()
        val added = ArrayList<String>()
        incoming.forEach { bill ->
            // 0 元 = 没出水的空账单，不入库
            if (bill.yuan <= 0.0) return@forEach
            val old = current[bill.id]
            if (old == null) {
                current[bill.id] = bill
                added += bill.id
            } else {
                // 服务端版本优先，但要保住已经补到的升数（列表不带升数，别把详情冲掉）
                current[bill.id] = bill.copy(
                    liters = old.liters ?: bill.liters,
                    secs = old.secs ?: bill.secs,
                    deviceName = bill.deviceName ?: old.deviceName,
                    dead = old.dead,
                    // 接口这次没带类型码（胖乖的老订单常见）时，保住本地已有的那个，
                    // 否则会被刷成 null，下一轮 parse 又得重新猜一遍。
                    category = bill.category ?: old.category
                )
            }
        }
        write(current.values.toList())
        return added
    }

    // ---------------- 导出 / 导入 ----------------

    /**
     * 账本的原始镜像（不排序、不截断），导出用。
     *
     * 用 [read] 而不是 [all]：导出要的是盘上那份原样数据，顺序和条数都别动 ——
     * 排序交给导入方，截断则由 [write] 在写回时决定。
     */
    @Synchronized
    fun snapshot(): List<BillRecord> = read()

    /**
     * 从另一个账本文件导入。
     *
     * 合并规则和 [mergeReport] 一致：按 `id` 去重，已有的**只补空缺字段**，
     * 本地已有的升数、分类不会被导入文件里的空值冲掉；0 元空账单不入库。
     *
     * 区别只在于它是给"用户主动导入"用的，需要把「新增」和「已存在」分开报给界面。
     */
    @Synchronized
    fun importRecords(records: List<BillRecord>): BillImportCount {
        if (!enabled || records.isEmpty()) return BillImportCount(0, 0)
        val current = read().associateBy { it.id }.toMutableMap()
        var added = 0
        var existed = 0
        records.forEach { bill ->
            if (bill.yuan <= 0.0) return@forEach
            val old = current[bill.id]
            if (old == null) {
                current[bill.id] = bill
                added++
            } else {
                existed++
                current[bill.id] = old.copy(
                    // 本地优先：导入文件来自另一台设备，不能把本机补到的升数/分类覆盖成 null
                    liters = old.liters ?: bill.liters,
                    secs = old.secs ?: bill.secs,
                    deviceName = old.deviceName ?: bill.deviceName,
                    category = old.category ?: bill.category,
                    dead = old.dead
                )
            }
        }
        if (added > 0) write(current.values.toList())
        AppLog.i("Bill", "导入账本：新增 $added 条，已存在 $existed 条")
        return BillImportCount(added, existed)
    }

    // ---------------- 每日同步节流 ----------------

    /**
     * 今天是否已经同步过。
     *
     * 用量页每进一次就拉一遍账单太吵，服务端也没这个必要。这里按「平台 + 账号」
     * 记一个日期戳，同一天只放行一次；跨过零点自然失效，第二天第一次进来再拉。
     *
     * 未登录时恒为 true —— 没有账号就没有可同步的东西，别让调用方白起协程。
     */
    fun syncedToday(): Boolean {
        if (!enabled) return true
        return sp.getString(syncDayKey, null) == LocalDate.now().toString()
    }

    /**
     * 记下「今天已经同步过了」。
     *
     * 只在同步**真正成功**后调用。失败就不写，下次进页面还能再试一次；
     * 否则一次网络抖动会让这一整天都不再刷新。
     */
    fun markSyncedToday() {
        if (!enabled) return
        // 成功就把失败戳清掉：退避只针对「刚失败过」的那段时间
        sp.edit()
            .putString(syncDayKey, LocalDate.now().toString())
            .remove(failAtKey)
            .apply()
    }

    /** 记一次失败的时刻，用于 [inBackoff]。 */
    fun markSyncFailed() {
        if (!enabled) return
        sp.edit().putLong(failAtKey, System.currentTimeMillis()).apply()
    }

    /**
     * 这轮同步是不是刚失败过（[BACKOFF_MS] 之内）。
     *
     * 鉴权失效、服务端 5xx、断网都会走到失败分支。不记时间的话，用户每切进
     * 用量页都会重打一遍服务端 —— 而网络层面的原因通常几分钟内自己就好了，
     * 重试得太勤只是白耗流量和电。手动下拉不受这个限制。
     */
    fun inBackoff(): Boolean {
        if (!enabled) return false
        val at = sp.getLong(failAtKey, 0L)
        if (at <= 0L) return false
        return System.currentTimeMillis() - at < BACKOFF_MS
    }

    /**
     * 进用量页时该不该自动同步。
     *
     * 三条都要满足：今天还没同步过、不在失败退避期、有账号。
     * 跨过零点 `syncedToday()` 自然变 false，第二天第一次进来就会再拉一遍。
     */
    fun shouldAutoSync(): Boolean = enabled && !syncedToday() && !inBackoff()

    /**
     * 还没拿到升数的账单 id，用于逐条补详情。
     *
     * 排除 [BillRecord.dead]：那类账单服务端已经查不到用量，再试也是白跑，
     * 留在队头只会把真正能补的挤到预算外面。
     */
    @Synchronized
    fun missingDetail(): List<String> =
        read()
            .filter { it.liters == null && !it.dead }
            .sortedByDescending { it.at }
            .map { it.id }

    /** 详情拉回来之后补字段。 */
    @Synchronized
    fun updateDetail(
        id: String,
        liters: Double?,
        secs: Double?,
        deviceName: String?,
        category: String? = null,
    ) {
        if (liters == null) return
        val list = read().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(
            liters = liters,
            secs = secs,
            deviceName = deviceName ?: list[index].deviceName,
            // 详情拿不到类型码时保留列表阶段写进去的那个，不能抹成 null
            category = category ?: list[index].category
        )
        write(list)
    }

    /**
     * 批量写入详情结果 —— **一次读、一次落盘**。
     *
     * 补详情是逐条发请求，一条一个 [updateDetail] 就是一次全表反序列化 + 全量
     * `commit()`；186 条账单要写 186 次，比网络本身还慢。这里把一批结果攒起来
     * 一次写完，同一批 20 条只付一次写盘成本。
     *
     * 只认 `id` 已存在的记录：同步过程中账号切换、账单被服务端删掉都不该凭空
     * 造出一条本地没有的账单。`liters = null` 且 `dead = true` 表示服务端查不到
     * 用量，打个标记让它退出补详情队列。
     *
     * @return 实际更新了几条
     */
    @Synchronized
    fun fillDetails(records: List<BillRecord>): Int {
        if (!enabled || records.isEmpty()) return 0
        val byId = read().associateBy { it.id }.toMutableMap()
        var updated = 0
        records.forEach { record ->
            val old = byId[record.id] ?: return@forEach
            if (record.liters == null && !record.dead) return@forEach
            byId[record.id] = old.copy(
                liters = record.liters ?: old.liters,
                secs = record.secs ?: old.secs,
                deviceName = record.deviceName ?: old.deviceName,
                dead = record.dead || old.dead,
                // 胖乖的详情带类型码，顺手补齐列表阶段没拿到的那几条老单；
                // 详情不带时保住本地已有的，不能抹成 null
                category = record.category ?: old.category
            )
            updated++
        }
        // 一条都没命中就不要白写一次盘
        if (updated == 0) return 0
        write(byId.values.toList())
        return updated
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

    /** 某月按饮水/淋浴拆开的合计。 */
    @Synchronized
    fun monthSplit(year: Int, month: Int): SplitTotals = splitOf(monthBills(year, month))

    /** 某年按饮水/淋浴拆开的合计。 */
    @Synchronized
    fun yearSplit(year: Int): SplitTotals =
        splitOf(all().filter { it.at.toLocalDate().year == year })

    /**
     * 一组账单按类型拆开。
     *
     * 判据只认淋浴码 `"08"`，其余（`"04"` 以及迁移前遗留的 null）都算饮水。
     * 两个平台的历史数据已经用 `backfillCategory` 补齐过码，null 只剩极端脏数据。
     */
    private fun splitOf(bills: List<BillRecord>): SplitTotals {
        fun sum(shower: Boolean) = BillTotals(
            liters = bills.filter { (it.category == BillRecord.CATEGORY_SHOWER) == shower }
                .mapNotNull { it.liters }.sum(),
            yuan = bills.filter { (it.category == BillRecord.CATEGORY_SHOWER) == shower }
                .sumOf { it.yuan }
        )
        return SplitTotals(drink = sum(shower = false), shower = sum(shower = true))
    }

    /** 某月有记录的天数。 */
    @Synchronized
    fun monthDays(year: Int, month: Int): Int =
        monthBills(year, month).map { it.at.toLocalDate() }.distinct().size

    /**
     * 某月每天的用量，key 是「日」(1..31)。
     *
     * @param category 传 `CATEGORY_DRINK` / `CATEGORY_SHOWER` 只统计该类；不传就是合计，
     *   与加这个参数之前的行为完全一致。
     */
    @Synchronized
    fun monthDailyLiters(year: Int, month: Int, category: String? = null): Map<Int, Double> =
        monthBills(year, month)
            .filter { category == null || it.category == category }
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

    private fun read(): List<BillRecord> =
        if (!enabled) emptyList() else parseJson(sp.getString(key, null))

    private fun write(bills: Collection<BillRecord>) {
        if (!enabled) return
        // 超出上限丢最旧的，防止 JSON 无限膨胀。
        // 账单是用户唯一的历史，被丢掉就再也拉不回来（服务端会过期），
        // 所以截断必须留个记录，出问题时能查到是什么时候开始丢的。
        val sorted = bills.sortedByDescending { it.at }
        if (sorted.size > MAX_BILLS) {
            AppLog.w("Bill", "账本超过 $MAX_BILLS 条，丢弃最旧的 ${sorted.size - MAX_BILLS} 条")
        }
        val trimmed = sorted.take(MAX_BILLS)
        val array = JSONArray()
        trimmed.forEach { bill -> array.put(encodeBill(bill)) }
        // 账单是用户唯一的历史记录，用 commit 同步落盘，进程被杀也不丢
        sp.edit().putString(key, array.toString()).commit()
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * 把「老板本按 uid 存的账本」合并进当前手机号的账本。
     *
     * 历史原因：2.0.0 早期版本的 `verify()` 把 `/acc/view-info` 的 `id`
     * （16 位十六进制账号 id）当成手机号回填，账本 owner 从 `bills_<手机号>`
     * 漂移成了 `bills_<uid>`。数据没丢，但换了个 key，界面上看起来就是「账本空了」。
     *
     * 这里在每次同步前跑一遍（幂等、无遗留时直接 return）：
     * 把 `bills_` 前缀下**后缀不是 11 位手机号**的 key 全部并进当前账本，然后删掉。
     * 后缀是 11 位手机号的一律不动 —— 那是别的账号的账本，不能并。
     *
     * @return 合并进来多少条本地没有的记录
     */
    @Synchronized
    fun migrateLegacyOwners(): Int {
        if (!enabled) return 0
        val stale = sp.all.keys.filter { k ->
            k.startsWith(KEY_PREFIX) &&
                k != key &&
                !k.removePrefix(KEY_PREFIX).matches(PHONE)
        }
        if (stale.isEmpty()) return 0

        var merged = 0
        stale.forEach { k ->
            // merge 会写盘并保住已有升数；空数据也照样把 key 清掉
            merged += merge(parseJson(sp.getString(k, null)))
        }
        sp.edit().also { editor -> stale.forEach { editor.remove(it) } }.apply()
        AppLog.i("Bill", "迁移遗留账本 ${stale.size} 个 key（$stale），合并进来 $merged 条")
        return merged
    }

    companion object {
        // ---------------- JSON 形状（账本读写与导出文件共用） ----------------

        /**
         * 把一段落盘 JSON 解成账单列表。
         *
         * 账本读取和「导入账单文件」走同一个函数 —— 导出文件里的 `bills` 数组
         * 就是落盘格式，不另造一套字段，也就不会出现「导出的能读、盘上的读不了」。
         *
         * 数据损坏就当空账本，宁可少显示也不要让页面崩。
         */
        internal fun parseJson(raw: String?): List<BillRecord> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val at = item.optLong("at", 0L)
                        if (id.isBlank() || at <= 0L) continue
                        val name = item.optString("n").takeIf { it.isNotBlank() }
                        add(
                            BillRecord(
                                id = id,
                                at = at,
                                yuan = item.optDouble("y", 0.0),
                                deviceName = name,
                                liters = if (item.has("l")) item.optDouble("l") else null,
                                secs = if (item.has("s")) item.optDouble("s") else null,
                                dead = item.optBoolean("d", false),
                                category = item.optString("c").takeIf { it.isNotBlank() }
                                    ?: guessCategory(name)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        /** 单条账单的落盘形状。导出文件直接复用它，两边字段永远一致。 */
        internal fun encodeBill(bill: BillRecord): JSONObject {
            val obj = JSONObject()
                .put("id", bill.id)
                .put("at", bill.at)
                .put("y", round3(bill.yuan))
            bill.deviceName?.let { obj.put("n", it) }
            bill.liters?.let { obj.put("l", round3(it)) }
            bill.secs?.let { obj.put("s", it) }
            if (bill.dead) obj.put("d", true)
            bill.category?.let { obj.put("c", it) }
            return obj
        }

        /**
         * 老账本没有类型码时的兜底。
         *
         * 加 `category` 之前的记录只有设备名，实测设备上两本账本的形状是：
         * - 798：「雅3——1洗热间—热水龙头5」「雅3——1洗热间—淋浴6」→ 淋浴
         * - 胖乖：「雅3-1热」「雅3-1冷」「静4-4热」→ 饮水机的热/冷水口
         *
         * 所以只认「淋浴 / 热水龙头 / 洗热」这几个明确指向淋浴的字样，其余按饮水。
         * 这是启发式、不是权威：新账单都自带服务端的 `goodsCategory`，根本走不到这里；
         * 只有「加字段之前就已存在、且服务端已清账查不回来」的那几条才用得上，
         * 判错了也不会覆盖任何权威数据。
         */
        private fun guessCategory(name: String?): String {
            val n = name.orEmpty()
            val shower = n.contains("淋浴") || n.contains("热水龙头") || n.contains("洗热")
            return if (shower) BillRecord.CATEGORY_SHOWER else BillRecord.CATEGORY_DRINK
        }

        private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0


        private const val PREFS = "bill_store"
        private const val KEY_PREFIX = "bills_"
        private const val MAX_BILLS = 3000

        /** 同步失败后的自动重试冷却时间，30 分钟。手动下拉不受限。 */
        private const val BACKOFF_MS = 30 * 60 * 1000L

        /** 账本归属只可能是手机号，11 位数字。 */
        private val PHONE = Regex("""\d{11}""")

        /**
         * 「存量账单已补类型码」的落戳，毫秒时间。0 / 不存在表示还没跑过。
         *
         * 用时间戳而不是布尔：既是「跑过了」的开关，也顺手记下分界线，
         * 排查时能知道哪一批算存量。
         */
        private const val TAG_AT = "catTagAt"

        /** 「上一版归档标已清理」的落戳，和 [TAG_AT] 分开，两件事各自幂等。 */
        private const val CLEAN_AT = "catLegacyCleanAt"

        /**
         * 给加 [BillRecord.category] 之前的 798 存量账单补上「淋浴」码，
         * 并清掉上一版误加的 `"a"`（归档标）残留。
         *
         * 798 那些数据全是淋浴/热水龙头（惠生活在该账号下没有饮水单），
         * 但盘上一条 `c` 都没有 —— 只有读的时候才临时猜一个。不落盘的话：
         * - 以后做饮水/淋浴分开统计，这批老账要么被算进「未知」，要么每次都要靠名字猜
         * - 名字判据（含「淋浴/热水龙头/洗热」）对这批数据恰好成立，但那是巧合，不该依赖
         *
         * 所以一次性写死：**只给 798 的账本（`bills_*`）补码**，**只补缺 `c` 的**，
         * 已有码的一律不动 —— 将来 798 真有饮水单，接口给的 `"04"` 不会被这里覆盖。
         * 胖乖的 key 以 `pangguai-` 开头，不会被误改。
         *
         * 清理 `"a"` 则要扫两本账（上一版对胖乖全量写过这个键），单独一个落戳。
         *
         * 直接改原始 JSON 再写回，不走 [write] —— 不排序、不截断、不做数值四舍五入，
         * 条数、顺序、金额与改动前逐字一致。中途被杀则落戳不写，下次重跑幂等。
         */
        fun backfillCategory(context: Context) {
            val sp = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val bookKeys = sp.all.keys.filter { bookKey ->
                bookKey.startsWith(KEY_PREFIX) ||
                    bookKey.startsWith(Platform.PANGGUAI.prefix + KEY_PREFIX)
            }
            if (bookKeys.isEmpty()) return

            // ---------- 第一步：798 补淋浴码 ----------
            if (sp.getLong(TAG_AT, 0L) <= 0L) {
                var books = 0
                var tagged = 0
                bookKeys.filter { it.startsWith(KEY_PREFIX) }.forEach { bookKey ->
                    val array = readArray(sp, bookKey) ?: return@forEach
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        if (obj.optString("c").isBlank()) {
                            obj.put("c", BillRecord.CATEGORY_SHOWER)
                            tagged++
                        }
                    }
                    // commit 同步落盘：账本是用户唯一的历史，宁可慢一点也不能丢
                    sp.edit().putString(bookKey, array.toString()).commit()
                    books++
                }
                sp.edit().putLong(TAG_AT, System.currentTimeMillis()).apply()
                AppLog.i("Bill", "798 存量账单补分类：$books 本账本、补 $tagged 条（淋浴）")
            }

            // ---------- 第二步：清掉归档标残渣 ----------
            if (sp.getLong(CLEAN_AT, 0L) <= 0L) {
                var books = 0
                var cleaned = 0
                bookKeys.forEach { bookKey ->
                    val array = readArray(sp, bookKey) ?: return@forEach
                    var touched = false
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        if (obj.has("a")) {
                            obj.remove("a")
                            cleaned++
                            touched = true
                        }
                    }
                    if (touched) {
                        sp.edit().putString(bookKey, array.toString()).commit()
                        books++
                    }
                }
                val editor = sp.edit().putLong(CLEAN_AT, System.currentTimeMillis())
                // 上一版的落戳已经没用了，顺手删掉，别在盘上留两个含义相近的 key
                if (sp.contains("catArchiveAt")) editor.remove("catArchiveAt")
                editor.apply()
                AppLog.i("Bill", "清理归档标残留：$books 本账本、$cleaned 条")
            }
        }

        private fun readArray(sp: SharedPreferences, key: String): JSONArray? {
            val raw = sp.getString(key, null) ?: return null
            return runCatching { JSONArray(raw) }.getOrNull()
        }

        /**
         * 删掉「按账号隔离」之前的单份账本。
         *
         * 那时候所有账号共用 `bills` 这一个 key，里面的数据已经分不清是谁的了，
         * 留着只会误导 —— 启动时清一次，之后永远走 `bills_<owner>`。
         *
         * **只删 `bills` 这一个 key**，其他一律交给 [migrateLegacyOwners] 处理：
         * 早期版本把 `data.id`（16 位十六进制）当手机号用过，那些 key 里是**真数据**，
         * 直接删就永久丢了，必须先合并再删。
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
