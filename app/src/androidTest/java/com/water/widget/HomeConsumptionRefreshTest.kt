package com.water.widget

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.ui.UsageHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeConsumptionRefreshTest {
    @Test
    fun onlySettlementOfCurrentAccountRefreshesBalance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("migrated_v3", true).commit()
        context.getSharedPreferences("usage_history_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(ConfigActivity::class.java).use { scenario ->
            var initialGeneration = 0
            scenario.onActivity { activity ->
                // 无凭据账户确保测试不会请求真实业务接口。
                AccountStore.addOrUpdate(activity, Account("account-a"))
                initialGeneration = generation(activity)
                UsageHistoryStore.record(activity, "account-a", WaterConsumption(
                    spentScore = 0, occurredAt = System.currentTimeMillis(), paymentYuan = 0.20,
                    historyKey = "bill:id:history-test"
                ))
                UsageHistoryStore.notifyWaterSettled("account-b")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(initialGeneration, generation(activity))
                UsageHistoryStore.notifyWaterSettled("account-a")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(initialGeneration + 1, generation(activity))
            }
        }
    }

    private fun generation(activity: ConfigActivity): Int = ConfigActivity::class.java
        .getDeclaredField("scoreGeneration").apply { isAccessible = true }.getInt(activity)
}
