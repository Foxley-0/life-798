package com.life798.mini.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.life798.mini.core.AppLog
import com.life798.mini.scan.SquareCaptureActivity

/**
 * 扫码页。
 *
 * 不再自己处理相机帧，改用 zxing-android-embedded 的 CaptureActivity：
 * 对焦、曝光、旋转、二值化全部由成熟库负责，识别率和稳定性远高于手写实现。
 *
 * 本页只做三件事：启动扫码、展示上一次的结果、允许重扫。
 */
@Composable
fun ScanScreen(
    state: UiState,
    onBack: () -> Unit,
    onDecoded: (String) -> Unit
) {
    var scanRound by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        AppLog.i("Scan", "扫码返回: ${contents?.take(100) ?: "(用户取消)"}")
        if (contents.isNullOrBlank()) {
            onBack()
        } else {
            onDecoded(contents)
        }
    }

    // 进入页面即拉起扫码；点「重新扫描」递增计数再次拉起
    LaunchedEffect(scanRound) {
        AppLog.i("Scan", "拉起扫码 round=$scanRound")
        launcher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("将设备二维码放入框内")
                setBeepEnabled(false)
                setOrientationLocked(true)
                setBarcodeImageEnabled(false)
                // 用正方形取景框，库默认的是条形码用的横向矩形
                setCaptureActivity(SquareCaptureActivity::class.java)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "扫码已结束", fontSize = 16.sp)

        state.message?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { scanRound++ },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("重新扫描")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("返回")
        }
    }
}
