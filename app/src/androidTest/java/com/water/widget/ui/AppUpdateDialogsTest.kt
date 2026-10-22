package com.water.widget.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.water.widget.AppRelease
import com.water.widget.AppUpdateUiState
import com.water.widget.AutoUpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun userCanDisableAutomaticChecks() {
        var policy by mutableStateOf(AutoUpdateCheck.DAILY)
        var dismissed = false
        compose.setContent {
            WaterTheme { AutoUpdateSettingsDialog(policy, { policy = it }, { dismissed = true }) }
        }
        compose.onNodeWithText("打开应用时，按所选频率检查新版本。").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(AutoUpdateCheck.OFF, policy) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun releaseDialogShowsNotesAndOpensSelectedRelease() {
        val url = "https://github.com/nocookies111/life-798/releases/tag/v5.4.4"
        var opened: String? = null
        var dismissed = false
        compose.setContent {
            WaterTheme { AppUpdateResultDialog(AppUpdateUiState(available = AppRelease("5.4.4", "修复接水通知", url)),
                { opened = it }, { dismissed = true }) }
        }
        compose.onNodeWithText("修复接水通知").assertIsDisplayed()
        compose.onNodeWithText("前往下载").performClick()
        compose.runOnIdle { assertEquals(url, opened); assertTrue(dismissed) }
    }
}
