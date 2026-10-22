package com.water.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.water.widget.AppUpdateUiState
import com.water.widget.AutoUpdateCheck

@Composable
fun AutoUpdateSettingsDialog(policy: AutoUpdateCheck, onSelect: (AutoUpdateCheck) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自动检查更新") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("打开应用时，按所选频率检查新版本。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutoUpdateCheck.entries.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().selectable(selected = item == policy,
                    role = Role.RadioButton, onClick = { onSelect(item) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = item == policy, onClick = null)
                    Text(item.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
fun AppUpdateResultDialog(state: AppUpdateUiState, onOpenRelease: (String) -> Unit, onDismiss: () -> Unit) {
    val release = state.available
    if (release != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("发现新版本 v${release.version}") },
            text = { Text(release.notes.ifBlank { "前往发布页查看更新内容并下载安装包。" },
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { onOpenRelease(release.url); onDismiss() }) { Text("前往下载") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } })
    } else if (state.message != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("检查更新") }, text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
    }
}
