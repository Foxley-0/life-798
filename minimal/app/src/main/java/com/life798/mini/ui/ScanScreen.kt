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
import android.widget.Toast
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
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
import com.life798.mini.core.DeviceEntry
import com.life798.mini.core.DevicePrefs
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executor
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
 * 1. ImageAnalysis 分辨率必须显式设成 1280×720 —— 默认只有 640×480，小码照样扫不出
 * 2. 每帧必须 imageProxy.close()，漏一次管线就永久卡死
 * 3. 预览用 FIT_CENTER 而不是 FILL_CENTER，否则屏幕边缘「看得见却扫不到」
 */
@Composable
fun ScanScreen(
    state: UiState,
    onEnter: () -> Unit,
    onDecoded: (String) -> Boolean,
    onScanned: () -> Unit,
    /** 从设备列表点进来：同样解析编号，但**不记账**（不是真的扫过） */
    onPickDevice: (String) -> Boolean = onDecoded,
    /** 有设备正在出水时，浮层上多一个「回到用水界面」的入口 */
    onResumeUsing: () -> Unit = {},
    /** 底栏占掉的高度。设备列表底部要避开它，否则最后一项会被胶囊压住 */
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    /**
     * 关帧专用执行器。
     *
     * 为什么不直接用 analysisExecutor：页面销毁时 onDispose 会 shutdown 它，
     * 而这一刻可能还有一帧挂在 ML Kit 里没回调完。等它回调时再往已关闭的
     * 线程池投递，会抛 RejectedExecutionException —— 抛在 ML Kit 的内部线程上，
     * 没人接，直接崩。
     *
     * 线程池还活着 → 丢进去异步关；已经关了 → 就在当前线程同步关。
     * 两条路都保证 proxy.close() 一定被执行，管线不会卡死。
     */
    val closeExecutor = remember {
        Executor { runnable ->
            runCatching { analysisExecutor.execute(runnable) }
                .onFailure { runnable.run() }
        }
    }

    /** 是否在扫描。浮层的显示条件就是它的反 —— 不再单独维护 showCover。 */
    val scanning by CameraController.scanning.collectAsState()

    // 常用设备按手机号隔离：换账号就是另一个 key，跟上一个账号毫无关系
    val devicePrefs = remember(state.phone) { DevicePrefs(context, state.phone) }
    var deviceTick by remember { mutableStateOf(0) }
    // 扫描时清空：浮层已经淡出，没必要再算一遍列表
    val favorites = remember(devicePrefs, scanning, deviceTick) {
        if (scanning) emptyList() else devicePrefs.favorites()
    }
    val recent = remember(devicePrefs, scanning, deviceTick) {
        if (scanning) emptyList() else devicePrefs.recentVisits()
    }
    var renaming by remember { mutableStateOf<DeviceEntry?>(null) }

    /** 管理模式：只影响下面那几张设备卡，不接管整页、不隐藏底栏。 */
    var managing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    /** 最近 7 天抽屉 */
    var showRecent by remember { mutableStateOf(false) }

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
                // 只认二维码。设备上贴的就是二维码，一维码（CODE_128）从来没用到过；
                // 只开一种格式，识别器不会去跑 1D 那条解码分支，也省得误把别的码当设备号。
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
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

    // 状态栏外观**不在这里改**。
    //
    // 这里曾经有一段「进页设成深色图标、离开还原」的代码，是当年这个页面
    // 固定白底时留下的。现在整页跟着主题走，那段就成了 bug：
    // 深色模式下进扫描页，图标被强行刷成深色，深底上看不见；
    // 而 enableEdgeToEdge 只在 dark 变化时执行，不会把它纠正回来。
    //
    // 唯一权威来源是 MainActivity 里的 enableEdgeToEdge(dark)。

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
     *
     * 每次都重新查一遍权限，而不是只读 [permissionGranted]：
     * 用户被拒之后会去系统设置里手动打开，回来时 Compose 不会重建这个页面，
     * 那个状态变量还是 false —— 只读它的话点多少次都弹不出相机。
     */
    fun startScan() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        permissionGranted = granted
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        permissionDenied = false
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

            // Preview 和 ImageAnalysis **故意用不同的分辨率**。
            //
            // 原来两者共用 1920×1080：画面是清楚了，但 ML Kit 单帧要处理
            // 200 万像素，低端机 1~3 秒。而这个帧率直接决定了两件事：
            //   ① 扫码响应速度
            //   ② 释放相机时 unbindAll 要等多久（它要等 in-flight 的帧结束）
            //
            // 720p 比当年 ZXing 时代的 640×480 还大 4 倍，扫 20~30cm 的码足够。
            // 预览仍走 1080p，用户看到的画面不变。
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
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
                                // 识别成功就立刻断开相机：onScanned 只是往栈上 push
                                // 一个路由，扫码页还留在 Pager 里没销毁，相机不会自己关
                                CameraController.release("识别成功")
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
                    // 必须关，否则管线会卡死。
                    // 回调显式指定 closeExecutor：默认走主线程，切页时 Compose
                    // 转场动画把主线程占满，这个 close 就得排队 —— 而 unbindAll
                    // 正等着它。
                    .addOnCompleteListener(closeExecutor) { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            CameraController.markBound(provider) {
                // 同步断开两条管线。必须在 unbindAll 之前做：
                // clearAnalyzer 让 CameraX 停止往 analyzer 送帧（不再等 ML Kit 处理完），
                // setSurfaceProvider(null) 让 Preview 立刻放掉 Surface。
                // 这两个调用返回后画面就没了，用户看到的是「相机立刻关掉」。
                runCatching { analysis.clearAnalyzer() }
                runCatching { preview.setSurfaceProvider(null) }
            }

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

    // 浮层整体淡入淡出。
    // 不用 AnimatedVisibility：它会把子内容的约束改成 wrap-content，
    // fillMaxSize 随之失效，整块浮层就贴到左边去了。
    val overlayAlpha by animateFloatAsState(
        targetValue = if (scanning) 0f else 1f,
        animationSpec = tween(180),
        label = "scanOverlay"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            // 原生 View 不参与 Compose 裁剪，不加这个会在 pager 滑动时溢出到相邻页
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )

        // 浮层：页头 → 扫描卡 → 常用设备。
        // 三块内容从同一个 Column 往下排，设备列表吃掉剩余高度并自己滚动 ——
        // 卡片多了就滚，不靠「隐藏底栏」腾地方。
        if (overlayAlpha > 0.01f) {
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = overlayAlpha }
                    .background(MiuixTheme.colorScheme.background)
                    .padding(top = statusTop)
            ) {
                PageTop()

                // 页头到卡片 52dp：这段留白是整页的呼吸区。
                // 挤到 20 出头，视觉重心会重新回到上半屏 —— 就是之前被打回的那版
                Spacer(Modifier.height(52.dp))

                HeroCard(
                    enabled = !managing,
                    onStartScan = { startScan() },
                    onOpenRecent = { showRecent = true }
                )

                DeviceSection(
                    favorites = favorites,
                    activeDeviceId = state.session?.deviceId,
                    managing = managing,
                    selected = selected,
                    onPick = { id ->
                        // 设备编号直接当二维码原文喂进去，复用整套解析 + 加载设备名。
                        // 但不算「扫过」，不写流水。
                        if (onPickDevice(id)) currentOnScanned()
                    },
                    onToggleSelect = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    },
                    onSelectAll = {
                        selected = if (selected.size == favorites.size) {
                            emptySet()
                        } else {
                            favorites.map { it.id }.toSet()
                        }
                    },
                    onToggleManage = {
                        managing = !managing
                        // 退出管理必须清空选择，否则下次进来还带着上次勾的
                        if (!managing) selected = emptySet()
                    },
                    onRename = { entry -> renaming = entry },
                    onRemove = {
                        val count = selected.size
                        selected.forEach { devicePrefs.removeFavorite(it) }
                        selected = emptySet()
                        deviceTick++
                        Toast.makeText(context, "已移出 $count 台设备", Toast.LENGTH_SHORT).show()
                    },
                    onOpenRecent = { showRecent = true },
                    bottomInset = bottomInset,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // 最近 7 天抽屉。放在浮层外面：它自带遮罩和进出场动画，
    // 跟着浮层一起淡出会在切页时闪一下。
    //
    // 高度固定成 1/2 屏：抽屉随记录条数伸缩的话，每次打开都得重新找位置，
    // 只有一条时又矮得像个提示条。定高之后不管几条，视觉重心都在同一个地方。
    // 减掉的 52dp 是 miuix 抽屉自带的拖拽条 + 标题行高度（真机实测，不是估算）。
    // 往上拖不再变高 —— 半屏已经能放 5 行，再多就滚动；往下拖仍然是关闭（miuix 自带）。
    @Suppress("DEPRECATION")
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetListHeight = (screenHeight * 0.50f - 52.dp).coerceAtLeast(150.dp)

    OverlayBottomSheet(
        show = showRecent,
        title = "最近 7 天",
        // 抽屉底色必须显式给，不能用 miuix 默认值。
        // 默认值是 BottomSheetDefaults.backgroundColor() = colorScheme.background，
        // 也就是「跟页面背景同色」—— MIUI 靠遮罩分层没问题，但本项目的 background
        // 被 Theme.kt 压到了 #F2F3F5 / #121212，遮罩压暗后两块糊成一片（深色下尤其明显）。
        // 换成 surfaceContainer（浅 #FFF / 深 #242424），和页面拉开一档，浮层才立得住。
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        endAction = {
            IconButton(onClick = { showRecent = false }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        },
        cornerRadius = 26.dp,
        onDismissRequest = { showRecent = false }
    ) {
        RecentSheetContent(
            recent = recent,
            favoriteIds = favorites.map { it.id }.toSet(),
            listHeight = sheetListHeight,
            onPick = { id ->
                // 解析失败就不关抽屉，让用户换一台点
                if (onPickDevice(id)) {
                    showRecent = false
                    currentOnScanned()
                }
            },
            onAdd = { id ->
                devicePrefs.addFavorite(id)
                deviceTick++
                Toast.makeText(context, "已添加到常用设备", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 权限被拒改用对话框：新版浮层里没有位置塞一段红字，
    // 而且只有真的去点了扫描才该弹，浏览列表时不该被打扰
    if (permissionDenied) {
        OverlayDialog(
            show = true,
            title = "需要相机权限",
            summary = "扫码要用到摄像头，请在系统设置里允许「慧生活」访问相机。",
            onDismissRequest = { permissionDenied = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "稍后",
                    onClick = { permissionDenied = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        permissionDenied = false
                        openAppSettings(context)
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("去设置")
                }
            }
        }
    }

    // 改名弹窗放在浮层外面：它不该跟着浮层一起淡出
    renaming?.let { entry ->
        RenameDialog(
            entry = entry,
            onConfirm = { alias ->
                devicePrefs.renameFavorite(entry.id, alias)
                deviceTick++
                renaming = null
            },
            onDismiss = { renaming = null }
        )
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

/**
 * 改名弹窗。
 *
 * 副标题显示服务端返回的原设备名 —— 用户靠名字认设备，不靠编号。
 * 清空保存 = 恢复原名。
 */
@Composable
private fun RenameDialog(
    entry: DeviceEntry,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(entry.id) { mutableStateOf(entry.alias) }

    OverlayDialog(
        show = true,
        title = "重命名",
        summary = entry.name.ifBlank { null },
        onDismissRequest = onDismiss
    ) {
        TextField(
            value = text,
            onValueChange = { if (it.length <= 16) text = it },
            label = "设备名字",
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f)
            ) {
                Text("保存")
            }
        }
    }
}
