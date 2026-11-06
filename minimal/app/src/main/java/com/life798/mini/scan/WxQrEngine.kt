package com.life798.mini.scan

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.life798.mini.core.AppLog
import com.water.widget.scan.WxQr
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信二维码引擎（wechat_qrcode）的封装。
 *
 * 替换掉原来的 ML Kit：接口形状保持一致 —— 进来一帧 [ImageProxy]，出去一个文本。
 * 相机管线、权限、浮层、释放逻辑都不受影响。
 *
 * **线程约束**：native 层是一个全局单例（`g_qr`），不是线程安全的。
 * 只能从 ScanScreen 那条单线程的 analysisExecutor 调用，别在主线程或其它线程调。
 */
object WxQrEngine {

    private const val TAG = "WxQr"

    /** 四个模型文件名，必须与 assets/wxqr 下的一致 */
    private val MODELS = listOf(
        "detect.prototxt",
        "detect.caffemodel",
        "sr.prototxt",
        "sr.caffemodel"
    )

    private val ready = AtomicBoolean(false)

    @Volatile
    private var initMs = 0L

    @Volatile
    private var initError: String? = null

    /** 最近一帧的解码耗时，毫秒。给日志用。 */
    @Volatile
    var lastDecodeMs = 0L
        private set

    /** 是否按 rotationDegrees 旋转灰度帧。实测后确认库自己能处理旋转的话可以关掉。 */
    @Volatile
    var rotateFrames = true

    fun isReady(): Boolean = ready.get()

    fun status(): String = when {
        ready.get() -> "就绪 ${initMs}ms"
        initError != null -> "失败 $initError"
        else -> "未初始化"
    }

    /**
     * 拷贝模型 + 加载。**阻塞**，必须放后台线程。
     *
     * 失败不缓存 —— 下次进页面还会重试，避免一次偶发失败永久废掉扫码。
     */
    @Synchronized
    fun init(context: Context): Boolean {
        if (ready.get()) return true

        val t0 = SystemClock.elapsedRealtime()
        return runCatching {
            val dir = installModels(context.applicationContext)
            val ok = WxQr.nativeInit(
                File(dir, "detect.prototxt").absolutePath,
                File(dir, "detect.caffemodel").absolutePath,
                File(dir, "sr.prototxt").absolutePath,
                File(dir, "sr.caffemodel").absolutePath
            )
            check(ok) { "nativeInit 返回 false" }
            initMs = SystemClock.elapsedRealtime() - t0
            initError = null
            ready.set(true)
            AppLog.i(TAG, "引擎就绪，耗时 ${initMs}ms")
            true
        }.getOrElse { e ->
            initMs = SystemClock.elapsedRealtime() - t0
            initError = e.message ?: e.javaClass.simpleName
            ready.set(false)
            AppLog.w(TAG, "引擎初始化失败（${initMs}ms）：$initError")
            false
        }
    }

    /**
     * 解一帧。同步，单线程调用。
     *
     * @return 识别到的文本；无结果或未就绪返回 null
     */
    fun decode(proxy: ImageProxy): String? {
        if (!ready.get()) return null

        val t0 = SystemClock.elapsedRealtime()
        val frame = extractGray(proxy)
        val results = WxQr.nativeDecode(frame.gray, frame.width, frame.height, 1.0f)
        lastDecodeMs = SystemClock.elapsedRealtime() - t0
        return results?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun release() {
        if (!ready.get()) return
        runCatching { WxQr.nativeRelease() }
            .onFailure { AppLog.w(TAG, "释放失败：${it.message}") }
        ready.set(false)
        AppLog.i(TAG, "引擎已释放")
    }

    // ── 模型 ────────────────────────────────────────────────────────

    /** assets/wxqr 下的 4 个模型拷到私有目录 —— OpenCV 只接受文件路径，读不了 assets。 */
    private fun installModels(context: Context): File {
        val dir = File(context.filesDir, "wxqr")
        if (!dir.exists() && !dir.mkdirs()) error("无法创建模型目录 ${dir.path}")
        MODELS.forEach { name ->
            val target = File(dir, name)
            if (target.length() <= 0L) {
                context.assets.open("wxqr/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dir
    }

    // ── 灰度帧 ──────────────────────────────────────────────────────

    private class Frame(val gray: ByteArray, val width: Int, val height: Int)

    // 单线程调用，缓冲复用，避免每帧 900 KB 的 GC 压力
    private var srcBuf: ByteArray? = null
    private var dstBuf: ByteArray? = null

    private fun buf(size: Int, isSrc: Boolean): ByteArray {
        val cur = if (isSrc) srcBuf else dstBuf
        if (cur != null && cur.size >= size) return cur
        val fresh = ByteArray(size)
        if (isSrc) srcBuf = fresh else dstBuf = fresh
        return fresh
    }

    /**
     * ImageProxy → 紧凑灰度。
     *
     * Y 平面带行填充（rowStride >= width），不能直接 `buffer.array()`，
     * 必须按 rowStride 逐行取 —— 这是这类库最常见的踩坑点。
     */
    private fun extractGray(proxy: ImageProxy): Frame {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val src = buf(w * h, isSrc = true)
        buffer.rewind()
        if (pixelStride == 1 && rowStride == w) {
            buffer.get(src, 0, w * h)
        } else if (pixelStride == 1) {
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(src, y * w, w)
            }
        } else {
            for (y in 0 until h) {
                val base = y * rowStride
                for (x in 0 until w) {
                    src[y * w + x] = buffer.get(base + x * pixelStride)
                }
            }
        }

        if (!rotateFrames) return Frame(src, w, h)
        return when (proxy.imageInfo.rotationDegrees) {
            90 -> rotate90(src, w, h)
            180 -> rotate180(src, w, h)
            270 -> rotate270(src, w, h)
            else -> Frame(src, w, h)
        }
    }

    /** 顺时针 90°：dst[x * h + (h-1-y)] = src[y * w + x]，输出 h×w */
    private fun rotate90(src: ByteArray, w: Int, h: Int): Frame {
        val dst = buf(w * h, isSrc = false)
        for (y in 0 until h) {
            val rowBase = y * w
            val dstCol = h - 1 - y
            for (x in 0 until w) {
                dst[x * h + dstCol] = src[rowBase + x]
            }
        }
        return Frame(dst, h, w)
    }

    private fun rotate180(src: ByteArray, w: Int, h: Int): Frame {
        val dst = buf(w * h, isSrc = false)
        val total = w * h
        for (i in 0 until total) {
            dst[total - 1 - i] = src[i]
        }
        return Frame(dst, w, h)
    }

    /** 逆时针 90°：dst[(w-1-x) * h + y] = src[y * w + x]，输出 h×w */
    private fun rotate270(src: ByteArray, w: Int, h: Int): Frame {
        val dst = buf(w * h, isSrc = false)
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                dst[(w - 1 - x) * h + y] = src[rowBase + x]
            }
        }
        return Frame(dst, h, w)
    }
}
