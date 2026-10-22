package com.water.widget

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.ui.DashboardViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DeviceStopStartupTest {
    @Test fun stopCannotRaceWithPendingStartAndReservationCleanupResetsGuard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val field = WaterService::class.java.getDeclaredField("STARTING_RESERVATION_ID").apply { isAccessible = true }
        val starting = field.get(null) as AtomicLong
        val cleanup = WaterService::class.java.getDeclaredMethod("releaseReservation", java.lang.Long.TYPE).apply { isAccessible = true }
        instrumentation.runOnMainSync {
            val account = Account("stop-test-account").apply { appToken = "not-a-real-token" }
            AccountStore.addOrUpdate(app, account)
            AccountStore.setCurrent(app, account.phone)
            starting.set(123L)
            try {
                assertTrue(WaterService.isStarting())
                val model = DashboardViewModel(app)
                model.stopDevice("test-device")
                assertNull(model.uiState.value.stoppingDeviceId)
                assertEquals("设备正在启动，请稍后再停止", model.uiState.value.deviceStopMessage)
                cleanup.invoke(null, 122L)
                assertTrue(WaterService.isStarting())
                cleanup.invoke(null, 123L)
                assertFalse(WaterService.isStarting())
            } finally {
                starting.set(0L)
                app.getSharedPreferences("accounts_cfg", 0).edit().clear().commit()
            }
        }
    }
}
