package com.life798.mini.ui.components

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import com.journeyapps.barcodescanner.BarcodeView

/**
 * 全帧解码的 [BarcodeView]。
 *
 * 默认的 `CameraPreview.calculateFramingRect` 会做两件事：
 * 1. 四边各内缩 10%（`marginFraction = 0.1`）
 * 2. 竖屏时（`height > width` 必然成立）把区域再裁成正方形
 *
 * 结果是 1080×2400 的屏幕上只有中间 864×864 参与解码，上下各丢约 660px，
 * 屏幕边缘的码永远扫不到。
 *
 * 这里直接返回 `container ∩ surface`，不内缩、不裁方。
 * 这是源码注释里明确留的扩展点：
 * "Override this for more control over the framing rect calculations."
 *
 * 注意：不要用 `setMarginFraction(0)` 代替 —— 那只消掉第 1 步，
 * 裁正方形那段是硬编码的，绕不过去。
 */
class FullScreenBarcodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BarcodeView(context, attrs, defStyleAttr) {

    override fun calculateFramingRect(container: Rect, surface: Rect): Rect {
        val intersection = Rect(container)
        intersection.intersect(surface)
        return intersection
    }
}
