package com.life798.mini.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/** 一条用水流水。结算完成时追加一条，不覆盖。 */
data class UsageEntry(val at: Long, val liters: Double)

/**
 * 用水账本。
 *
 * 服务端只有设备累计值 `gene.out`，没有按天的时间序列，也没有可用的账单接口，
 * 所以历史只能靠自己记 —— 每结算一次追加一条流水，日/月/年总量在读取时聚合。
 *
 * 存流水而不是存日汇总：一天可能接好几次水，账本要能逐笔显示。
 */
class UsageLedger(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 追加一条流水。用量为 0 或负数时直接丢弃，避免空按一次留个 0。 */
    @Synchronized
    fun append(at: Long = System.currentTimeMillis(), liters: Double) {
        if (liters <= 0.0) return
        val next = read().toMutableList()
        next += UsageEntry(at, round3(liters))
        // 超出上限丢最旧的，防止 JSON 无限膨胀
        write(if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next)
    }

    /** 某月全部流水，按时间倒序 —— 账本惯例是最新的在最上面。 */
    @Synchronized
    fun monthEntries(year: Int, month: Int): List<UsageEntry> =
        read()
            .filter { entry ->
                val day = entry.at.toLocalDate()
                day.year == year && day.monthValue == month
            }
            .sortedByDescending { it.at }

    @Synchronized
    fun monthTotal(year: Int, month: Int): Double = monthEntries(year, month).sumOf { it.liters }

    @Synchronized
    fun yearTotal(year: Int): Double =
        read().filter { it.at.toLocalDate().year == year }.sumOf { it.liters }

    /** 某月有记录的天数。 */
    @Synchronized
    fun monthDays(year: Int, month: Int): Int =
        monthEntries(year, month).map { it.at.toLocalDate() }.distinct().size

    /** 某月每天合计，key 是「日」(1..31)，给日历上色用。 */
    @Synchronized
    fun monthDailyTotals(year: Int, month: Int): Map<Int, Double> =
        monthEntries(year, month)
            .groupBy { it.at.toLocalDate().dayOfMonth }
            .mapValues { (_, entries) -> entries.sumOf { it.liters } }

    /** 最早一条流水的时间，没有记录返回 null。用于限制月份翻页范围。 */
    @Synchronized
    fun earliestAt(): Long? = read().minOfOrNull { it.at }

    // ---------------- 内部 ----------------

    private fun read(): List<UsageEntry> {
        val raw = sp.getString(KEY_ENTRIES, null) ?: return emptyList()
        // 数据损坏就当空账本，宁可少显示也不要让页面崩
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val at = item.optLong("at", 0L)
                    val liters = item.optDouble("l", 0.0)
                    if (at > 0L && liters > 0.0) add(UsageEntry(at, liters))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<UsageEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("at", entry.at).put("l", entry.liters))
        }
        sp.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        const val PREFS = "usage_ledger"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 2000
    }
}
