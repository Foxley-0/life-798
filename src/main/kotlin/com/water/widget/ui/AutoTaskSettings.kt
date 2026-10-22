package com.water.widget.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.water.widget.AutoTasks
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun AutoTaskSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(AutoTasks.enabled(context)) }
    var time by remember { mutableStateOf(AutoTasks.time(context)) }
    var permitted by remember { mutableStateOf(AutoTasks.permitted(context)) }
    val power = context.getSystemService(PowerManager::class.java)
    var batteryExempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var show by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val allowed = AutoTasks.permitted(context)
                if (allowed && !permitted) AutoTasks.schedule(context)
                permitted = allowed
                batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val timeText = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    SettingsRow(Icons.Default.CheckCircle, "自动积分任务",
        if (!enabled) "已关闭" else if (!permitted) "待授权" else "每天 $timeText",
        onClick = { show = true })
    if (showTime) AutoTaskTimeDialog(time, onConfirm = {
        time = it
        AutoTasks.setTime(context, it)
        showTime = false
    }, onDismiss = { showTime = false })
    if (show && !showTime) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("自动积分任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("每天运行全部账号的签到和积分任务。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动执行", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        AutoTasks.setEnabled(context, it)
                        if (it) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                OutlinedButton(enabled = enabled, onClick = {
                    showTime = true
                }, modifier = Modifier.fillMaxWidth()) { Text("每日执行时间  $timeText") }
                if (enabled && !permitted) {
                    Text("允许设置闹钟与提醒后，即可按时运行。")
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("允许定时执行") }
                }
                OutlinedButton(enabled = !batteryExempt, onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (batteryExempt) "已忽略电池优化" else "忽略电池优化")
                }
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("完成") } }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoTaskTimeDialog(time: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每日执行时间") },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimeInput(state = picker)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(picker.hour, picker.minute)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
