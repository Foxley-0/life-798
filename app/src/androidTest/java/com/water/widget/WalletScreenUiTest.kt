package com.water.widget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.water.widget.ui.WaterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WalletScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rechargeAndExchangeStaySeparateAndKeepConfirmationDetails() {
        val wallet = RechargeWallet("offline-endpoint", "离线宿舍钱包", "offline-owner", 3.0)
        val product = RechargeProduct("offline-product", "充值6元", 6.0, null, "")
        var state by mutableStateOf(
            WalletUiState(
                accountName = "离线示例账号",
                wallets = listOf(wallet),
                selectedEndpointId = wallet.endpointId,
                selectedOwnerId = wallet.ownerId,
                products = listOf(product),
                selectedProductId = product.id,
                availableScore = 2000
            )
        )
        var rechargeConfirmations = 0
        var exchangeQuantity: Int? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                WaterTheme {
                    WalletScreen(
                        state = state,
                        onBack = {},
                        onOpenAccounts = {},
                        onRefresh = {},
                        onSelectWallet = {
                            state = state.copy(selectedEndpointId = it.endpointId, selectedOwnerId = it.ownerId)
                        },
                        onSelectProduct = { state = state.copy(selectedProductId = it.id) },
                        onRequestRecharge = { state = state.copy(showRechargeConfirmation = true) },
                        onDismissRecharge = { state = state.copy(showRechargeConfirmation = false) },
                        onConfirmRecharge = {
                            rechargeConfirmations++
                            state = state.copy(showRechargeConfirmation = false)
                        },
                        onRequestExchange = { state = state.copy(exchangeScore = it) },
                        onDismissExchange = { state = state.copy(exchangeScore = null) },
                        onConfirmExchange = {
                            exchangeQuantity = it
                            state = state.copy(exchangeScore = null)
                        }
                    )
                }
            }
        }

        compose.runOnIdle { state = state.copy(refreshing = true) }
        compose.onNodeWithText("正在刷新…").assertIsDisplayed()
        val headerBottom = compose.onNodeWithText("离线示例账号").fetchSemanticsNode().boundsInRoot.bottom
        val indicatorTop = compose.onNodeWithText("正在刷新…").fetchSemanticsNode().boundsInRoot.top
        assertTrue("大字体下刷新提示应位于标题行下方", indicatorTop > headerBottom)
        compose.runOnIdle { state = state.copy(refreshing = false) }

        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("充值  ¥6.00"))
        compose.onNodeWithText("充值  ¥6.00").assertIsDisplayed()
        compose.onNodeWithText("1000 积分兑 ¥1.00 / 份").assertDoesNotExist()
        compose.onNodeWithText("充值  ¥6.00").performClick()
        compose.onNodeWithText("确认充值").assertIsDisplayed()
        compose.onNodeWithText("钱包：离线宿舍钱包").assertIsDisplayed()
        compose.onNodeWithText("充值金额：¥6.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, rechargeConfirmations) }
        compose.onNodeWithText("确认并前往支付宝").performClick()
        compose.runOnIdle { assertEquals(1, rechargeConfirmations) }
        compose.onNodeWithText("确认充值").assertDoesNotExist()

        list.performScrollToNode(hasText("积分兑换"))
        compose.onNodeWithText("积分兑换").performClick()
        compose.onNodeWithText("选择充值金额").assertDoesNotExist()
        compose.onNodeWithText("充值  ¥6.00").assertDoesNotExist()
        list.performScrollToNode(hasText("1000 积分兑 ¥1.00 / 份"))
        compose.onNodeWithText("1000 积分兑 ¥1.00 / 份").performClick()
        compose.onNodeWithText("确认积分兑换").assertIsDisplayed()
        compose.onNode(hasText("兑换到：离线宿舍钱包") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("每份 1000 积分 = ¥1.00").assertIsDisplayed()
        compose.onNodeWithText("合计消耗 1000 积分，兑换 ¥1.00").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1000, state.exchangeScore)
            assertNull(exchangeQuantity)
        }
        compose.onNodeWithText("确认兑换").performClick()
        compose.runOnIdle { assertEquals(1, exchangeQuantity) }
        compose.onNodeWithText("确认积分兑换").assertDoesNotExist()
    }
}
