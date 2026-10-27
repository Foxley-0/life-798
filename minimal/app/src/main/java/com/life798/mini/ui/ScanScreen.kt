package com.life798.mini.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.life798.mini.core.AppLog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 扫码页：CameraX + ML Kit，**分析整帧**。
 *
 * 和之前 ZXing 版本的区别：
 * - 预览（Preview）和解码（ImageAnalysis）是两条独立管线，分析帧就是完整相机帧，没有"解码区域"
 * - ML Kit 是 CNN 模型，对尺度/角度/模糊/光照更鲁棒，不再出现「码必须很小才扫得出」
 *
 * 三个必须做对的点（写错的话现象和 ZXing 版本一模一样）：
 * 1. ImageAnalysis 分辨率必须显式设成 1920×1080 —— 默认只有 640×480，小码照样扫不出
 * 2. 每帧必须 imageProxy.close()，漏一次管线就永久卡死
 * 3. 预览用 FIT_CENTER 而不是 FILL_CENTER，否则屏幕边缘「看得见却扫不到」
 */
@Composable
fun ScanScreen(
    state: UiState,
    onEnter: () -> Unit,
    onDecoded: (String) -> Boolean,
    onScanned: () -> Unit,
    /** 每次自增表示「上层要求暂停解码」（按返回键时） */
    pauseTick: Int = 0
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var showCover by remember { mutableStateOf(true) }
    var permissionDenied by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // 跨线程标志：用 AtomicBoolean，避免在分析线程里读 Compose State
    val alive = remember { AtomicBoolean(true) }
    val handling = remember { AtomicBoolean(false) }
    val paused = remember { AtomicBoolean(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            // FIT_CENTER：屏幕看到的 = 实际分析的整帧（FILL_CENTER 会裁边）
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
                .build()
        )
    }

    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val currentOnScanned by rememberUpdatedState(onScanned)

    LaunchedEffect(Unit) { onEnter() }

    DisposableEffect(Unit) {
        alive.set(true)
        onDispose {
            alive.set(false)
            runCatching { cameraProvider?.unbindAll() }
            runCatching { scanner.close() }
            analysisExecutor.shutdown()
            AppLog.i("Scan", "离开扫码页，相机与识别器已释放")
        }
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
        AppLog.i("Scan", "相机权限: ${if (granted) "已授予" else "被拒绝"}")
        if (granted) scanning = true else showCover = true
    }

    fun startScan() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        AppLog.i("Scan", "开始扫描")
        paused.set(false)
        handling.set(false)
        scanning = true
        showCover = false
    }

    // 首次进页申请权限，但不自动开相机（保留「点击启动扫描」浮层）
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 按返回键：暂停解码并退回浮层，用户想继续可以再点一次
    LaunchedEffect(pauseTick) {
        if (pauseTick > 0) {
            paused.set(true)
            showCover = true
            AppLog.i("Scan", "按返回键，已暂停解码")
        }
    }

    // 相机绑定：Preview + ImageAnalysis
    LaunchedEffect(scanning, permissionGranted, lifecycleOwner) {
        if (!scanning || !permissionGranted) return@LaunchedEffect

        val provider = awaitCameraProvider(context)
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            // 默认 640×480 太小，小码会糊；显式要 1080p
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            // 只保留最新帧，避免积压导致延迟越来越高
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            // 暂停 / 已处理过结果 → 直接丢弃这一帧
            if (paused.get() || handling.get()) {
                proxy.close()
                return@setAnalyzer
            }
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val barcode = barcodes.firstOrNull()
                    val text = barcode?.rawValue
                    if (text.isNullOrBlank()) return@addOnSuccessListener
                    // 抢到处理权才继续，避免同一帧回调多次
                    if (!handling.compareAndSet(false, true)) return@addOnSuccessListener
                    val corners = barcode.cornerPoints
                        ?.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }
                        .orEmpty()
                    AppLog.i("Scan", "识别到: ${text.take(100)} 码位置=$corners")
                    mainHandler.post {
                        if (!alive.get()) {
                            handling.set(false)
                            return@post
                        }
                        if (currentOnDecoded(text)) {
                            currentOnScanned()
                        } else {
                            handling.set(false)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    handling.set(false)
                    AppLog.w("Scan", "识别失败: ${e.message}")
                }
                // 必须关，否则管线会卡死
                .addOnCompleteListener { proxy.close() }
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )

        val info = analysis.resolutionInfo?.resolution
        AppLog.i(
            "Scan",
            "相机已绑定 分析分辨率=${info?.let { "${it.width}x${it.height}" } ?: "未知"} " +
                "预览容器=${previewView.width}x${previewView.height}"
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
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

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
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
