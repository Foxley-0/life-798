package com.water.widget

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TaskNotificationCompletionTest {
    @Test
    fun rapidEmptyTaskUpdatesEndWithCompletedNotification() {
        checkTerminalNotification(cancelled = false)
    }

    @Test
    fun rapidTaskUpdatesEndWithCancelledNotification() {
        checkTerminalNotification(cancelled = true)
    }

    private fun checkTerminalNotification(cancelled: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).close()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        var service: TaskForegroundService? = null
        try {
            instrumentation.runOnMainSync {
                service = TaskForegroundService().also {
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }.invoke(it, context)
                    it.onCreate()
                }
            }
            val update = TaskForegroundService::class.java.getDeclaredMethod(
                "updateNotification", TaskRunState::class.java
            ).apply { isAccessible = true }
            val running = TaskRunState(running = true, lanes = listOf(
                TaskLaneState(1, 1, accountCount = 4, currentTask = "正在收尾")
            ))
            // 已完成的多个账号快速返回，只触发本地通知，不调用业务接口。
            repeat(30) { index ->
                instrumentation.runOnMainSync { update.invoke(service, running.copy(totalGained = index)) }
                Thread.sleep(50)
            }
            val terminal = if (cancelled) TaskRunReducer.cancel(running, "测试停止")
                else TaskRunReducer.finish(running)
            instrumentation.runOnMainSync { update.invoke(service, terminal) }
            // 等待系统通知服务处理异步入队，而不是只检查 Builder 的内容。
            Thread.sleep(1500)
            val notifications = manager.activeNotifications
            assertEquals("应只保留一条结束通知", 1, notifications.size)
            assertEquals(AppNotifications.TASK_RESULT_ID, notifications.single().id)
            val notification = notifications.single().notification
            assertEquals(if (cancelled) "积分任务已停止" else "积分任务已完成",
                notification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        } finally {
            instrumentation.runOnMainSync { service?.onDestroy() }
            manager.cancelAll()
        }
    }
}
