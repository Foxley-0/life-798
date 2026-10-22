package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaterConsumptionParserTest {
    @Test
    fun mixedWalletAndPointsBillCountsFullCostWithoutAddingWalletBreakdownAgain() {
        val bill = JSONObject().put("id", "mixed-water").put("type", 91).put("cata", 6)
            .put("status", 3).put("payment", 0.12).put("discount", 0.08)
            .put("promo", JSONObject().put("type", 4).put("discount", 0.08))
            .put("mk", JSONObject().put("olGift", 0.12))
            .put("utime", 1_800_000_022_000L)
            .put("dev", JSONObject().put("id", "water-device"))
        val detail = JSONObject().put("code", 0).put("data", JSONObject().put("bill", bill))
        val result = WaterBillParser.fromDetail(detail, "mixed-water", "water-device")
        assertEquals(200, result?.scoreEquivalent)
        assertEquals("¥0.20", result?.moneyText)
        assertEquals("bill:id:mixed-water", result?.historyKey)
    }

    @Test
    fun completedPointsBillUsesDetailAndExcludesExchange() {
        val record = JSONObject().put("id", "bill-160").put("type", 91).put("cata", 6)
            .put("status", 3).put("payment", 0).put("discount", 0.16)
            .put("promo", JSONObject().put("type", 4))
            .put("ctime", 1_800_000_001_000L).put("utime", 1_800_000_022_000L)
            .put("dev", JSONObject().put("id", "water-device"))
        val detail = JSONObject().put("code", 0).put("data", JSONObject().put("bill", record))
        val result = WaterBillParser.fromDetail(detail, "bill-160", "water-device")
        assertEquals(160, result?.scoreEquivalent)
        assertEquals("¥0.16", result?.moneyText)
        assertEquals("bill:id:bill-160", result?.historyKey)
        assertNull(WaterBillParser.fromDetail(detail, "bill-160", "other-device"))
        assertNull(WaterBillParser.fromDetail(detail, "other-bill", "water-device"))
        record.put("cata", 1)
        assertNull(WaterBillParser.fromDetail(detail, "bill-160", "water-device"))
    }

    @Test
    fun zeroPaymentCandidateCanBeSelectedForDetailVerification() {
        val record = JSONObject().put("id", "bill-160").put("type", 91).put("cata", 6)
            .put("status", 3).put("payment", 0).put("utime", 1_800_000_022_000L)
        val response = JSONObject().put("code", 0).put("data", JSONArray().put(record))
        assertEquals(listOf("bill-160"), WaterBillParser.candidateIds(response, 1_800_000_000_000L, emptySet(), ""))
        assertEquals(emptyList<String>(), WaterBillParser.candidateIds(response, 0L, setOf("bill:id:bill-160"), ""))
    }

    @Test
    fun scoreUsesBillNumberAndKeepsSameTimestampRecordsDistinct() {
        val records = JSONArray()
        for (id in listOf("one", "two")) {
            records.put(JSONObject().put("type", 107).put("ctime", 1_800_000_001_000L)
                .put("data", JSONObject().put("sn", id).put("spend", 160)))
        }
        val response = JSONObject().put("code", 0).put("data", records)
        assertEquals(2, WaterConsumptionParser.recordKeys(response).size)
        val result = WaterConsumptionParser.latestSince(response, 0L, "", setOf("score:id:one"))
        assertEquals("bill:id:two", result?.historyKey)
    }

    @Test
    fun readsLatestConsumptionForCurrentDevice() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("ctime", 1_800_000_001_000L)
                            .put("type", 107)
                            .put("spend", 80)
                            .put("data", JSONObject().put("did", "other-device"))
                    )
                    .put(
                        JSONObject()
                            .put("ctime", 1_800_000_002_000L)
                            .put("type", 107)
                            .put("spend", 160)
                            .put("data", JSONObject().put("did", "selected-device"))
                    )
            )

        val result = WaterConsumptionParser.latestSince(
            json,
            sinceMillis = 1_800_000_000_000L,
            expectedDeviceId = "selected-device"
        )

        assertEquals(160, result?.spentScore)
        assertEquals("¥0.16", result?.moneyText)
        assertEquals("本次消费 ¥0.16 · 水量约 500 ml", result?.completionText())
    }

    @Test
    fun supportsSecondBasedTimestampsForVerifiedConsumption() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                        JSONObject()
                            .put("ctime", 1_800_000_005L)
                            .put("type", 107)
                            .put("score", -335)
                            .put("title", "饮水消费")
                )
            )

        val result = WaterConsumptionParser.latestSince(json, 1_800_000_000_000L)

        assertEquals(335, result?.spentScore)
        assertEquals("本次消费 ¥0.34 · 水量约 1.0 L", result?.completionText())
    }

    @Test
    fun ignoresIncomeAndOldConsumption() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("ctime", 1_700_000_000_000L)
                            .put("type", 107)
                            .put("spend", 160)
                    )
                    .put(
                        JSONObject()
                            .put("ctime", 1_800_000_001_000L)
                            .put("score", 10)
                            .put("title", "任务奖励")
                    )
            )

        assertNull(WaterConsumptionParser.latestSince(json, 1_800_000_000_000L))
    }

    @Test
    fun readsActualPaymentFromDeviceBill() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "recharge")
                            .put("type", 2)
                            .put("ctime", 1_800_000_002_000L)
                            .put("payment", 10.0)
                    )
                    .put(
                        JSONObject()
                            .put("id", "water")
                            .put("type", 91)
                            .put("ctime", 1_800_000_003_000L)
                            .put("utime", 1_800_000_004_000L)
                            .put("status", 3)
                            .put("payment", 0.25)
                    )
            )

        val result = WaterBillParser.latestSince(json, 1_800_000_000_000L)

        assertEquals("¥0.25", result?.moneyText)
        assertEquals("本次消费 ¥0.25 · 水量约 780 ml", result?.completionText())
    }

    @Test
    fun onlyAcceptsNewCompletedBill() {
        val oldId = "old-water"
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", oldId)
                            .put("type", 91)
                            .put("ctime", 1_800_000_001_000L)
                            .put("utime", 1_800_000_010_000L)
                            .put("status", 3)
                            .put("payment", 0.16)
                    )
                    .put(
                        JSONObject()
                            .put("id", "pending-water")
                            .put("type", 91)
                            .put("ctime", 1_800_000_011_000L)
                            .put("status", 2)
                            .put("payment", 0.32)
                    )
                    .put(
                        JSONObject()
                            .put("id", "new-water")
                            .put("type", 91)
                            .put("ctime", 1_800_000_012_000L)
                            .put("status", 3)
                            .put("payment", 0.48)
                    )
            )

        val baseline = setOf("bill:id:$oldId")
        val result = WaterBillParser.latestSince(
            json,
            sinceMillis = 1_800_000_000_000L,
            excludedRecordKeys = baseline
        )

        assertEquals("¥0.48", result?.moneyText)
    }

    @Test
    fun baselineKeepsOldPendingBillExcludedAfterCompletion() {
        val pending = JSONObject()
            .put("id", "same-order")
            .put("type", 91)
            .put("ctime", 1_800_000_001_000L)
            .put("status", 2)
            .put("payment", 0.16)
        val before = JSONObject().put("code", 0).put("data", JSONArray().put(pending))
        val baseline = WaterBillParser.recordKeys(before)

        pending.put("status", 3).put("utime", 1_800_000_010_000L)
        val after = JSONObject().put("code", 0).put("data", JSONArray().put(pending))

        assertNull(
            WaterBillParser.latestSince(
                after,
                sinceMillis = 1_800_000_000_000L,
                excludedRecordKeys = baseline
            )
        )
    }

    @Test
    fun nestedSpendWinsOverZeroTopLevelScore() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("id", "score-water")
                        .put("type", 107)
                        .put("ctime", 1_800_000_001_000L)
                        .put("score", 0)
                        .put("data", JSONObject().put("spend", 160))
                )
            )

        assertEquals(
            160,
            WaterConsumptionParser.latestSince(json, 1_800_000_000_000L)?.spentScore
        )
    }

    @Test
    fun excludesExchangeLotteryAndUnknownSpending() {
        val records = JSONArray()
        for (type in listOf(105, 106, 999)) {
            records.put(JSONObject().put("type", type).put("ctime", 1_800_000_001_000L)
                .put("msg", "用户消费积分").put("data", JSONObject().put("spend", 100)))
        }
        assertNull(WaterConsumptionParser.latestSince(JSONObject().put("code", 0).put("data", records), 0L))
    }

    @Test
    fun taskRewardTextIsNotTreatedAsConsumption() {
        val json = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("ctime", 1_800_000_001_000L)
                        .put("score", 20)
                        .put("title", "使用支付宝完成任务")
                )
            )

        assertNull(WaterConsumptionParser.latestSince(json, 1_800_000_000_000L))
    }
    @Test
    fun completedZeroBillEndsSessionWithoutCostOrEstimatedWater() {
        val bill = JSONObject().put("id", "zero").put("type", 91).put("cata", 6)
            .put("status", 3).put("payment", 0.0).put("discount", 0.0)
            .put("utime", 1_800_000_002_000L).put("dev", JSONObject().put("id", "device-a"))
        val detail = JSONObject().put("code", 0).put("data", JSONObject().put("bill", bill))
        val result = WaterBillParser.fromDetail(detail, "zero", "device-a")!!
        assertEquals(0, result.scoreEquivalent)
        assertEquals("设备使用已结束", result.completionTitle)
        assertEquals("本次未产生费用", result.completionText())
        assertNull(WaterBillParser.fromDetail(detail, "zero", "other-device"))
        bill.put("status", 2)
        assertNull(WaterBillParser.fromDetail(detail, "zero", "device-a"))
    }

    @Test
    fun missingPaymentIsNotMisreportedAsZeroCostCompletion() {
        val bill = JSONObject().put("id", "unknown").put("type", 91).put("status", 3)
            .put("utime", 1_800_000_002_000L)
        val detail = JSONObject().put("code", 0).put("data", JSONObject().put("bill", bill))
        assertNull(WaterBillParser.fromDetail(detail, "unknown", ""))
    }

    @Test
    fun candidatesKeepCurrentDeviceWhenAnotherDeviceHasANewerBill() {
        fun bill(id: String, time: Long, device: String?) = JSONObject().put("id", id)
            .put("type", 91).put("status", 3).put("utime", time).apply {
                if (device != null) put("dev", JSONObject().put("id", device))
            }
        val records = JSONArray().put(bill("current", 1_800_000_001_000L, "a"))
            .put(bill("other", 1_800_000_003_000L, "b"))
            .put(bill("unknown", 1_800_000_002_000L, null))
        val response = JSONObject().put("code", 0).put("data", records)
        assertEquals(listOf("unknown", "current"), WaterBillParser.candidateIds(response, 0L, emptySet(), "a"))
        assertEquals(listOf("current"), WaterBillParser.candidateIds(response, 0L, setOf("bill:id:unknown"), "a"))
    }

}
