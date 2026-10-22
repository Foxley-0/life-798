package com.water.widget.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.Account
import com.water.widget.AppThemeMode
import com.water.widget.TaskLanePhase
import com.water.widget.TaskLaneState
import com.water.widget.TaskRunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiImprovementsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingRequestsAndUnknownResultsNeverClaimVerifiedZero() {
        val account = Account("离线示例账号").apply { appToken = "offline-token" }
        var state by mutableStateOf(TaskUiStateFactory.from(
            listOf(account), true, 0, listOf("请求成功，到账待核验"),
            listOf(TaskLaneState(1, 1, accountIndex = 1, accountCount = 1,
                accountLabel = "离线示例账号", currentTask = "核验累计积分变化", phase = TaskLanePhase.WAITING))
        ))
        compose.setContent { WaterTheme(AppThemeMode.DARK) { TaskScreen(state, {}) } }
        compose.onNodeWithContentDescription("等待确认").assertIsDisplayed()
        compose.onNodeWithContentDescription("成功").assertDoesNotExist()
        compose.onNodeWithText("正在确认积分到账").assertIsDisplayed()
        compose.onNodeWithText("暂未确认有积分增加").assertIsDisplayed()
        saveScreenshot("task-dark.png")

        compose.runOnIdle { state = state.copy(running = false, result = TaskRunResult.COMPLETED) }
        compose.onNodeWithText("已确认增加 0 积分").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(result = TaskRunResult.PARTIAL) }
        compose.onNodeWithText("已确认增加 0 积分").assertDoesNotExist()
        compose.onNodeWithText("暂未确认有积分增加").assertIsDisplayed()
    }

    @Test fun deviceControlsPrecedeStatisticsAndAccountPickerCanScroll() {
        val state = DashboardUiState(
            summary = DashboardSummaryUiState("离线示例账号", "设备与积分登录均已完成", "≈¥8.60",
                "钱包 ¥6.20 + 积分 2400（≈¥2.40）", true, true, true, 8,
                listOf(DeviceUiState("offline-device", "示例饮水机", true)),
                WaterUsageUiState("¥0.32", "¥12.80", "¥86.40", "1.0 L", "40.0 L", "270.0 L")),
            accounts = (1..8).map { DashboardAccountUiState("offline-$it", "示例账号 $it",
                "设备与积分登录均已完成", "", "", it == 1) }
        )
        var selected = ""
        var mode by mutableStateOf(AppThemeMode.LIGHT)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WaterTheme(mode) {
                    DashboardScreen(state, {}, {}, mode, { mode = it }, {}, {}, {},
                        { selected = it }, {}, {}, {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        val devices = compose.onNodeWithText("设备").fetchSemanticsNode().boundsInRoot
        val statistics = compose.onNodeWithText("今天概览").fetchSemanticsNode().boundsInRoot
        assertTrue(devices.top < statistics.top)
        saveScreenshot("home-light.png")
        compose.onNodeWithText("切换账号").performClick()
        compose.onNodeWithText("示例账号 8").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("offline-8", selected) }

        compose.onNode(hasText("我的") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("账户与资金").performScrollTo().assertIsDisplayed()
        saveScreenshot("mine-light.png")
        compose.runOnIdle { fontScale = 1.4f; mode = AppThemeMode.DARK }
        compose.waitForIdle()
        compose.onNodeWithText("外观设置").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("完成").assertHasClickAction()
        compose.onNodeWithText("完成").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        saveScreenshot("appearance-dark-large.png", compose.onNode(isDialog()))
        compose.onNodeWithText("完成").performClick()
    }

    @Test fun signedOutHomeOffersLoginWithoutEmptyStatistics() {
        var loginRequested = false
        compose.setContent {
            WaterTheme {
                DashboardScreen(DashboardUiState(), { loginRequested = true }, {}, AppThemeMode.SYSTEM,
                    {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("今天概览").assertDoesNotExist()
        compose.onNodeWithText("去登录").performClick()
        compose.runOnIdle { assertTrue(loginRequested) }
    }

    private fun saveScreenshot(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir("ui-review"), name)
        output.outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
