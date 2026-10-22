package com.water.widget.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.Account
import com.water.widget.AccountStore
import com.water.widget.AppThemeMode
import com.water.widget.WaterConsumption
import com.water.widget.WaterEstimate
import com.water.widget.WaterUsageSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class WaterUsageSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = ViewModelStore()
    private lateinit var original: WaterEstimate

    @Before fun prepare() {
        original = WaterUsageSettings.read(context)
        WaterUsageSettings.save(context, WaterEstimate())
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { store.clear() }
        WaterUsageSettings.save(context, original)
    }

    @Test fun savedRatioImmediatelyRecalculatesHistoryAndHideRemovesWaterFromBothTabs() {
        context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("migrated_v3", true).commit()
        context.getSharedPreferences("usage_history_v1", Context.MODE_PRIVATE).edit().clear().commit()
        // 无登录凭据，不创建 Activity 的业务刷新，也不启动接水服务。
        AccountStore.addOrUpdate(context, Account("water-settings-offline"))
        UsageHistoryStore.record(context, "water-settings-offline", WaterConsumption(
            160, System.currentTimeMillis(), historyKey = "bill:id:settings-test"))
        lateinit var model: DashboardViewModel
        instrumentation.runOnMainSync {
            model = DashboardViewModel(context.applicationContext as Application)
            store.put("dashboard", model)
        }
        compose.setContent {
            val state by model.uiState.collectAsState()
            WaterTheme(AppThemeMode.LIGHT) {
                DashboardScreen(state, {}, {}, AppThemeMode.LIGHT, {}, {}, {}, {}, {}, {}, {}, {},
                    { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("500 ml").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("我的") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("预估水量设置").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("金额（元）").performTextReplacement("0.20")
        compose.onNodeWithText("对应水量（ml）").performTextReplacement("1000")
        saveScreenshot("water-settings-light.png")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { model.uiState.value.summary.usage.todayWaterText == "800 ml" }
        assertEquals(WaterEstimate(amountYuan = 0.20, volumeMl = 1000.0), WaterUsageSettings.read(context))
        assertEquals("¥0.16", model.uiState.value.summary.usage.todayCostText)
        compose.onNodeWithText("预估水量设置").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { !model.uiState.value.summary.usage.showWaterEstimate }
        compose.onAllNodesWithText("800 ml").assertCountEquals(0)
        compose.onNodeWithText("已隐藏预估水量").assertExists()
        compose.onNode(hasText("控制") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("今日估算水量").assertDoesNotExist()
        compose.onNodeWithText("今日用水花费").performScrollTo().assertIsDisplayed()
        assertEquals("¥0.16", UsageHistoryStore.mergeAndRead(context, "water-settings-offline", null).todayCostText)
    }

    @Test fun cancelAlsoDiscardsToggleAndRestoredDefaultRatio() {
        val saved = WaterEstimate(amountYuan = 0.25, volumeMl = 750.0)
        WaterUsageSettings.save(context, saved)
        compose.setContent { WaterTheme(AppThemeMode.DARK) { WaterUsageSettingsRow() } }
        compose.onNodeWithText("预估水量设置").performClick()
        compose.onNodeWithText("恢复默认比例").performScrollTo().performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(saved, WaterUsageSettings.read(context))
        compose.onNodeWithText("预估水量设置").performClick()
        compose.onNodeWithText("金额（元）").assertTextContains("0.25")
        compose.onNodeWithText("对应水量（ml）").assertTextContains("750")
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("恢复默认比例").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(WaterEstimate(), WaterUsageSettings.read(context))
    }

    @Test fun invalidInputCannotSaveAndLargeTextDialogCanScroll() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WaterTheme(AppThemeMode.DARK) { WaterUsageSettingsRow() }
            }
        }
        compose.onNodeWithText("预估水量设置").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("0")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("请输入大于 0 的有效金额").assertExists()
        compose.onNodeWithText("金额（元）").performTextReplacement("0.16")
        compose.onNodeWithText("对应水量（ml）").performTextReplacement("abc")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("金额（元）").performTextReplacement("0.20")
        compose.onNodeWithText("对应水量（ml）").performTextReplacement("1000")
        compose.onNodeWithText("恢复默认比例").performScrollTo().assertIsDisplayed()
        saveScreenshot("water-settings-dark-large.png")
        compose.onNodeWithText("保存").assertIsEnabled().assertIsDisplayed().performClick()
        compose.onNodeWithText("金额（元）").assertDoesNotExist()
        assertEquals(WaterEstimate(amountYuan = 0.20, volumeMl = 1000.0), WaterUsageSettings.read(context))
    }

    private fun saveScreenshot(name: String) {
        val output = File(context.getExternalFilesDir("water-settings-review"), name)
        output.outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(output.parentFile, "device-$name").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
