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
import kotlin.math.min

/**
 * 逐帧解码器。
 *
 * 三个要点决定了它够不够快：
 *  1. 直接读 Y 平面当灰度图，跳过 YUV→RGB 的转换（省掉单帧 30~80ms）；
 *  2. 只解码画面中心区域，面积降到 36%，速度提升约 3 倍，误识别也更少；
 *  3. 处理 rowStride 填充，否则宽度对不齐会整帧解不出来。
 *
 * 旋转在数据层完成，因为 ZXing 的 PlanarYUVLuminanceSource 不支持旋转。
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // 关掉 TRY_HARDER：实时预览要的是速度，不是极限识别率
                DecodeHintType.TRY_HARDER to false
            )
        )
    }

    private var lastText: String = ""
    private var lastAt: Long = 0L

    @Volatile
    var paused: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return

            val gray = toGrayscale(image) ?: return
            val rotated = rotate(gray.data, gray.width, gray.height, image.imageInfo.rotationDegrees)

            val cropW = (rotated.width * CROP_RATIO).toInt()
            val cropH = (rotated.height * CROP_RATIO).toInt()
            if (cropW < MIN_SIDE || cropH < MIN_SIDE) return

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

            val result = reader.decodeWithState(bitmap)
            emit(result.text)
        } catch (_: NotFoundException) {
            // 这一帧没有码，正常现象
        } catch (_: Throwable) {
            // 解码过程中的任何异常都不该影响下一帧
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun emit(text: String) {
        if (text.isBlank()) return
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
    }
}
