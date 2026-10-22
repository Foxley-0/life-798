package com.water.widget

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 从积分流水中识别一次饮水消费。
 *
 * 服务端当前没有返回可靠的出水毫升数字，水量仅按用户设置的比例估算。
 */
data class WaterConsumption(
    val spentScore: Int,
    val occurredAt: Long,
    val paymentYuan: Double? = null,
    val historyKey: String
) {
    val scoreEquivalent: Int
        get() = if (spentScore > 0) {
            spentScore
        } else {
            ((paymentYuan ?: 0.0) * 1000).roundToInt()
        }

    val moneyText: String
        get() = "¥${String.format(Locale.CHINA, "%.2f", paymentYuan ?: spentScore / 1000.0)}"

    val completionTitle: String
        get() = if (scoreEquivalent == 0) "设备使用已结束" else "接水完成"

    fun completionText(estimate: WaterEstimate = WaterEstimate()): String =
        if (scoreEquivalent == 0) "本次未产生费用"
        else if (estimate.visible) "本次消费 $moneyText · 水量约 ${estimate.waterText(scoreEquivalent)}"
        else "本次消费 $moneyText"
}

object WaterBillParser {
    fun recordKeys(billJson: JSONObject?): Set<String> {
        if (billJson == null || billJson.optInt("code", -999) != 0) return emptySet()
        val records = WaterRecordFields.readRecords(billJson) ?: return emptySet()
        return buildSet {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                if (record.optInt("type", Int.MIN_VALUE) == 91) {
                    add(WaterRecordFields.recordKey("bill", record, record.optJSONObject("data")))
                }
            }
        }
    }

    fun candidateIds(
        billJson: JSONObject?,
        sinceMillis: Long,
        excludedRecordKeys: Set<String>,
        expectedDeviceId: String
    ): List<String> {
        if (billJson == null || billJson.optInt("code", -999) != 0) return emptyList()
        val records = WaterRecordFields.readRecords(billJson) ?: return emptyList()
        return (0 until records.length()).mapNotNull(records::optJSONObject)
            .filter { isCompletedWaterBill(it) }
            .filter { WaterRecordFields.recordKey("bill", it, it.optJSONObject("data")) !in excludedRecordKeys }
            .filter { WaterRecordFields.normalizeEpochMillis(it.optLong("utime", it.optLong("ctime", 0L))) >= sinceMillis }
            .filter {
                val deviceId = WaterRecordFields.readDeviceId(it, it.optJSONObject("data"))
                expectedDeviceId.isBlank() || deviceId == null || deviceId == expectedDeviceId
            }
            .sortedByDescending { it.optLong("utime", it.optLong("ctime", 0L)) }
            .mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .distinct()
    }

    fun fromDetail(response: JSONObject?, expectedBillId: String, expectedDeviceId: String): WaterConsumption? {
        if (response == null || response.optInt("code", -999) != 0) return null
        val bill = response.optJSONObject("data")?.optJSONObject("bill") ?: return null
        if (bill.optString("id") != expectedBillId) return null
        return latestSince(JSONObject().put("code", 0).put("data", org.json.JSONArray().put(bill)), 0L, expectedDeviceId)
    }

    private fun isCompletedWaterBill(record: JSONObject): Boolean =
        record.optInt("type", Int.MIN_VALUE) == 91 &&
            record.optInt("status", Int.MIN_VALUE) == 3 &&
            (!record.has("cata") || record.optInt("cata") == 6)

    fun latestSince(
        billJson: JSONObject?,
        sinceMillis: Long,
        expectedDeviceId: String = "",
        excludedRecordKeys: Set<String> = emptySet()
    ): WaterConsumption? {
        if (billJson == null || billJson.optInt("code", -999) != 0) return null
        val records = WaterRecordFields.readRecords(billJson) ?: return null
        var latest: WaterConsumption? = null
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (!isCompletedWaterBill(record)) continue
            val recordKey = WaterRecordFields.recordKey(
                "bill",
                record,
                record.optJSONObject("data")
            )
            if (recordKey in excludedRecordKeys) continue
            val updatedAt = WaterRecordFields.normalizeEpochMillis(record.optLong("utime", 0L))
            val createdAt = WaterRecordFields.normalizeEpochMillis(record.optLong("ctime", 0L))
            val occurredAt = updatedAt.takeIf { it > 0L } ?: createdAt
            if (occurredAt < sinceMillis) continue
            // 已核验：积分抵扣记录在详情的 promo.type=4 / discount，列表 payment 可以为零。
            val scoreDiscount = if (record.optJSONObject("promo")?.optInt("type") == 4) {
                record.optDouble("discount", Double.NaN)
            } else 0.0
            val payment = record.optDouble("payment", Double.NaN) + scoreDiscount
            if (!payment.isFinite() || payment < 0.0) continue

            val explicitDeviceId = WaterRecordFields.readDeviceId(record, record.optJSONObject("data"))
            if (expectedDeviceId.isNotBlank() &&
                explicitDeviceId != null &&
                explicitDeviceId != expectedDeviceId
            ) {
                continue
            }

            if (latest == null || occurredAt > latest.occurredAt) {
                latest = WaterConsumption(
                    spentScore = 0,
                    occurredAt = occurredAt,
                    paymentYuan = payment,
                    historyKey = WaterRecordFields.recordKey("bill", record, record.optJSONObject("data"))
                )
            }
        }
        return latest
    }
}

