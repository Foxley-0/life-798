package com.water.widget.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.water.widget.TokenValidity
import com.water.widget.AppThemeMode
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginAndAccountsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentLoginStatusesAndReloginActionsAreShown() {
        val account = AccountCardState("13800000000", "示例账户", true,
            "synthetic-mini", "synthetic-app", "暂无设备",
            TokenValidity.EXPIRED, TokenValidity.VALID)
        var relogin: Pair<String, Boolean>? = null
        var checks = 0
        compose.setContent {
            WaterTheme {
                AccountsScreen(listOf(account), {}, {}, {}, {}, { _, _ -> }, {},
                    onCheckLogin = { checks++ }, onRelogin = { phone, app -> relogin = phone to app }, onBack = {})
            }
        }
        compose.onNodeWithText("需重新验证").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("有效").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("账号详情").performScrollTo().performClick()
        compose.onNodeWithText("复制积分登录信息").assertDoesNotExist()
        compose.onNodeWithText("重新登录积分账号").performClick()
        compose.runOnIdle { assertEquals(account.phone to false, relogin) }
        compose.onNodeWithText("账号详情").performClick()
        compose.onNodeWithText("重新登录用水账号").performClick()
        compose.runOnIdle { assertEquals(account.phone to true, relogin) }
        compose.onNodeWithText("账号详情").performClick()
        compose.onNodeWithText("检查登录状态").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithText("完成").performClick()
    }

    @Test fun accountDetailsKeepManualActionsCollapsedAndWorkInBothThemesAtLargeFont() {
        val account = AccountCardState("13800000000", "离线示例账号", true,
            "synthetic-mini", "synthetic-app", "示例饮水机", TokenValidity.EXPIRED, TokenValidity.VALID)
        var mode by mutableStateOf(AppThemeMode.LIGHT)
        var revealed: Pair<String, Boolean>? = null
        var deleted: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WaterTheme(mode) {
                    AccountsScreen(listOf(account), {}, {}, {}, {}, { phone, app -> revealed = phone to app },
                        { deleted = it }, {}, { _, _ -> }, {})
                }
            }
        }
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { theme ->
            compose.runOnIdle { mode = theme }
            compose.onNodeWithText("账号详情").performScrollTo().performClick()
            compose.onNodeWithText("复制用水登录信息").assertDoesNotExist()
            compose.onAllNodesWithText("synthetic", substring = true).assertCountEquals(0)
            saveScreenshot("account-details-${theme.name.lowercase()}.png")
            compose.onNodeWithText("复制或手动填写登录信息").performScrollTo().performClick()
            compose.onNodeWithText("手动填写用水登录").performScrollTo().assertIsDisplayed()
            if (theme == AppThemeMode.DARK) saveScreenshot("account-details-dark-manual.png")
            compose.onNodeWithText("复制用水登录信息").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(account.phone to true, revealed) }
        }
        compose.onNodeWithText("账号详情").performClick()
        compose.onNodeWithText("复制用水登录信息").assertDoesNotExist()
        compose.onNodeWithText("删除账号").performClick()
        compose.runOnIdle { assertEquals(account.phone, deleted) }
    }

    @Test fun phoneIsReadableWhileImportedLoginInformationStaysMasked() {
        var saved: List<String>? = null
        compose.setContent {
            WaterTheme {
                TextEntryDialog("手动添加登录信息", "离线测试",
                    listOf("手机号" to "", "积分登录" to "", "用水登录" to ""),
                    sensitiveFields = setOf(1, 2), onDismiss = {}, onConfirm = { saved = it })
            }
        }
        compose.onNodeWithText("手机号").performTextInput("13800000000")
        compose.onNodeWithText("13800000000").assertIsDisplayed()
        compose.onNodeWithText("手机号").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onNodeWithText("用水登录").performTextInput("synthetic-app")
        compose.onNodeWithText("用水登录").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 3000)
        val input = compose.onNodeWithText("用水登录").fetchSemanticsNode()
        assertTrue("输入框应在键盘上方完整显示", input.boundsInRoot.height >= input.layoutInfo.coordinates.size.height - 1)
        File(instrumentation.targetContext.getExternalFilesDir("author-review"), "login-import-device.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("确认").assertIsDisplayed()
        val coordinates = compose.onNodeWithText("确认").fetchSemanticsNode().layoutInfo.coordinates
        val center = compose.runOnIdle {
            coordinates.localToScreen(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        }
        val now = android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(now, now, action, center.x, center.y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitUntil(5000) { saved != null }
        compose.runOnIdle { assertEquals(listOf("13800000000", "", "synthetic-app"), saved) }
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("author-review"), name).outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun loadingLocksPlatformsAndInputsButAllowsBack() {
        val phone = "13800000000"
        val graphCode = "1234"
        val smsCode = "654321"
        val state = LoginUiStateFactory.from(LoginPlatform.APP, true, true, phone, graphCode, smsCode)
        var edits = 0
        var requests = 0
        var backs = 0
        compose.setContent {
            WaterTheme {
                LoginScreen(
                    state = state,
                    phone = phone,
                    graphCode = graphCode,
                    smsCode = smsCode,
                    captchaBitmap = null,
                    loading = true,
                    onPlatformChange = { edits++ },
                    onPhoneChange = { edits++ },
                    onGraphCodeChange = { edits++ },
                    onSmsCodeChange = { edits++ },
                    onLoadCaptcha = { requests++ },
                    onSendSms = { requests++ },
                    onLogin = { requests++ },
                    onBack = { backs++ }
                )
            }
        }
        LoginPlatform.entries.forEach { platform ->
            compose.onNode(hasText(platform.title) and hasClickAction())
                .assertIsNotEnabled().performTouchInput { click() }
        }
        listOf(phone, graphCode, smsCode).forEach { value ->
            compose.onNodeWithText(value).performScrollTo()
                .assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithContentDescription("刷新图形验证码").performScrollTo()
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("发送短信验证码").performScrollTo()
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("处理中…").performScrollTo()
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("返回").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, edits)
            assertEquals(0, requests)
            assertEquals(1, backs)
        }
    }

    @Test
    fun accountShowsLoginStatusAndLongDeviceRowRemainsOperableAtLargeFont() {
        val account = AccountCardState(
            phone = "13800000000",
            title = "示例账户",
            current = false,
            alipayToken = "ALIP_LOCAL_TEST_TAIL",
            appToken = "",
            deviceSummary = "宿舍一号饮水机 · 教学楼二层饮水机 · 图书馆三层饮水机"
        )
        var selected: String? = null
        var backs = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                WaterTheme {
                    AccountsScreen(
                        accounts = listOf(account),
                        onSmsLogin = {},
                        onAddToken = {},
                        onSetCurrent = { selected = it },
                        onSetAppToken = {},
                        onRevealToken = { _, _ -> },
                        onDelete = {},
                        onCheckLogin = {},
                        onRelogin = { _, _ -> },
                        onBack = { backs++ }
                    )
                }
            }
        }
        compose.onNodeWithText("未检测").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(account.alipayToken, substring = true).assertDoesNotExist()
        compose.onNodeWithText("ALIP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("TAIL", substring = true).assertDoesNotExist()
        compose.onNodeWithText(account.deviceSummary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("设为当前").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("返回").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(account.phone, selected)
            assertEquals(1, backs)
        }
    }
}
