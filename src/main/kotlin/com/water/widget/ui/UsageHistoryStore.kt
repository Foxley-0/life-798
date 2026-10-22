package com.water.widget.ui

import android.content.Context
import com.water.widget.WaterConsumption
import com.water.widget.WaterEstimate
import com.water.widget.WaterUsageSettings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 将服务端当前可见的积分流水与本地确认的设备账单合并到日汇总。
 * 服务端缩短流水窗口时，已经记录到本地的历史不会随之减少。
 */
object UsageHistoryStore {
    private const val PREFS = "usage_history_v1"
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val settlements = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val waterSettled = settlements.asSharedFlow()

    fun notifyWaterSettled(accountKey: String) {
        settlements.tryEmit(accountKey)
    }

    @Synchronized
    fun mergeAndRead(context: Context, accountKey: String, scoreJson: JSONObject?): WaterUsageUiState {
        val estimate = WaterUsageSettings.read(context)
        if (accountKey.isBlank()) return WaterUsageUiState(showWaterEstimate = estimate.visible)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "account_${digest(accountKey)}"
        val ledger = UsageHistoryLedger.fromJson(
            runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        )
        if (ledger.merge(scoreJson)) {
            prefs.edit().putString(key, ledger.toJson().toString()).apply()
            revision.value += 1
        }
        return ledger.toUiState(estimate = estimate)
    }

    @Synchronized
    fun record(context: Context, accountKey: String, consumption: WaterConsumption) {
        if (accountKey.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "account_${digest(accountKey)}"
        val ledger = UsageHistoryLedger.fromJson(
            runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        )
        if (ledger.recordLocal(consumption.historyKey, consumption.occurredAt, consumption.scoreEquivalent)) {
            prefs.edit().putString(key, ledger.toJson().toString()).apply()
            revision.value += 1
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal class UsageHistoryLedger(
    private val seen: LinkedHashSet<String> = LinkedHashSet(),
    private val dayTotals: MutableMap<String, Int> = linkedMapOf(),
    private var localThrough: Long = 0L,
    private val bills: MutableMap<String, Pair<String, Int>> = linkedMapOf()
) {
    fun merge(scoreJson: JSONObject?): Boolean {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return false
        val records = scoreJson.optJSONArray("data") ?: return false
        var changed = false
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val data = record.optJSONObject("data")
            val amount = readSpentScore(record, data)
            if (amount <= 0) continue
            val time = normalizeEpochMillis(record.optLong("ctime", 0L))
            if (!isConsumption(record, data)) {
                // 只撤销旧版本确实累计过的同一条流水，不清空窗口外的历史。
                if (seen.remove(fingerprint(recordIdentity(record)))) {
                    val day = dayKey(time)
                    dayTotals[day] = (dayTotals[day] ?: 0) - amount
                    changed = true
                }
                continue
            }
            val billId = data?.optString("sn")?.takeIf(String::isNotBlank)
            if (billId != null) {
                val key = "bill:id:$billId"
                val hash = fingerprint(key)
                // 将旧积分流水迁移到同一账单，保留历史金额；已经记过账单时扣回旧的重复项。
                if (seen.remove(fingerprint(recordIdentity(record)))) {
                    val day = dayKey(time)
                    if (bills.containsKey(hash) || seen.contains(hash)) {
                        dayTotals[day] = (dayTotals[day] ?: 0) - amount
                    } else {
                        bills[hash] = day to amount
                    }
                    changed = true
                }
                if (time <= localThrough && !bills.containsKey(hash) && !seen.contains(hash)) continue
                if (recordBill(key, time, amount)) changed = true
            } else {
                if (time <= localThrough) continue
                if (record(recordIdentity(record), time, amount)) changed = true
            }
        }
        return changed
    }

    fun recordLocal(historyKey: String, time: Long, amount: Int): Boolean {
        val occurredAt = normalizeEpochMillis(time)
        if (historyKey.isBlank() || occurredAt <= 0L || amount <= 0) return false
        return if (historyKey.startsWith("bill:id:")) recordBill(historyKey, occurredAt, amount)
        else record(historyKey, occurredAt, amount)
    }

    private fun recordBill(historyKey: String, occurredAt: Long, amount: Int): Boolean {
        val hash = fingerprint(historyKey)
        val previous = bills[hash]
        if (previous != null) {
            if (amount <= previous.second) return false
            dayTotals[previous.first] = (dayTotals[previous.first] ?: 0) + amount - previous.second
            bills[hash] = previous.first to amount
            return true
        }
        val day = dayKey(occurredAt)
        if (!seen.remove(hash)) dayTotals[day] = (dayTotals[day] ?: 0) + amount
        bills[hash] = day to amount
        return true
    }

    private fun record(historyKey: String, occurredAt: Long, amount: Int): Boolean {
        if (!seen.add(fingerprint(historyKey))) return false
        val day = dayKey(occurredAt)
        dayTotals[day] = (dayTotals[day] ?: 0) + amount
        return true
    }

    fun toUiState(now: Calendar = Calendar.getInstance(), estimate: WaterEstimate = WaterEstimate()): WaterUsageUiState {
        if (dayTotals.isEmpty()) return WaterUsageUiState(showWaterEstimate = estimate.visible)
        val todayKey = dayKey(now.timeInMillis)
        val monthPrefix = "%04d-%02d".format(
            Locale.ROOT,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1
        )
        val yearPrefix = "%04d".format(Locale.ROOT, now.get(Calendar.YEAR))
        val today = dayTotals[todayKey] ?: 0
        val month = dayTotals.filterKeys { it.startsWith(monthPrefix) }.values.sum()
        val year = dayTotals.filterKeys { it.startsWith(yearPrefix) }.values.sum()
        return WaterUsageUiState(
            todayCostText = moneyText(today),
            monthCostText = moneyText(month),
            yearCostText = moneyText(year),
            todayWaterText = estimate.waterText(today),
            monthWaterText = estimate.waterText(month),
            yearWaterText = estimate.waterText(year),
            showWaterEstimate = estimate.visible
        )
    }

    fun toJson(): JSONObject {
        val days = JSONObject()
        dayTotals.forEach { (day, total) -> days.put(day, total) }
        val billEntries = JSONObject()
        bills.forEach { (key, entry) ->
            billEntries.put(key, JSONObject().put("day", entry.first).put("amount", entry.second))
        }
        return JSONObject()
            .put("bills", billEntries)
            .put("seen", JSONArray(seen.toList()))
            .put("days", days)
            .put("localThrough", localThrough)
    }

    companion object {
        fun fromJson(json: JSONObject): UsageHistoryLedger {
            val seen = LinkedHashSet<String>()
            json.optJSONArray("seen")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(seen::add)
                }
            }
            val days = linkedMapOf<String, Int>()
            json.optJSONObject("days")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val day = keys.next()
                    values.optInt(day, 0).takeIf { it >= 0 }?.let { days[day] = it }
                }
            }
            val bills = linkedMapOf<String, Pair<String, Int>>()
            json.optJSONObject("bills")?.let { entries ->
                entries.keys().forEach { key ->
                    val entry = entries.getJSONObject(key)
                    bills[key] = entry.getString("day") to entry.getInt("amount")
                }
            }
            return UsageHistoryLedger(seen, days, json.optLong("localThrough", 0L), bills)
        }