object WaterConsumptionParser {
    private val amountKeys = arrayOf(
        "spend", "score", "changeScore", "change_score", "amount", "value", "num", "points"
    )

    fun recordKeys(scoreJson: JSONObject?): Set<String> {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return emptySet()
        val records = WaterRecordFields.readRecords(scoreJson) ?: return emptySet()
        return buildSet {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val data = record.optJSONObject("data")
                if (isConsumption(record, data)) {
                    add(WaterRecordFields.recordKey("score", record, data))
                }
            }
        }
    }

    fun latestSince(
        scoreJson: JSONObject?,
        sinceMillis: Long,
        expectedDeviceId: String = "",
        excludedRecordKeys: Set<String> = emptySet()
    ): WaterConsumption? {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return null
        val records = WaterRecordFields.readRecords(scoreJson) ?: return null
        var latest: WaterConsumption? = null
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val data = record.optJSONObject("data")
            val occurredAt = WaterRecordFields.normalizeEpochMillis(record.optLong("ctime", 0L))
            if (occurredAt < sinceMillis) continue
            if (WaterRecordFields.recordKey("score", record, data) in excludedRecordKeys) continue

            val explicitDeviceId = WaterRecordFields.readDeviceId(record, data)
            if (expectedDeviceId.isNotBlank() &&
                explicitDeviceId != null &&
                explicitDeviceId != expectedDeviceId
            ) {
                continue
            }

            val amount = readSpentScore(record, data)
            if (amount <= 0 || !isConsumption(record, data)) continue
            if (latest == null || occurredAt > latest.occurredAt) {
                latest = WaterConsumption(
                    spentScore = amount,
                    occurredAt = occurredAt,
                    historyKey = data?.optString("sn")?.takeIf(String::isNotBlank)
                        ?.let { "bill:id:$it" } ?: WaterRecordFields.recordKey("score", record, data)
                )
            }
        }
        return latest
    }

    private fun readSpentScore(record: JSONObject, data: JSONObject?): Int {
        // 已验证的设备消费优先使用 spend；避免顶层 score=0 遮住 data.spend。
        readNonZero(data, "spend")?.let { return abs(it) }
        readNonZero(record, "spend")?.let { return abs(it) }

        val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
        if (type != 107) return 0
        for (key in amountKeys) {
            if (key == "spend") continue
            readNonZero(record, key)?.let { return abs(it) }
        }
        if (data != null) {
            for (key in amountKeys) {
                if (key == "spend") continue
                readNonZero(data, key)?.let { return abs(it) }
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
}

private object WaterRecordFields {
    private val deviceKeys = arrayOf("did", "deviceId", "device_id")
    private val recordIdKeys = arrayOf("id", "billId", "bill_id", "orderId", "order_id", "serialNo", "sn")

    fun readRecords(root: JSONObject): org.json.JSONArray? {
        root.optJSONArray("data")?.let { return it }
        val data = root.optJSONObject("data") ?: return null
        for (key in arrayOf("records", "items", "list", "content")) {
            data.optJSONArray(key)?.let { return it }
        }
        return null
    }

    fun recordKey(prefix: String, record: JSONObject, data: JSONObject?): String {
        for (key in recordIdKeys) {
            record.optString(key, "").trim().takeIf(String::isNotEmpty)?.let {
                return "$prefix:id:$it"
            }
        }
        if (data != null) {
            for (key in recordIdKeys) {
                data.optString(key, "").trim().takeIf(String::isNotEmpty)?.let {
                    return "$prefix:id:$it"
                }
            }
        }
        // 不包含 status/utime，确保旧的待确认订单变更状态后仍被视为同一条基线记录。
        return listOf(
            prefix,
            record.optLong("ctime", 0L).toString(),
            record.optInt("type", Int.MIN_VALUE).toString(),
            record.optString("owner", ""),
            record.optString("msg", ""),
            data?.optString("adId", "").orEmpty(),
            data?.optString("did", "").orEmpty()
        ).joinToString("|")
    }

    fun readDeviceId(record: JSONObject, data: JSONObject?): String? {
        record.optJSONObject("dev")?.optString("id")?.takeIf(String::isNotBlank)?.let { return it }
        for (key in deviceKeys) {
            record.optString(key, "").trim().takeIf(String::isNotEmpty)?.let { return it }
        }
        if (data != null) {
            for (key in deviceKeys) {
                data.optString(key, "").trim().takeIf(String::isNotEmpty)?.let { return it }
            }
        }
        return null
    }

    fun normalizeEpochMillis(value: Long): Long = when {
        value <= 0L -> 0L
        value < 10_000_000_000L -> value * 1000L
        else -> value
    }
}
