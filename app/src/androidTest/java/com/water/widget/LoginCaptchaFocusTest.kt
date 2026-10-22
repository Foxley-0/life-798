package com.water.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.WindowInsets
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Exercises the real Activity/render/loading flow with HTTP replaced locally. */
class LoginCaptchaFocusTest {
    companion object {
        @Volatile private var response: () -> ByteArray = { throw IOException("Offline test") }

        @JvmStatic @BeforeClass fun interceptNetwork() {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "http" && protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getInputStream(): java.io.InputStream {
                            check(url.path.endsWith("/captcha/")) { "Unexpected offline request" }
                            return ByteArrayInputStream(response())
                        }
                    }
                }
            }
        }

        @JvmStatic @AfterClass fun blockRemainingNetwork() {
            response = { throw IOException("Offline test completed") }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<LoginActivity>()
    private val phone get() = compose.onNode(hasSetTextAction() and hasText("手机号"))
    private val graphCode get() = compose.onNode(hasText("图形验证码") and (hasSetTextAction() or isNotEnabled()))

    @Before fun fillSyntheticPhone() {
        phone.performTextInput("13800000000")
    }

    @Test fun refreshFocusesCaptchaAfterLoadingAndKeepsTheKeyboardAvailable() {
        repeat(2) {
            val requested = CountDownLatch(1)
            val release = CountDownLatch(1)
            val image = captchaImage()
            response = {
                requested.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                image
            }
            try {
                compose.onNodeWithContentDescription("刷新图形验证码").performScrollTo().performClick()
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                graphCode.assertIsNotEnabled()
            } finally {
                release.countDown()
            }
            compose.waitUntil(5000) { !isLoading() }
            graphCode.assertIsFocused()
            compose.waitUntil(5000) {
                compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "captcha-focus").apply { mkdirs() }
        File(directory, "captcha-keyboard.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun failedRefreshWithAnOldImageDoesNotFocusTheCaptchaField() {
        val image = captchaImage()
        response = { image }
        compose.onNodeWithContentDescription("刷新图形验证码").performScrollTo().performClick()
        compose.waitUntil(5000) { !isLoading() }
        graphCode.assertIsFocused()
        phone.performScrollTo().performClick()
        phone.assertIsFocused()

        response = { throw IOException("Synthetic captcha failure") }
        compose.onNodeWithContentDescription("刷新图形验证码").performScrollTo().performClick()
        compose.waitUntil(5000) { !isLoading() }
        graphCode.assertIsNotFocused()
    }

    private fun isLoading(): Boolean = compose.runOnIdle {
        LoginActivity::class.java.getDeclaredField("loading").apply { isAccessible = true }
            .getBoolean(compose.activity)
    }

    private fun captchaImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("2468", 20f, 47f, Paint().apply { color = Color.BLACK; textSize = 44f })
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
