package com.water.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.water.widget.ui.WaterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceAddLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLargeTextFormKeepsSaveReachableWithKeyboardOpen() {
        var saved = ""
        var imeVisible = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                WaterTheme {
                    imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        DeviceAddScreen(onSave = { saved = it }, onBack = {})
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performClick().performTextInput("offline-device")
        compose.waitUntil(5000) { imeVisible }
        compose.onNodeWithText("保存设备").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("offline-device", saved) }
    }
}
