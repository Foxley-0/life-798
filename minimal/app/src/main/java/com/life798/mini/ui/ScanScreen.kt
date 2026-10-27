package com.life798.mini.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.life798.mini.core.AppLog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扫码页（全屏白底）。
 *
 * 三个容易踩的点都在这里处理：
 * 1. 白底铺到状态栏下，所以状态栏图标必须强制深色，否则深色主题下白图标看不见
 * 2. 相机由 AndroidView 的 factory 创建、onRelease 释放，不用 remember 长期持有
 * 3. 解码回调是异步的，页面销毁后要丢弃结果，否则会把用户从扫码页拽进启动页
 */
@Composable
fun ScanScreen(
    state: UiState,
    onEnter: () -> Unit,
    onDecoded: (String) -> Boolean,
    onScanned: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var showCover by remember { mutableStateOf(true) }
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // 进页清掉上一次的提示，否则旧的错误文案会一直挂在新一轮扫码上
    LaunchedEffect(Unit) { onEnter() }

    // 页面销毁后丢弃异步到达的扫码结果
    DisposableEffect(Unit) {
        alive = true
        onDispose { alive = false }
    }

    // 白底页：状态栏图标改成深色，离开时还原
    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = true
        onDispose {
            if (previous != null) controller?.isAppearanceLightStatusBars = previous
        }
    }

    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val currentOnScanned by rememberUpdatedState(onScanned)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
        AppLog.i("Scan", "相机权限: ${if (granted) "已授予" else "被拒绝"}")
        if (granted) {
            barcodeView?.resume()
            showCover = false
        }
    }

    fun startScan() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        AppLog.i("Scan", "开始扫描")
        barcodeView?.resume()
        showCover = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                DecoratedBarcodeView(ctx).apply {
                    viewFinder.visibility = View.GONE
                    setStatusText("")
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult?) {
                            val text = result?.text ?: return
                            mainHandler.post {
                                if (!alive) {
                                    AppLog.i("Scan", "页面已销毁，丢弃扫码结果")
                                    return@post
                                }
                                pause()
                                AppLog.i("Scan", "识别到: ${text.take(100)}")
                                if (currentOnDecoded(text)) {
                                    currentOnScanned()
                                } else {
                                    // 非本平台或无法识别，退回白底让用户重扫
                                    showCover = true
                                }
                            }
                        }
                    })
                    barcodeView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { v ->
                runCatching { v.pause() }
                if (barcodeView === v) barcodeView = null
                AppLog.i("Scan", "离开扫码页，相机已释放")
            }
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
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                    }

                    if (permissionDenied) {
                        Text(
                            text = "需要相机权限才能扫码",
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = "去系统设置开启",
                            onClick = { openAppSettings(context) }
                        )
                    } else {
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
    }
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        AppLog.w("Scan", "无法打开应用设置: ${it.message}")
    }
}
