package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterEstimateTest {
    @Test fun defaultRatioKeepsExistingWaterFormatting() {
        val estimate = WaterEstimate()
        assertEquals("0 ml", estimate.waterText(0))
        assertEquals("500 ml", estimate.waterText(160))
        assertEquals("780 ml", estimate.waterText(250))
        assertEquals("1.0 L", estimate.waterText(320))
    }

    @Test fun customRatioChangesNotificationWaterButNotCost() {
        val consumption = WaterConsumption(160, 1_800_000_000_000L, historyKey = "bill:id:ratio-test")
        val estimate = WaterEstimate(amountYuan = 0.20, volumeMl = 1000.0)
        assertEquals("800 ml", estimate.waterText(consumption.scoreEquivalent))
        assertEquals("本次消费 ¥0.16 · 水量约 800 ml", consumption.completionText(estimate))
        assertEquals("¥0.16", consumption.moneyText)
        assertEquals(160, consumption.scoreEquivalent)
    }

    @Test fun hiddenEstimateLeavesOnlyCostAndZeroBillStillHasNoWater() {
        val hidden = WaterEstimate(visible = false)
        assertEquals("本次消费 ¥0.16", WaterConsumption(160, 1L, historyKey = "bill:id:paid").completionText(hidden))
        val zero = WaterConsumption(0, 1L, paymentYuan = 0.0, historyKey = "bill:id:zero")
        assertEquals("本次未产生费用", zero.completionText(hidden))
        assertEquals("本次未产生费用", zero.completionText())
    }
}
