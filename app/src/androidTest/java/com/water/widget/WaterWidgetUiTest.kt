package com.water.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterWidgetUiTest {
    @Test fun widgetOffersSetupBeforeConfigurationAndKeepsConfiguredStartButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("widget_ui_regression_$name", mode)
        }
        val prefs = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        try {
            instrumentation.runOnMainSync {
                val unconfigured = WaterWidgetProvider.buildViews(context, 701, null)
                    .apply(context, FrameLayout(context))
                val setupButton = unconfigured.findViewById<Button>(R.id.btn_start)
                assertEquals("点击设置", setupButton.text.toString())
                assertTrue(setupButton.hasOnClickListeners())
                assertTrue(unconfigured.hasOnClickListeners())

                AccountStore.addOrUpdate(context, Account("widget-ui-account").apply {
                    appToken = "offline-widget-token"
                    selectDevice("offline-widget-device")
                })
                val configured = WaterWidgetProvider.buildViews(context, 701, null)
                    .apply(context, FrameLayout(context))
                val startButton = configured.findViewById<Button>(R.id.btn_start)
                assertEquals("启动设备", startButton.text.toString())
                assertTrue(startButton.hasOnClickListeners())
                assertEquals("点击启动当前设备",
                    configured.findViewById<TextView>(R.id.widget_status).text.toString())

                val density = context.resources.displayMetrics.density
                val width = (300 * density).toInt()
                val height = (180 * density).toInt()
                configured.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                configured.layout(0, 0, width, height)
                assertTrue("Status must fit within the minimum widget height",
                    configured.findViewById<View>(R.id.widget_status).bottom <= height - configured.paddingBottom)
            }
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
