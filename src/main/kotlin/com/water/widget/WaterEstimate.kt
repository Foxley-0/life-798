package com.water.widget

import java.util.Locale
import kotlin.math.floor

data class WaterEstimate(
    val visible: Boolean = true,
    val amountYuan: Double = 0.16,
    val volumeMl: Double = 500.0
) {
    fun waterText(scoreEquivalent: Int): String {
        val millilitres = floor(scoreEquivalent / 1000.0 * (volumeMl / amountYuan))
        return if (millilitres >= 1000) {
            String.format(Locale.CHINA, "%.1f L", millilitres / 1000.0)
        } else {
            String.format(Locale.CHINA, "%.0f ml", floor((millilitres + 5) / 10) * 10)
        }
    }
}
