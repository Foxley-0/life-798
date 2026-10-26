package com.life798.mini.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.life798.mini.core.AppLog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扫码页（全屏）。
 *
 * 进入时白底 + 「点击启动扫描」，相机不启动。
 * 点击后相机开始预览，白底淡出，露出全屏画面（不画取景框，整屏参与识别）。
 * 扫到后暂停预览，解析成功则弹确认框，确认后启动设备。
 */
@Composable
fun ScanScreen(
    state: UiState,
    onDecoded: (String) -> Boolean,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var showCover by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            // 不画取景框，整屏都是识别区域
            viewFinder.visibility = View.GONE
            setStatusText("")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        AppLog.i("Scan", "相机权限: ${if (granted) "已授予" else "被拒绝"}")
        if (granted) {
            barcodeView.resume()
            showCover = false
        }
    }

    fun startScan() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        AppLog.i("Scan", "开始扫描")
        barcodeView.resume()
        showCover = false
    }

    DisposableEffect(Unit) {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text ?: return
                mainHandler.post {
                    barcodeView.pause()
                    AppLog.i("Scan", "识别到: ${text.take(100)}")
                    val ok = onDecoded(text)
                    if (ok) {
                        showConfirm = true
                    } else {
                        // 解析失败（非本平台/无法识别），退回白底让用户重扫
                        showCover = true
                    }
                }
            }
        })
        onDispose {
            barcodeView.pause()
            AppLog.i("Scan", "离开扫码页")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { barcodeView },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showCover,
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    state.message?.let { msg ->
                        Text(
                            text = msg,
                            color = MiuixTheme.colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                    }

                    Button(
                        onClick = { startScan() },
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("点击启动扫描", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showConfirm) {
        OverlayDialog(
            show = showConfirm,
            title = "启动设备",
            summary = "设备编号：${state.deviceId}\n\n启动后请在设备上使用，用完在设备上结算。",
            onDismissRequest = {
                showConfirm = false
                showCover = true
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = {
                        showConfirm = false
                        showCover = true
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        showConfirm = false
                        onStart()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认启动")
                }
            }
        }
    }
}
