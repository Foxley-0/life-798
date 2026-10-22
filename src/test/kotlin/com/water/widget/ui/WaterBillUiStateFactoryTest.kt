package com.water.widget.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WaterBillUiStateFactoryTest {
    @Test
    fun mixedPaymentShowsWalletAndPointsWithoutDoubleCounting() {
        val result = WaterBillUiStateFactory.payment(detail(0.12, 0.08, 0.12), "water-1")
        assertEquals("¥0.20", result.totalText)
        assertEquals(listOf(PaymentPartUiState("钱包礼品金", "¥0.12"), PaymentPartUiState("积分抵扣", "80 积分 · ¥0.08")), result.parts)
    }

    @Test
    fun pointsOnlyAndWalletOnlyRemainSeparate() {
        val points = WaterBillUiStateFactory.payment(detail(0.0, 0.16, 0.0), "water-1")
        assertEquals(listOf(PaymentPartUiState("积分抵扣", "160 积分 · ¥0.16")), points.parts)
        val wallet = WaterBillUiStateFactory.payment(detail(0.2, 0.0, 0.2), "water-1")
        assertEquals(listOf(PaymentPartUiState("钱包礼品金", "¥0.20")), wallet.parts)
    }

    @Test
    fun unspecifiedPaymentChannelIsNotInventedAsWallet() {
        val response = detail(0.2, 0.0, 0.0)
        response.getJSONObject("data").getJSONObject("bill").remove("mk")
        assertEquals("金额支付（渠道未标明）", WaterBillUiStateFactory.payment(response, "water-1").parts.single().label)
    }

    @Test
    fun listsOnlyCompletedWaterBillsAndKeepsZeroPaymentCandidates() {
        val water = detail(0.0, 0.16, 0.0).getJSONObject("data").getJSONObject("bill")
        val exchange = JSONObject(water.toString()).put("id", "exchange").put("cata", 1)
        val pending = JSONObject(water.toString()).put("id", "pending").put("status", 2)
        val records = WaterBillUiStateFactory.records(JSONObject().put("code", 0).put("data", JSONArray().put(exchange).put(pending).put(water)))
        assertEquals(listOf("water-1"), records.map { it.id })
    }

    @Test
    fun rejectsFailedResponsesAndWrongBillDetails() {
        assertThrows(IllegalArgumentException::class.java) { WaterBillUiStateFactory.records(JSONObject().put("code", -99)) }
        assertThrows(IllegalArgumentException::class.java) { WaterBillUiStateFactory.payment(detail(0.12, 0.08, 0.12), "other-bill") }
    }

    @Test
    fun completedZeroCostBillDoesNotFailAsUnavailable() {
        val response = detail(0.0, 0.0, 0.0)
        response.getJSONObject("data").getJSONObject("bill").apply {
            remove("promo")
            put("mk", JSONObject())
        }
        val result = WaterBillUiStateFactory.payment(response, "water-1")
        assertEquals("¥0.00", result.totalText)
        assertTrue(result.parts.isEmpty())
    }

    @Test
    fun recentBillsTopUpMixedPaymentsAndIncludeWalletOnlyWithoutDuplicates() {
        val now = java.util.Calendar.getInstance()
        val mixed = detail(0.12, 0.08, 0.12)
        mixed.getJSONObject("data").getJSONObject("bill").put("utime", now.timeInMillis)
        val wallet = detail(0.30, 0.0, 0.30)
        wallet.getJSONObject("data").getJSONObject("bill").put("id", "wallet-only").put("utime", now.timeInMillis)
        val scores = JSONObject().put("code", 0).put("data", JSONArray().put(
            JSONObject().put("id", "score-1").put("type", 107).put("ctime", now.timeInMillis)
                .put("data", JSONObject().put("sn", "water-1").put("spend", 80))
        ))
        for (billFirst in listOf(true, false)) {
            val ledger = UsageHistoryLedger()
            if (!billFirst) ledger.merge(scores)
            listOf("water-1" to mixed, "wallet-only" to wallet).forEach { (id, json) ->
                val consumption = requireNotNull(WaterBillUiStateFactory.consumption(json, id))
                ledger.recordLocal(consumption.historyKey, consumption.occurredAt, consumption.scoreEquivalent)
            }
            ledger.merge(scores)
            val restored = UsageHistoryLedger.fromJson(ledger.toJson())
            val repeated = requireNotNull(WaterBillUiStateFactory.consumption(mixed, "water-1"))
            assertFalse(restored.recordLocal(repeated.historyKey, repeated.occurredAt, repeated.scoreEquivalent))
            assertEquals("¥0.50", restored.toUiState(now).todayCostText)
        }
    }

    @Test
    fun historicalZeroBillsAreIgnoredButInvalidDetailsAreNotTreatedAsZero() {
        assertNull(WaterBillUiStateFactory.consumption(detail(0.0, 0.0, 0.0), "water-1"))
        val exchange = detail(0.1, 0.0, 0.0)
        exchange.getJSONObject("data").getJSONObject("bill").put("cata", 1)
        assertThrows(IllegalArgumentException::class.java) { WaterBillUiStateFactory.consumption(exchange, "water-1") }
        assertThrows(IllegalArgumentException::class.java) { WaterBillUiStateFactory.consumption(detail(-0.1, 0.0, 0.0), "water-1") }
        val missingTime = detail(0.1, 0.0, 0.0)
        missingTime.getJSONObject("data").getJSONObject("bill").remove("utime")
        assertThrows(IllegalArgumentException::class.java) { WaterBillUiStateFactory.consumption(missingTime, "water-1") }
    }

    private fun detail(payment: Double, discount: Double, gift: Double) = JSONObject().put("code", 0).put("data", JSONObject().put("bill",
        JSONObject().put("id", "water-1").put("type", 91).put("cata", 6).put("status", 3)
            .put("payment", payment).put("discount", discount).put("utime", 1_800_000_022_000L)
            .put("promo", JSONObject().put("type", 4)).put("mk", JSONObject().put("olGift", gift))))
}
