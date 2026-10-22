package com.water.widget.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.water.widget.Account
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScoreDashboardStateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownPointsAreNotDisplayedAsZeroButConfirmedZeroIs() {
        var state by mutableStateOf(ScoreUiStateFactory.loading(Account("points-state")))
        compose.setContent {
            WaterTheme { ScoreDashboardScreen(listOf(state), false, {}, { _, _ -> }) }
        }
        compose.onAllNodesWithText("加载中…").assertCountEquals(2)
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithText("可用 · ≈0.00元").assertDoesNotExist()

        compose.runOnIdle { state = state.copy(scoreLoading = false, message = "积分查询失败") }
        compose.onAllNodesWithText("暂不可用").assertCountEquals(2)
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithText("可用 · ≈0.00元").assertDoesNotExist()

        compose.runOnIdle { state = state.copy(isReady = true, message = "暂无积分变动") }
        compose.onAllNodesWithText("0").assertCountEquals(2)
        compose.onNodeWithText("可用 · ≈0.00元").assertIsDisplayed()
    }

    @Test fun summaryOnlyTotalsAvailableAccountsAndBackInvokesCallback() {
        val ready = ScoreUiStateFactory.loading(Account("ready-account")).copy(
            scoreLoading = false, isReady = true, validScore = 120,
            totalScore = 120, validMoneyText = "≈0.12元", message = "暂无积分变动"
        )
        val loading = ScoreUiStateFactory.loading(Account("loading-account")).copy(
            isReady = true, validScore = 999
        )
        val unavailable = ScoreUiStateFactory.loading(Account("failed-account")).copy(
            scoreLoading = false, validScore = 500, message = "积分查询失败"
        )
        var returned = false
        compose.setContent {
            WaterTheme {
                ScoreDashboardScreen(listOf(ready, loading, unavailable), false, {}, { _, _ -> },
                    onBack = { returned = true })
            }
        }
        compose.onNodeWithText("已获取可用积分合计").assertIsDisplayed()
        compose.onNodeWithText("1/3 个账号已获取").assertIsDisplayed()
        compose.onAllNodesWithText("120").assertCountEquals(2)
        compose.onNodeWithText("999").assertDoesNotExist()
        compose.onNodeWithText("500").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(returned) }
    }
}