        private fun recordIdentity(record: JSONObject): String {
            return arrayOf("id", "sid", "serialNo", "orderId", "bizId")
                .firstNotNullOfOrNull { key -> record.optString(key, "").takeIf(String::isNotBlank) }
                ?: record.toString()
        }

        private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun dayKey(time: Long): String {
            val date = Calendar.getInstance().apply { timeInMillis = time }
            return "%04d-%02d-%02d".format(
                Locale.ROOT,
                date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH)
            )
        }

        private fun readSpentScore(record: JSONObject, data: JSONObject?): Int {
            val keys = arrayOf("spend", "score", "changeScore", "change_score", "amount", "value", "num", "points")
            readNonZero(data, "spend")?.let { return kotlin.math.abs(it) }
            readNonZero(record, "spend")?.let { return kotlin.math.abs(it) }
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            if (type != 107) return 0
            for (key in keys) {
                if (key != "spend") readNonZero(record, key)?.let { return kotlin.math.abs(it) }
            }
            if (data != null) {
                for (key in keys) {
                    if (key != "spend") readNonZero(data, key)?.let { return kotlin.math.abs(it) }
                }
            }
            return 0
        }

        private fun isConsumption(record: JSONObject, data: JSONObject?): Boolean {
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            return type == 107
        }

        private fun readNonZero(json: JSONObject?, key: String): Int? {
            if (json == null || !json.has(key) || json.isNull(key)) return null
            return json.optInt(key, 0).takeIf { it != 0 }
        }

        private fun normalizeEpochMillis(value: Long): Long = when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }

        private fun moneyText(score: Int): String = "¥${String.format(Locale.CHINA, "%.2f", score / 1000.0)}"
    }
}
