package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.water.widget.Account
import com.water.widget.AppThemeMode
import com.water.widget.TaskRunResult
import java.io.File
import org.junit.Rule
import org.junit.Test

class TaskAccountCardsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accountsExpandIndependentlyAndFailuresStayInTheirOwnCard() {
        val logs = listOf("开始运行全部账号") + (1..6).map { "[账号甲] 甲记录 $it" } +
            (1..3).map { "[账号乙] 乙记录 $it" } + "[账号乙] 视频重试仍受限，跳过本任务剩余次数"
        val state = TaskUiStateFactory.from(listOf(Account("账号甲"), Account("账号乙")),
            false, 0, logs, result = TaskRunResult.PARTIAL)
        compose.setContent {
            WaterTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
                    TaskScreen(state, {}, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                }
            }
        }
        compose.onNodeWithText("整体运行").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("账号甲").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("账号乙").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("甲记录 1").assertDoesNotExist()
        compose.onNodeWithText("乙记录 1").assertDoesNotExist()
        compose.onNodeWithText("展开全部 6 条记录").performScrollTo().performClick()
        compose.onNodeWithText("甲记录 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("乙记录 1").assertDoesNotExist()
        compose.onNodeWithText("仅看失败").performScrollTo().performClick()
        compose.onNodeWithText("账号甲").assertDoesNotExist()
        compose.onNodeWithText("账号乙").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("视频再次尝试仍被拒绝，跳过本任务剩余次数").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("整体运行").assertDoesNotExist()
    }

    @Test fun longAccountLogsWrapAtLargeFontInBothThemes() {
        val accounts = listOf(Account("离线账号甲"), Account("离线账号乙"))
        val state = TaskUiStateFactory.from(accounts, false, 35, listOf(
            "[离线账号甲] 已核验新增 20 分，执行期间累计增加 20 分（包含期间其他渠道入账）",
            "[离线账号乙] [APP] 观看视频重试仍受限，跳过本任务剩余次数，继续其他任务"
        ), result = TaskRunResult.PARTIAL)
        var mode by mutableStateOf(AppThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WaterTheme(mode) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
                        TaskScreen(state, {}, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { theme ->
            compose.runOnIdle { mode = theme }
            compose.onNodeWithText("[APP] 观看视频再次尝试仍被拒绝，跳过本任务剩余次数，继续其他任务")
                .performScrollTo().assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "task-account-cards-${theme.name.lowercase()}.png")
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
