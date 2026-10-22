package com.water.widget.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.Account
import org.junit.Rule
import org.junit.Test
import java.io.File

class ScoreDashboardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun zeroCostBillShowsAmountAndNoChargeMessage() {
        val state = ScoreUiStateFactory.loading(Account("示例账户")).copy(
            scoreLoading = false,
            bills = listOf(WaterBillLogUiState("zero-bill", "09-21 17:50", "接水消费",
                WaterBillPaymentUiState("¥0.00", emptyList())))
        )
        compose.setContent {
            WaterTheme { ScoreDashboardScreen(listOf(state), false, {}, { _, _ -> }) }
        }
        compose.onNodeWithText("接水消费").performClick()
        compose.onNodeWithText("¥0.00").assertIsDisplayed()
        compose.onNodeWithText("查看支付明细").performClick()
        compose.onNodeWithText("本笔账单未产生扣费").assertIsDisplayed()
        compose.onNodeWithText("支付明细暂不可用，请重试").assertDoesNotExist()
    }

    @Test
    fun mixedPaymentCanBeExpandedAndScoreTabStillWorks() {
        val state = ScoreUiStateFactory.loading(Account("示例账户")).copy(
            scoreLoading = false, isReady = true, message = "最近 1 条积分记录",
            validScore = 920, validMoneyText = "≈0.92元",
            logs = listOf(ScoreLogUiState("09-21 17:34", "-80", "接水积分消费", false)),
            billMessage = "来自最近 20 笔账单，不含充值、兑换",
            bills = listOf(WaterBillLogUiState("bill-1", "09-21 17:34", "宿舍饮水机",
                WaterBillPaymentUiState("¥0.20", listOf(
                    PaymentPartUiState("钱包礼品金", "¥0.12"),
                    PaymentPartUiState("积分抵扣", "80 积分 · ¥0.08")
                ))))
        )
        var screenState by mutableStateOf(state.copy(bills = state.bills.map { it.copy(payment = null) }))
        compose.setContent {
            WaterTheme {
                ScoreDashboardScreen(listOf(screenState), false, {}, { _, _ ->
                    screenState = screenState.copy(bills = state.bills)
                })
            }
        }
        compose.onNodeWithText("接水消费").performClick()
        compose.onNodeWithText("¥0.20").assertIsDisplayed()
        compose.onNodeWithText("钱包礼品金").assertDoesNotExist()
        compose.onNodeWithText("查看支付明细").performClick()
        compose.onNodeWithText("钱包礼品金").assertIsDisplayed()
        compose.onNodeWithText("80 积分 · ¥0.08").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "consumption-screen.png")
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithText("积分明细").performClick()
        compose.onNodeWithText("接水积分消费").performScrollTo().assertIsDisplayed()
    }

    @Test fun refreshIndicatorStaysBelowTabs() = verifyRefreshPosition(1f, false)

    @Test fun refreshIndicatorFollowsLargerHeaderOnBillsTab() = verifyRefreshPosition(1.4f, true)

    private fun verifyRefreshPosition(fontScale: Float, bills: Boolean) {
        var refreshing by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WaterTheme { ScoreDashboardScreen(emptyList(), refreshing, {}, { _, _ -> }) }
            }
        }
        if (bills) compose.onNodeWithText("接水消费").performClick()
        compose.runOnIdle { refreshing = true }
        compose.waitForIdle()
        compose.onNodeWithText("正在刷新…").assertIsDisplayed()
        val tabsBottom = compose.onNodeWithText("接水消费").fetchSemanticsNode().boundsInRoot.bottom
        val indicatorTop = compose.onNodeWithText("正在刷新…").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Refresh indicator must sit below tabs", indicatorTop > tabsBottom)
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "refresh-layout-$fontScale.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
