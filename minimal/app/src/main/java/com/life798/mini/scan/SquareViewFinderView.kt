package com.life798.mini.scan

import android.content.Context
import android.graphics.Rect
import com.journeyapps.barcodescanner.ViewfinderView
import kotlin.math.min

/**
 * 正方形取景框。
 *
 * 库默认的取景框按屏幕宽高比算出来是横向矩形（为条形码设计），
 * 扫二维码时上下留白过多、左右反而溢出。
 *
 * 关键点：ViewfinderView 每次 onDraw 都会调用 refreshSizes() 重算 framingRect，
 * 所以必须在 refreshSizes 里覆盖，改 onSizeChanged 是无效的（会被下一帧冲掉）。
 */
class SquareViewfinderView(context: Context) : ViewfinderView(context, null) {

    override fun refreshSizes() {
        // 父类负责算 previewSize，算不出来也不影响我们画框
        runCatching { super.refreshSizes() }

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val side = (min(w, h) * SIDE_RATIO).toInt()
        val left = (w - side) / 2
        val top = (h - side) / 2
        framingRect = Rect(left, top, left + side, top + side)
    }

    private companion object {
        const val SIDE_RATIO = 0.72f
    }
}
