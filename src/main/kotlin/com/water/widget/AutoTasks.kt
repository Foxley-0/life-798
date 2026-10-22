package com.water.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalTime
import java.time.ZonedDateTime

object AutoTasks {
    const val ACTION_RUN = "com.water.widget.action.AUTO_TASKS"
    private fun preferences(context: Context) = context.getSharedPreferences("auto_tasks", Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
    fun time(context: Context): LocalTime = LocalTime.ofSecondOfDay(preferences(context).getInt("minute", 540) * 60L)
    fun permitted(context: Context) = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
        schedule(context)
    }

    fun setTime(context: Context, time: LocalTime) {
        preferences(context).edit().putInt("minute", time.hour * 60 + time.minute).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 2401, Intent(context, AutoTaskReceiver::class.java).setAction(ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.cancel(pending)
        if (!enabled(context) || !permitted(context)) return
        alarms.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextAutoTaskTime(ZonedDateTime.now(), time(context)).toInstant().toEpochMilli(),
            pending
        )
    }
}

class AutoTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoTasks.schedule(context)
        // 开机、升级和时间变更只恢复计划，不立即执行任务。
        if (intent.action != AutoTasks.ACTION_RUN || !AutoTasks.enabled(context)) return
        val result = TaskForegroundService.start(context)
        val message = when (result) {
            TaskForegroundService.StartResult.RISK_NOT_CONFIRMED -> "请先进入积分任务页，阅读风险提示并确认"
            TaskForegroundService.StartResult.NO_ACCOUNTS -> "请先登录账号，再运行积分任务"
            TaskForegroundService.StartResult.FAILED -> "请打开应用运行积分任务"
            else -> return
        }
        AppNotifications.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(2402, Notification.Builder(context, AppNotifications.CHANNEL_TASK_PROGRESS)
            .setSmallIcon(R.drawable.ic_water_drop)
            .setContentTitle("自动积分任务未启动")
            .setContentText(message)
            .setContentIntent(AppNotifications.openApp(context, false, 2402))
            .setAutoCancel(true)
            .build())
    }
}
