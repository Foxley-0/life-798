package com.water.widget.ui

import com.water.widget.WaterEstimate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class UsageHistoryLedgerTest {
    @Test
    fun `重复同步同一流水不会重复累计`() {
        val now = Calendar.getInstance()
        val response = response(now.timeInMillis, 160, "order-1")
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response))
        assertFalse(ledger.merge(response))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
    }

    @Test
    fun `服务端后续窗口缩短时本地历史仍保留`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val lastMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val ledger = UsageHistoryLedger()
        ledger.merge(response(lastMonth.timeInMillis, 320, "old-order"))
        ledger.merge(response(now.timeInMillis, 160, "new-order"))

        val restored = UsageHistoryLedger.fromJson(ledger.toJson())
        val state = restored.toUiState(now)

        assertEquals("¥0.16", state.monthCostText)
        assertEquals("¥0.48", state.yearCostText)
    }

    @Test
    fun `嵌套消费金额不被顶层零积分遮住`() {
        val now = Calendar.getInstance()
        val response = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("id", "nested-spend")
                        .put("type", 107)
                        .put("ctime", now.timeInMillis)
                        .put("score", 0)
                        .put("data", JSONObject().put("spend", 160))
                )
            )
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
        assertEquals("500 ml", ledger.toUiState(now).todayWaterText)
    }

    @Test
    fun `秒级时间戳计入当天`() {
        val now = Calendar.getInstance().apply { set(Calendar.MILLISECOND, 0) }
        val ledger = UsageHistoryLedger()

        assertTrue(ledger.merge(response(now.timeInMillis / 1000L, 160, "seconds-order")))
        assertEquals("¥0.16", ledger.toUiState(now).todayCostText)
    }

    @Test
    fun `账单与积分流水按订单去重且不跳过其他旧流水`() {
        val now = Calendar.getInstance()
        val json = response(now.timeInMillis, 160, "score-record")
        json.getJSONArray("data").getJSONObject(0).getJSONObject("data").put("sn", "shared-order")
        for (billFirst in listOf(true, false)) {
            val ledger = UsageHistoryLedger()
            if (billFirst) ledger.recordLocal("bill:id:shared-order", now.timeInMillis + 12, 160)
            ledger.merge(json)
            if (!billFirst) ledger.recordLocal("bill:id:shared-order", now.timeInMillis + 12, 160)
            val restored = UsageHistoryLedger.fromJson(ledger.toJson())
            assertFalse(restored.merge(json))
            assertFalse(restored.recordLocal("bill:id:shared-order", now.timeInMillis, 160))
            assertTrue(restored.merge(response(now.timeInMillis - 1000, 50, "another-order")))
            assertEquals("¥0.21", restored.toUiState(now).todayCostText)
        }
    }

    @Test
    fun `旧积分历史迁移后仅补入账单的额外钱包消费`() {
        val now = Calendar.getInstance()
        val ledger = UsageHistoryLedger()
        ledger.recordLocal("legacy-score", now.timeInMillis, 100)
        val json = response(now.timeInMillis, 100, "legacy-score")
        json.getJSONArray("data").getJSONObject(0).getJSONObject("data").put("sn", "mixed-order")
        val restored = UsageHistoryLedger.fromJson(ledger.toJson())
        restored.merge(json)
        restored.recordLocal("bill:id:mixed-order", now.timeInMillis, 180)
        assertEquals("¥0.18", restored.toUiState(now).todayCostText)
        assertFalse(restored.merge(json))
    }

    @Test
    fun `旧记录晚于账单迁移时也不会重复累计`() {
        val now = Calendar.getInstance()
        val ledger = UsageHistoryLedger()
        ledger.recordLocal("legacy-score", now.timeInMillis, 100)
        ledger.recordLocal("bill:id:mixed-order", now.timeInMillis, 180)
        val json = response(now.timeInMillis, 100, "legacy-score")
        json.getJSONArray("data").getJSONObject(0).getJSONObject("data").put("sn", "mixed-order")
        ledger.merge(json)
        assertEquals("¥0.18", ledger.toUiState(now).todayCostText)
        assertFalse(ledger.merge(json))
    }

    @Test
    fun `兑换抽奖不计用水且撤销旧误计仅一次`() {
        val now = Calendar.getInstance()
        val water = response(now.timeInMillis, 180, "water").getJSONArray("data").getJSONObject(0)
        val exchange = response(now.timeInMillis, 100, "exchange").getJSONArray("data").getJSONObject(0).put("type", 105)
        val lottery = response(now.timeInMillis, 50, "lottery").getJSONArray("data").getJSONObject(0).put("type", 106)
        val json = JSONObject().put("code", 0).put("data", JSONArray().put(water).put(exchange).put(lottery))
        val fresh = UsageHistoryLedger()
        fresh.merge(json)
        assertEquals("¥0.18", fresh.toUiState(now).todayCostText)
        val old = UsageHistoryLedger()
        old.recordLocal("water", now.timeInMillis, 180)
        old.recordLocal("exchange", now.timeInMillis, 100)
        old.recordLocal("lottery", now.timeInMillis, 50)
        val restored = UsageHistoryLedger.fromJson(old.toJson())
        assertTrue(restored.merge(json))
        assertEquals("¥0.18", restored.toUiState(now).todayCostText)
        val again = UsageHistoryLedger.fromJson(restored.toJson())
        assertFalse(again.merge(json))
        assertEquals("¥0.18", again.toUiState(now).todayCostText)
    }

    @Test
    fun `修改比例重算历史水量但不改金额或历史数据`() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.JULY, 15, 12, 0, 0) }
        val lastMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val ledger = UsageHistoryLedger()
        ledger.merge(response(now.timeInMillis, 160, "today"))
        ledger.merge(response(lastMonth.timeInMillis, 320, "last-month"))
        val persisted = ledger.toJson().toString()
        val restored = UsageHistoryLedger.fromJson(JSONObject(persisted))
        val state = restored.toUiState(now, WaterEstimate(amountYuan = 0.20, volumeMl = 1000.0))
        assertEquals("¥0.16", state.todayCostText)
        assertEquals("¥0.16", state.monthCostText)
        assertEquals("¥0.48", state.yearCostText)
        assertEquals("800 ml", state.todayWaterText)
        assertEquals("800 ml", state.monthWaterText)
        assertEquals("2.4 L", state.yearWaterText)
        assertEquals(persisted, restored.toJson().toString())
        assertFalse(restored.toUiState(now, WaterEstimate(visible = false)).showWaterEstimate)
        assertFalse(UsageHistoryLedger().toUiState(now, WaterEstimate(visible = false)).showWaterEstimate)
    }

    private fun response(time: Long, spend: Int, id: String) = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("ctime", time)
                    .put("type", 107)
                    .put("data", JSONObject().put("spend", spend))
            )
        )
}
