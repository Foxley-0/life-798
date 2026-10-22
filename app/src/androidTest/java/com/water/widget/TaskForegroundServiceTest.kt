package com.water.widget

import android.content.ComponentName
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskForegroundServiceTest {
    @Test
    fun taskWakeLockIsReleasedWhenServiceIsDestroyed() {
        withTaskService { service, lock ->
            assertTrue(lock.isHeld)
            service.onDestroy()
            assertFalse(lock.isHeld)
        }
    }

    @Test
    fun cleanupAlsoWorksAfterWakeLockHasAlreadyBeenReleased() {
        withTaskService { service, lock ->
            lock.release()
            service.onDestroy()
            assertFalse(lock.isHeld)
        }
    }

    @Test
    fun appOnlyAccountPassesStartGateWithoutStartingRealService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val prefs = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("migrated_v3", true).commit()
            val consent = context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE)
            val previouslyConfirmed = TaskRiskConsent.isConfirmed(context)
            consent.edit().clear().commit()
            TaskRunRepository.cancel("测试初始化")
            var starts = 0
            val interceptedContext = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName {
                    assertEquals(TaskForegroundService.ACTION_RUN_TASKS, service.action)
                    starts++
                    return ComponentName(context, TaskForegroundService::class.java)
                }
            }
            try {
                AccountStore.addOrUpdate(context, Account("app-only").apply { appToken = "synthetic-app-token" })
                assertEquals(TaskForegroundService.StartResult.RISK_NOT_CONFIRMED, TaskForegroundService.start(interceptedContext))
                assertEquals(0, starts)
                TaskRiskConsent.confirm(context)
                assertTrue(TaskRiskConsent.isConfirmed(context))
                assertEquals(TaskForegroundService.StartResult.STARTED, TaskForegroundService.start(interceptedContext))
                assertEquals(1, starts)
                prefs.edit().clear().putBoolean("migrated_v3", true).commit()
                assertEquals(TaskForegroundService.StartResult.NO_ACCOUNTS, TaskForegroundService.start(interceptedContext))
                assertEquals(1, starts)
            } finally {
                consent.edit().putBoolean("confirmed", previouslyConfirmed).commit()
                prefs.edit().clear().putBoolean("migrated_v3", true).commit()
            }
        }
    }

    @Test
    fun absentPlatformDoesNotIssueARequest() {
        withTaskService { service, _ ->
            val load = TaskForegroundService::class.java.getDeclaredMethod(
                "loadPlatformTaskData", Account::class.java, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Function1::class.java
            ).apply { isAccessible = true }
            for (useApp in listOf(false, true)) {
                var called = false
                val callback: (JSONObject?) -> Unit = { assertEquals(null, it); called = true }
                load.invoke(service, Account("synthetic"), useApp, 0, callback)
                assertTrue(called)
            }
        }
    }

    // 仅注入模拟任务、拦截启动请求，不调用任何业务接口。
    private fun withTaskService(check: (TaskForegroundService, PowerManager.WakeLock) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val service = TaskForegroundService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
                invoke(service, instrumentation.targetContext)
            }
            service.onCreate()
            try {
                TaskForegroundService::class.java.getDeclaredMethod("keepCpuAwake").apply {
                    isAccessible = true
                    invoke(service)
                }
                val lock = TaskForegroundService::class.java.getDeclaredField("taskWakeLock").apply {
                    isAccessible = true
                }.get(service) as PowerManager.WakeLock
                check(service, lock)
            } finally {
                service.onDestroy()
            }
        }
    }
}
