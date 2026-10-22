package com.water.widget.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.AutoTasks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import java.time.LocalTime
import com.water.widget.AppThemeMode
import org.junit.Rule
import org.junit.Test

class AutoTaskSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timeInputConfirmsIndividualMinutes() {
        var selected: LocalTime? = null
        compose.setContent { WaterTheme(mode = AppThemeMode.LIGHT) {
            AutoTaskTimeDialog(LocalTime.of(9, 0), { selected = it }, {})
        } }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("18")
        fields[1].performTextReplacement("27")
        captureTimeDialog("auto-task-time-light.png")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(LocalTime.of(18, 27), selected) }
    }

    @Test fun timeInputCancelDoesNotSaveChangesInDarkTheme() {
        var selected = LocalTime.of(23, 59)
        var dismissed = false
        compose.setContent { WaterTheme(mode = AppThemeMode.DARK) {
            AutoTaskTimeDialog(selected, { selected = it }, { dismissed = true })
        } }
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("01")
        captureTimeDialog("auto-task-time-dark.png")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(LocalTime.of(23, 59), selected); assertEquals(true, dismissed) }
    }

    private fun captureTimeDialog(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun defaultOffShowsTimeAndBatterySettingsWithoutStartingTasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AutoTasks.setEnabled(context, false)
        compose.setContent { WaterTheme { AutoTaskSettings() } }
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText("自动积分任务").performClick()
        compose.onNodeWithText("每天运行全部账号的签到和积分任务。").assertIsDisplayed()
        compose.onNodeWithText("每日执行时间  09:00").assertIsNotEnabled()
        compose.onNodeWithText("忽略电池优化", substring = true).assertIsDisplayed()
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "auto-task-settings.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertFalse(AutoTasks.enabled(context)) }
    }
}
