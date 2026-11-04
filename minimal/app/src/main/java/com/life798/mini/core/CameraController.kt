package com.life798.mini.core

import android.os.SystemClock
import androidx.camera.lifecycle.ProcessCameraProvider
import com.life798.mini.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 相机的唯一真相源。
 *
 * 整个 App 只有两个写入口：
 *
 *   [beginScanning]  用户点「点击启动扫描」按钮 —— **开相机的唯一入口**
 *   [release]        离开扫描页 / 按返回 / 页面销毁 / 退到后台
 *
 * 没有「自动恢复」这回事：离开之后再想扫，必须重新点按钮。
 */
object CameraController {

    private val provider = AtomicReference<ProcessCameraProvider?>(null)
    private val bound = AtomicBoolean(false)

    /**
     * 绑定后注册的「断开 use case」回调：停分析 + 断预览。
     *
     * 为什么要单独存一份：`unbindAll()` 只是解除绑定，相机硬件还要等
     * ImageAnalysis 的 analyzer 停止送帧、Preview 的 Surface 释放才真正关闭。
     * 而 analyzer 里跑着 ML Kit，一帧要几百毫秒到几秒，`proxy.close()`
     * 挂在识别完成的回调上 —— 不主动断开，`unbindAll()` 就得一直等。
     *
     * 这两个调用是纯同步的，做完画面立刻消失，不用等 CameraX 的异步关闭。
     */
    private var detachUseCases: (() -> Unit)? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 点按钮时调用。全工程只有这一处会把 scanning 置 true。 */
    fun beginScanning() {
        AppLog.i(TAG, "开始扫描（按钮触发）")
        _scanning.value = true
    }

    /**
     * 释放相机。调用即生效 —— 不排队、不等协程。
     *
     * 三步都带毫秒计时，慢的时候能一眼看出卡在哪：
     *   已断开 use case +Nms   ← 卡这里说明 ML Kit 还在处理帧
     *   unbindAll +Nms ok=     ← 卡这里说明是 CameraX/驱动的问题
     *
     * **bound 只在解绑成功后才清**：以前是先 getAndSet(false) 再操作，
     * 一旦那次 unbindAll 没真正生效，后续所有 release（包括页面销毁时那次）
     * 都会因为 bound 已经是 false 直接返回，再也没有重试机会。
     */
    fun release(reason: String) {
        _scanning.value = false

        if (!bound.get()) return

        val t0 = SystemClock.elapsedRealtime()

        runCatching { detachUseCases?.invoke() }
            .onFailure { AppLog.w(TAG, "断开 use case 异常：${it.message}") }
        detachUseCases = null
        AppLog.i(TAG, "已断开 use case +${SystemClock.elapsedRealtime() - t0}ms")

        val ok = runCatching { provider.get()?.unbindAll() }.isSuccess
        val elapsed = SystemClock.elapsedRealtime() - t0
        AppLog.i(TAG, "unbindAll +${elapsed}ms ok=$ok")

        if (ok) {
            bound.set(false)
            AppLog.i(TAG, "相机已释放：$reason（+${elapsed}ms）")
        } else {
            // 保留 bound，下次 release 还能再试一次
            AppLog.w(TAG, "解绑失败，保留 bound 以便重试（$reason）")
        }
    }

    /**
     * 绑定成功后由 ScanScreen 调用。
     *
     * @param detach 停分析 + 断预览的同步回调，release 时先于 unbindAll 执行。
     */
    fun markBound(p: ProcessCameraProvider, detach: () -> Unit) {
        provider.set(p)
        detachUseCases = detach
        bound.set(true)
        AppLog.i(TAG, "相机已绑定")
    }

    fun isScanning(): Boolean = _scanning.value

    fun isBound(): Boolean = bound.get()

    private val TAG = "Scan[${BuildConfig.VERSION_NAME}]"
}
