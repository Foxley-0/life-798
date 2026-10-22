package com.water.widget.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.AppThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AfdianSupportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun supportUsesAfdianPosterAndSeparateProfileAndSponsorLinks() {
        val opened = mutableListOf<String>()
        compose.setContent {
            WaterTheme(mode = AppThemeMode.LIGHT) {
                DashboardScreen(
                    state = DashboardUiState(),
                    onLogin = {}, onAccounts = {},
                    themeMode = AppThemeMode.LIGHT, onThemeModeChange = {},
                    onRunTasks = {}, onScores = {}, onWallet = {},
                    onSelectAccount = {}, onFetchDevices = {}, onRefreshHome = {},
                    onAddDevice = {}, onEditDevice = { _, _ -> }, onRemoveDevice = {},
                    onSelectDevice = {}, onStartDevice = {},
                    onOpenSupportLink = { opened += it }
                )
            }
        }
        compose.onNodeWithText("我的").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("支持开发者").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "afdian-support.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("爱发电赞助二维码海报").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("微信赞赏").assertDoesNotExist()
        compose.onNodeWithText("支付宝赞赏").assertDoesNotExist()

        compose.onNodeWithText("查看爱发电主页").performScrollTo().performClick()
        compose.onNodeWithText("前往赞助").performClick()
        compose.runOnIdle {
            assertEquals(listOf(
                "https://afdian.com/a/liaoruo",
                "https://afdian.com/order/create?user_id=752a8cca967311ee8f0652540025c377&remark=&affiliate_code="
            ), opened)
        }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("爱发电赞助二维码海报").assertDoesNotExist()
    }
}
