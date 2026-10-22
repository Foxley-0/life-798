package com.water.widget.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.Account
import com.water.widget.AppThemeMode
import com.water.widget.TaskRiskConsent
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TaskRiskConsentUiTest {
    @get:Rule val compose = createComposeRule()
    private var previouslyConfirmed = false

    @Before fun requireFreshConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previouslyConfirmed = TaskRiskConsent.isConfirmed(context)
        context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE).edit()
            .putBoolean("confirmed", previouslyConfirmed).commit()
    }

    @Test fun firstTaskVisitRequiresExplicitConfirmationAndRemembersIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val tasks = TaskUiStateFactory.from(
            listOf(Account("offline-risk-account").apply { appToken = "synthetic" }), false, 0, emptyList()
        )
        var composition by mutableIntStateOf(0)
        var runs = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                key(composition) {
                    WaterTheme(mode = AppThemeMode.DARK) {
                        DashboardScreen(
                            state = DashboardUiState(tasks = tasks),
                            onLogin = {}, onAccounts = {},
                            themeMode = AppThemeMode.DARK, onThemeModeChange = {},
                            onRunTasks = { runs++ }, onScores = {}, onWallet = {},
                            onSelectAccount = {}, onFetchDevices = {}, onRefreshHome = {},
                            onAddDevice = {}, onEditDevice = { _, _ -> }, onRemoveDevice = {},
                            onSelectDevice = {}, onStartDevice = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("积分任务风险提示").assertDoesNotExist()
        compose.onNodeWithText("我的").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("积分任务风险提示").assertDoesNotExist()

        compose.onNodeWithText("任务").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("积分任务风险提示").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("积分任务风险提示").assertIsDisplayed()

        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                8f, instrumentation.targetContext.resources.displayMetrics.heightPixels / 2f, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        compose.onNodeWithText("积分任务风险提示").assertIsDisplayed()
        compose.onNodeWithText("免责声明").performScrollTo().assertIsDisplayed()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "task-risk-warning.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("我已阅读并确认").assertIsDisplayed().performClick()
        compose.onNodeWithText("积分任务风险提示").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(TaskRiskConsent.isConfirmed(instrumentation.targetContext))
            assertEquals(0, runs)
            composition++
        }

        compose.onNodeWithText("任务").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("积分任务风险提示").assertDoesNotExist()
        compose.onNodeWithText("运行今日任务").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, runs) }
    }
}
