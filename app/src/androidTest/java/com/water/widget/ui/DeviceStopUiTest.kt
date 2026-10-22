package com.water.widget.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class DeviceStopUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun runningDeviceRemovalIsDisabledButBecomesAvailableAfterStopping() {
        var session by mutableStateOf<com.water.widget.WaterControlSession?>(
            com.water.widget.WaterControlSession(1, "test", "test-device"))
        var removed: String? = null
        val state = DashboardUiStateFactory.from(null, 0).copy(
            hasAccount = true, hasAppToken = true, hasDevices = true,
            devices = listOf(DeviceUiState("test-device", "测试饮水机")))
        compose.setContent {
            WaterTheme { DeviceCard(state, false, {}, {}, { _, _ -> }, { removed = it }, {}, {}, null, {}, session) }
        }
        compose.onNodeWithContentDescription("管理设备").performClick()
        compose.onNodeWithText("移除").assertIsNotEnabled()
        compose.onNodeWithText("请先停止接水，再移除设备").assertExists()
        compose.runOnIdle { session = null }
        compose.onNodeWithText("移除").assertIsEnabled().performClick()
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals("test-device", removed) }
    }

    @Test fun stopIsDirectAndUsesOneButton() {
        var stopped: String? = null
        var stopping by mutableStateOf<String?>(null)
        val state = DashboardUiStateFactory.from(null, 0).copy(
            hasAccount = true, hasAppToken = true, hasDevices = true,
            devices = listOf(DeviceUiState("test-device", "测试饮水机")))
        compose.setContent {
            WaterTheme {
                DeviceCard(state, false, {}, {}, { _, _ -> }, {}, {}, {}, stopping, {
                    stopped = it
                    stopping = it
                }, com.water.widget.WaterControlSession(1, "test", "test-device", false))
            }
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "device-stop-card.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithText("启动").assertDoesNotExist()
        val boundsBeforeStop = compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("停止").performClick()
        compose.onNodeWithText("确认停止").assertDoesNotExist()
        compose.runOnIdle { assertEquals("test-device", stopped) }
        compose.onNodeWithText("停止").assertIsNotEnabled()
        compose.onNodeWithText("停止中…").assertDoesNotExist()
        assertEquals(boundsBeforeStop, compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("启动").assertDoesNotExist()
    }

    @Test fun stopRequiresDeviceLogin() {
        val state = DashboardUiStateFactory.from(null, 0).copy(
            hasAccount = true, hasDevices = true,
            devices = listOf(DeviceUiState("test-device", "测试饮水机")))
        compose.setContent {
            WaterTheme { DeviceCard(state, false, {}, {}, { _, _ -> }, {}, {}, {}, null, {}) }
        }
        compose.onNodeWithText("启动").assertIsNotEnabled()
    }

    @Test fun buttonFollowsSessionAndFailureStaysInline() {
        var session by mutableStateOf<com.water.widget.WaterControlSession?>(null)
        val state = DashboardUiStateFactory.from(null, 0).copy(
            hasAccount = true, hasAppToken = true, hasDevices = true,
            devices = listOf(DeviceUiState("test-device", "测试饮水机")))
        compose.setContent {
            WaterTheme {
                DeviceCard(state, false, {}, {}, { _, _ -> }, {}, {}, {
                    session = com.water.widget.WaterControlSession(1, "test", it)
                }, null, {}, session, "网络连接失败，请重试")
            }
        }
        compose.onNodeWithText("启动").performClick()
        compose.onNodeWithText("启动").assertIsNotEnabled()
        compose.onNodeWithText("启动中…").assertDoesNotExist()
        compose.runOnIdle { session = session!!.copy(starting = false) }
        compose.onNodeWithText("停止").assertExists()
        compose.onNodeWithText("启动").assertDoesNotExist()
        compose.onNodeWithText("网络连接失败，请重试").assertExists()
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.runOnIdle { session = null }
        compose.onNodeWithText("启动").assertExists()
        compose.onNodeWithText("停止").assertDoesNotExist()
    }

    @Test fun stoppingOneDeviceDoesNotDisableOtherButtons() {
        var stopping by mutableStateOf<String?>(null)
        val state = DashboardUiStateFactory.from(null, 0).copy(
            hasAccount = true, hasAppToken = true, hasDevices = true,
            devices = listOf(DeviceUiState("first", "正在使用"), DeviceUiState("second", "其他设备")))
        compose.setContent {
            WaterTheme {
                DeviceCard(state, false, {}, {}, { _, _ -> }, {}, {}, {}, stopping,
                    { stopping = it }, com.water.widget.WaterControlSession(1, "test", "first", false))
            }
        }
        compose.onNodeWithText("启动").assertIsEnabled()
        val boundsBeforeStop = compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("停止").performClick()
        compose.onNodeWithText("停止").assertIsNotEnabled()
        compose.onNodeWithText("停止中…").assertDoesNotExist()
        assertEquals(boundsBeforeStop, compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("启动").assertIsEnabled()
    }
}
