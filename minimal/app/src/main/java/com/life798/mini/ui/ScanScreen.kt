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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import com.life798.mini.core.CameraController
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 扫码页：CameraX + ML Kit，**分析整帧**。
 *
 * 相机生命周期只有两条路：
 * - 点「点击启动扫描」→ [CameraController.beginScanning] → 绑定
 * - 离开本页（切 tab / 返回 / 进二级页 / 退后台）→ [CameraController.release] → 解绑
 *
 * 没有自动恢复：滑走再滑回来，显示的是浮层，要扫必须重新点按钮。
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
    /** 有设备正在出水时，浮层上多一个「回到用水界面」的入口 */
    onResumeUsing: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    /** 是否在扫描。浮层的显示条件就是它的反 —— 不再单独维护 showCover。 */
    val scanning by CameraController.scanning.collectAsState()

    var permissionDenied by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // 跨线程标志：用 AtomicBoolean，避免在分析线程里读 Compose State
    val alive = remember { AtomicBoolean(true) }
    val handling = remember { AtomicBoolean(false) }
    val camLock = remember { Mutex() }

    val previewView = remember {
        PreviewView(context).apply {
            // FILL_CENTER：铺满屏幕、无黑边。等比缩放只裁边不变形；
            // 裁掉的区域不影响识别 —— ImageAnalysis 分析的始终是完整帧
            scaleType = PreviewView.ScaleType.FILL_CENTER
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
            CameraController.release("扫码页销毁")
            runCatching { scanner.close() }
            analysisExecutor.shutdown()
            AppLog.i("Scan", "离开扫码页，识别器已释放")
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
        // 这里能走到 granted=true，只可能是因为用户刚点了「点击启动扫描」——
        // 本页不再自动申请权限，所以不需要 pendingStart 之类的标志
        if (granted) CameraController.beginScanning()
    }

    /**
     * 开相机的唯一入口。整个 App 里只有这个按钮和上面权限回调会调 beginScanning。
     */
    fun startScan() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        handling.set(false)
        CameraController.beginScanning()
    }

    /**
     * 绑定。
     *
     * 两处 isScanning 检查不是多余的：
     * ① awaitCameraProvider 可能挂起几百毫秒，这期间用户完全可能已经切页并被 release
     * ② bindToLifecycle 与 markBound 之间虽然没有挂起点，但 release 是同步方法，
     *    理论上能插进来；查一次，绑错了当场拆掉
     */
    LaunchedEffect(scanning, permissionGranted) {
        if (!scanning || !permissionGranted) return@LaunchedEffect

        val provider = awaitCameraProvider(context)

        if (!CameraController.isScanning()) {
            AppLog.i("Scan", "等待 provider 期间已释放，放弃绑定")
            return@LaunchedEffect
        }

        camLock.withLock {
            if (!CameraController.isScanning() || CameraController.isBound()) return@withLock

            // Preview 与 ImageAnalysis 必须共用同一个 selector，
            // 否则「屏幕看到的画面」和「实际分析的画面」不是同一块区域。
            // 优先 16:9：设备给 4:3（1920x1440）时铺满竖屏会裁掉太多
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(selector)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                // 只保留最新帧，避免积压导致延迟越来越高
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                // 已处理过结果 → 直接丢弃这一帧
                if (handling.get()) {
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
            CameraController.markBound(provider)

            if (!CameraController.isScanning()) {
                CameraController.release("绑定后已被释放")
                return@withLock
            }

            val info = analysis.resolutionInfo?.resolution
            AppLog.i(
                "Scan",
                "预览已就绪 分析分辨率=${info?.let { "${it.width}x${it.height}" } ?: "未知"} " +
                    "预览容器=${previewView.width}x${previewView.height}"
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            // 原生 View 不参与 Compose 裁剪，不加这个会在 pager 滑动时溢出到相邻页
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )

        // 浮层：三块内容原样保留，只是开关从 showCover 换成了 !scanning
        AnimatedVisibility(
            visible = !scanning,
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 与页面背景同色，跟随主题（深色下自动变深，不会闪浅灰）
                    .background(MiuixTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
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
                        Spacer(Modifier.height(20.dp))
                        // 从系统设置回来后再点一次，权限已授予就会直接开相机
                        Button(
                            onClick = { startScan() },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("点击启动扫描", fontSize = 16.sp)
                        }
                    } else {
                        // 有一条会话还在出水：这是最该被看见的东西，
                        // 否则用户只能靠记忆想起「我刚才是不是没关水」
                        state.session?.let { session ->
                            Text(
                                text = "「${session.deviceName ?: session.deviceId}」正在出水",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onResumeUsing,
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text("回到用水界面", fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                        Button(
                            onClick = { startScan() },
                            colors = ButtonDefaults.buttonColorsPrimary(),
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
