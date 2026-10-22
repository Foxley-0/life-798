package com.water.widget

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class AutoTasksTest {
    @Test fun realAlarmDeliveryReportsMissingAccountAndSchedulesNextRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val accounts = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        accounts.edit().clear().putBoolean("migrated_v3", true).commit()
        val consent = context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE)
        val previouslyConfirmed = TaskRiskConsent.isConfirmed(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.cancel(2402)
        TaskRunRepository.cancel("测试初始化")
        try {
            TaskRiskConsent.confirm(context)
            AutoTasks.setEnabled(context, true)
            val pending = android.app.PendingIntent.getBroadcast(context, 2401,
                Intent(context, AutoTaskReceiver::class.java).setAction(AutoTasks.ACTION_RUN),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(pending)
            context.getSystemService(android.app.AlarmManager::class.java).setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000, pending)
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (manager.activeNotifications.none { it.id == 2402 } && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(200)
            }
            val result = manager.activeNotifications.single { it.id == 2402 }
            assertEquals("自动积分任务未启动", result.notification.extras.getString("android.title"))
            assertEquals("请先登录账号，再运行积分任务", result.notification.extras.getString("android.text"))
            assertFalse(TaskRunRepository.state.value.running)
            assertTrue(shell("dumpsys alarm").contains("tag=*walarm*:${AutoTasks.ACTION_RUN}"))
        } finally {
            AutoTasks.setEnabled(context, false)
            context.getSharedPreferences("auto_tasks", Context.MODE_PRIVATE).edit().clear().commit()
            manager.cancel(2402)
            consent.edit().putBoolean("confirmed", previouslyConfirmed).commit()
        }
    }

    @Test fun settingsScheduleOneAlarmAndOnlyAlarmDeliveryStartsTasks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        val prefs = context.getSharedPreferences("auto_tasks", Context.MODE_PRIVATE)
        val accounts = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        val consent = context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE)
        val previouslyConfirmed = TaskRiskConsent.isConfirmed(context)
        prefs.edit().clear().commit()
        accounts.edit().clear().putBoolean("migrated_v3", true).commit()
        var starts = 0
        val intercepted = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName {
                assertEquals(TaskForegroundService.ACTION_RUN_TASKS, service.action)
                starts++
                return ComponentName(context, TaskForegroundService::class.java)
            }
        }
        try {
            TaskRiskConsent.confirm(context)
            assertFalse(AutoTasks.enabled(context))
            assertEquals(LocalTime.of(9, 0), AutoTasks.time(context))
            assertTrue(AutoTasks.permitted(context))
            AutoTasks.setTime(context, LocalTime.now().plusHours(2).withSecond(0).withNano(0))
            AutoTasks.setEnabled(context, true)
            AutoTasks.schedule(context)
            assertTrue(shell("dumpsys alarm").contains("*walarm*:${AutoTasks.ACTION_RUN}"))
            instrumentation.runOnMainSync {
                TaskRunRepository.cancel("测试初始化")
                AccountStore.addOrUpdate(context, Account("auto-test").apply { appToken = "synthetic-token" })
                AutoTaskReceiver().onReceive(intercepted, Intent(Intent.ACTION_BOOT_COMPLETED))
                assertEquals(0, starts)
                AutoTaskReceiver().onReceive(intercepted, Intent(AutoTasks.ACTION_RUN))
                assertEquals(1, starts)
                AutoTasks.setEnabled(context, false)
                AutoTaskReceiver().onReceive(intercepted, Intent(AutoTasks.ACTION_RUN))
                assertEquals(1, starts)
            }
            // 只检查待触发列表，不把系统的历史触发统计当成残留闹钟。
            val pending = shell("dumpsys alarm").substringBefore("Past-due non-wakeup alarms")
                .substringBefore("Alarm Stats:")
            assertFalse(pending.contains("tag=*walarm*:${AutoTasks.ACTION_RUN}"))
        } finally {
            AutoTasks.setEnabled(context, false)
            prefs.edit().clear().commit()
            accounts.edit().clear().putBoolean("migrated_v3", true).commit()
            consent.edit().putBoolean("confirmed", previouslyConfirmed).commit()
        }
    }
}
