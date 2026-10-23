package com.life798.mini.scan

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.life798.mini.core.AppLog
import kotlin.math.min

/**
 * 逐帧解码器。
 *
 * 关键约定：
 *  1. **回调必须在 try 之外触发**。之前写在 try 里，回调抛的异常会被下面的
 *     catch (Throwable) 吃掉，表现为"扫了没反应"，极难排查。
 *  2. 直接读 Y 平面当灰度图，跳过 YUV→RGB 转换。
 *  3. 只解码中心区域，降低单帧耗时。
 *  4. 读 buffer 前先 rewind，CameraX 复用的 buffer 位置不一定在 0。
 *
 * @param onDecoded 识别成功回调，运行在相机线程，调用方需自行切主线程
 * @param onStats 每 N 帧回调一次进度，用于界面显示，同样在相机线程
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val onStats: (frames: Int, hits: Int) -> Unit = { _, _ -> }
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to false
            )
        )
    }

    private var lastText: String = ""
    private var lastAt: Long = 0L
    private var frames: Int = 0
    private var hits: Int = 0

    @Volatile
    var paused: Boolean = false

    override fun analyze(image: ImageProxy) {
        var decoded: String? = null

        try {
            if (paused) return

            val gray = toGrayscale(image)
            if (gray != null) {
                val rotated = rotate(gray.data, gray.width, gray.height, image.imageInfo.rotationDegrees)

                val cropW = (rotated.width * CROP_RATIO).toInt()
                val cropH = (rotated.height * CROP_RATIO).toInt()

                if (cropW >= MIN_SIDE && cropH >= MIN_SIDE) {
                    val left = ((rotated.width - cropW) / 2).coerceAtLeast(0)
                    val top = ((rotated.height - cropH) / 2).coerceAtLeast(0)

                    val source = PlanarYUVLuminanceSource(
                        rotated.data,
                        rotated.width,
                        rotated.height,
                        left,
                        top,
                        cropW,
                        cropH,
                        false
                    )
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    decoded = reader.decodeWithState(bitmap).text
                }
            }
        } catch (_: NotFoundException) {
            // 这一帧没有码，正常现象
        } catch (e: Throwable) {
            AppLog.w("Scan", "解码异常: ${e.message}")
        } finally {
            reader.reset()
            image.close()
        }

        // ---- 以下在 try 之外，异常不再被上面的 catch 吞掉 ----

        frames++
        if (frames % STATS_EVERY == 0) {
            try {
                onStats(frames, hits)
            } catch (e: Throwable) {
                AppLog.w("Scan", "进度回调异常: ${e.message}")
            }
        }

        val text = decoded
        if (!text.isNullOrBlank()) {
            hits++
            try {
                AppLog.i("Scan", "识别成功($hits): ${text.take(80)}")
                emit(text)
            } catch (e: Throwable) {
                // 回调链路出错必须留痕，否则又是一次"没反应"
                AppLog.e("Scan", "识别回调抛出异常", e)
            }
        }
    }

    private fun emit(text: String) {
        val now = SystemClock.elapsedRealtime()
        // 同一内容 2 秒内只回调一次，否则连续帧会触发几十次
        if (text == lastText && now - lastAt < DEDUPE_MS) return
        lastText = text
        lastAt = now
        onDecoded(text)
    }

    /** 把 ImageProxy 的 Y 平面压成紧凑的 w*h 数组（去掉 rowStride 填充）。 */
    private fun toGrayscale(image: ImageProxy): Gray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val buffer = plane.buffer
        // CameraX 会复用 buffer，位置不一定在 0，不归零会读到错位图像
        buffer.rewind()

        val rowStride = plane.rowStride
        val data = ByteArray(width * height)

        if (rowStride == width) {
            val need = min(buffer.remaining(), data.size)
            buffer.get(data, 0, need)
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                if (buffer.remaining() <= 0) break
                val len = min(rowStride, buffer.remaining())
                buffer.get(row, 0, len)
                System.arraycopy(row, 0, data, y * width, min(width, len))
            }
        }
        return Gray(data, width, height)
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, degrees: Int): Gray {
        return when (((degrees % 360) + 360) % 360) {
            90 -> {
                val dst = ByteArray(src.size)
                val nw = h
                val nh = w
                for (y in 0 until nh) {
                    for (x in 0 until nw) {
                        dst[y * nw + x] = src[(h - 1 - x) * w + y]
                    }
                }
                Gray(dst, nw, nh)
            }

            180 -> {
                val dst = ByteArray(src.size)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        dst[y * w + x] = src[(h - 1 - y) * w + (w - 1 - x)]
                    }
                }
                Gray(dst, w, h)
            }

            270 -> {
                val dst = ByteArray(src.size)
                val nw = h
                val nh = w
                for (y in 0 until nh) {
                    for (x in 0 until nw) {
                        dst[y * nw + x] = src[x * w + (w - 1 - y)]
                    }
                }
                Gray(dst, nw, nh)
            }

            else -> Gray(src, w, h)
        }
    }

    private class Gray(val data: ByteArray, val width: Int, val height: Int)

    private companion object {
        /** 中心裁剪比例，0.6 表示取中间 60%×60%。 */
        const val CROP_RATIO = 0.6f
        const val MIN_SIDE = 64
        const val DEDUPE_MS = 2000L

        /** 每处理多少帧回报一次进度。 */
        const val STATS_EVERY = 20
    }
}
