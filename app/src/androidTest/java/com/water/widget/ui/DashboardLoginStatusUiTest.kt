package com.water.widget.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.water.widget.Account
import com.water.widget.AppThemeMode
import com.water.widget.TokenValidity
import org.junit.Rule
import org.junit.Test

class DashboardLoginStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeAndSwitcherShowWhichLoginNeedsVerification() {
        val current = Account("当前测试账号").apply { token = "main"; appToken = "app" }
        val other = Account("备用测试账号").apply { token = "other-main"; appToken = "other-app" }
        val state = DashboardUiState(
            summary = DashboardUiStateFactory.from(current, 2,
                appStatus = TokenValidity.EXPIRED, alipayStatus = TokenValidity.VALID),
            accounts = DashboardUiStateFactory.accountsFrom(listOf(current, other), current.phone) { account, app ->
                if (account === current && app) TokenValidity.EXPIRED else TokenValidity.VALID
            }
        )
        compose.setContent {
            WaterTheme {
                DashboardScreen(
                    state = state,
                    onLogin = {}, onAccounts = {},
                    themeMode = AppThemeMode.SYSTEM, onThemeModeChange = {},
                    onRunTasks = {}, onScores = {}, onWallet = {},
                    onSelectAccount = {}, onFetchDevices = {}, onRefreshHome = {},
                    onAddDevice = {}, onEditDevice = { _, _ -> }, onRemoveDevice = {},
                    onSelectDevice = {}, onStartDevice = {}
                )
            }
        }

        compose.onNodeWithText("用水登录需重新验证 · 积分登录有效").assertIsDisplayed()
        compose.onNodeWithText("切换账号").performClick()
        compose.onAllNodesWithText("用水登录需重新验证 · 积分登录有效").assertCountEquals(2)
        compose.onNodeWithText("用水登录有效 · 积分登录有效").assertIsDisplayed()
        compose.onAllNodesWithText("设备与积分登录均已完成").assertCountEquals(0)
    }
}
