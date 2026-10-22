package com.water.widget

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TaskNotificationRecoveryTest {
    @Test
    fun idleEntryRemovesRepostedProgressButKeepsResult() = withNotifications { context, manager ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { TaskRunRepository.cancel("测试空闲状态") }
        post(manager, context, AppNotifications.TASK_PROGRESS_ID)
        Thread.sleep(300)
        manager.cancel(AppNotifications.TASK_PROGRESS_ID)
        post(manager, context, AppNotifications.TASK_PROGRESS_ID)
        post(manager, context, AppNotifications.TASK_RESULT_ID)
        Thread.sleep(300)
        instrumentation.runOnMainSync { TaskForegroundService.clearIdleProgressNotification(context) }
        Thread.sleep(500)
        assertFalse(manager.activeNotifications.any { it.id == AppNotifications.TASK_PROGRESS_ID })
        assertTrue(manager.activeNotifications.any { it.id == AppNotifications.TASK_RESULT_ID })
    }

    @Test
    fun returningWhileRunningKeepsProgressNotification() = withNotifications { context, manager ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            TaskRunRepository.start(listOf(TaskLaneState(1, 1, accountCount = 1)))
        }
        post(manager, context, AppNotifications.TASK_PROGRESS_ID)
        Thread.sleep(300)
        instrumentation.runOnMainSync { TaskForegroundService.clearIdleProgressNotification(context) }
        Thread.sleep(500)
        assertTrue(manager.activeNotifications.any { it.id == AppNotifications.TASK_PROGRESS_ID })
        assertTrue(TaskRunRepository.state.value.running)
    }

    @Test
    fun interruptedServiceRemovesProgressAndStopsState() = withNotifications { context, manager ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var service: TaskForegroundService? = null
        try {
            instrumentation.runOnMainSync {
                service = TaskForegroundService().also {
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }.invoke(it, context)
                    it.onCreate()
                    TaskForegroundService::class.java.getDeclaredField("running")
                        .apply { isAccessible = true }.setBoolean(it, true)
                    TaskRunRepository.start(listOf(TaskLaneState(1, 1, accountCount = 1)))
                }
            }
            post(manager, context, AppNotifications.TASK_PROGRESS_ID)
            Thread.sleep(300)
            instrumentation.runOnMainSync { service!!.onDestroy(); service = null }
            Thread.sleep(500)
            assertFalse(TaskRunRepository.state.value.running)
            assertEquals(TaskRunResult.CANCELLED, TaskRunRepository.state.value.result)
            assertFalse(manager.activeNotifications.any { it.id == AppNotifications.TASK_PROGRESS_ID })
        } finally {
            instrumentation.runOnMainSync { service?.onDestroy() }
        }
    }

    private fun post(manager: NotificationManager, context: Context, id: Int) {
        manager.notify(id, Notification.Builder(context, AppNotifications.CHANNEL_TASK_PROGRESS)
            .setSmallIcon(R.drawable.ic_water_drop).setContentTitle("模拟任务通知")
            .setOngoing(id == AppNotifications.TASK_PROGRESS_ID).build())
    }

    // 仅模拟进程状态及系统通知，不启动真实任务或调用积分接口。
    private fun withNotifications(check: (Context, NotificationManager) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).close()
        AppNotifications.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        try {
            check(context, manager)
        } finally {
            instrumentation.runOnMainSync { TaskRunRepository.cancel("测试结束") }
            manager.cancelAll()
        }
    }
}
