package com.life798.mini.core

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
 *
 * 为什么解绑是一个同步方法而不是一个状态：
 * 前两版把「该不该开相机」做成 Compose 状态往下传，链路要经过
 * pagerState → snapshotFlow → State → 参数 → effect 五跳，任何一跳断了
 * 都表现为「相机不释放」，且无法从现象判断断在哪。相机是独占资源，
 * 页面切走那一刻就该还回去，不该等状态传播。
 */
object CameraController {

    private val provider = AtomicReference<ProcessCameraProvider?>(null)
    private val bound = AtomicBoolean(false)

    /**
     * 是否正在扫描。UI 用它决定浮层显不显示，绑定逻辑用它决定绑不绑。
     *
     * 用 StateFlow 而不是 Compose State：它只是给界面看的一个镜像，
     * 真正的释放动作在 [release] 里同步完成，不依赖这个值被谁读到。
     */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 点按钮时调用。全工程只有这一处会把 scanning 置 true。 */
    fun beginScanning() {
        AppLog.i(TAG, "开始扫描（按钮触发）")
        _scanning.value = true
    }

    /**
     * 释放相机。调用即生效 —— 同步 unbindAll，不排队、不等协程。
     *
     * 幂等：没绑定过就直接返回，不刷日志。
     */
    fun release(reason: String) {
        _scanning.value = false

        if (!bound.getAndSet(false)) return

        val p = provider.get()
        if (p == null) {
            AppLog.w(TAG, "释放跳过：provider 未注册（$reason）")
            return
        }
        runCatching { p.unbindAll() }
            .onSuccess { AppLog.i(TAG, "相机已释放：$reason") }
            .onFailure { AppLog.w(TAG, "释放异常：${it.message}（$reason）") }
    }

    /** 绑定成功后由 ScanScreen 调用，release 靠它拿到句柄。 */
    fun markBound(p: ProcessCameraProvider) {
        provider.set(p)
        bound.set(true)
        AppLog.i(TAG, "相机已绑定")
    }

    fun isScanning(): Boolean = _scanning.value

    fun isBound(): Boolean = bound.get()

    private val TAG = "Scan[${BuildConfig.VERSION_NAME}]"
}
