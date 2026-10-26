package com.life798.mini.scan

import android.view.ViewGroup
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.life798.mini.core.AppLog

/**
 * 使用正方形取景框的扫码页。
 *
 * DecoratedBarcodeView 在构造时内部 new 了一个 ViewfinderView，且只暴露 getter。
 * 所以只能在其初始化完成后把那个实例换掉，并同步内部字段引用，
 * 否则扫描线、结果点等绘制仍会落在已被移除的旧 View 上。
 */
class SquareCaptureActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        val container = super.initializeContent()
        swapViewfinder(container)
        return container
    }

    private fun swapViewfinder(container: DecoratedBarcodeView) {
        runCatching {
            val old = container.viewFinder
            val parent = old.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(old)

            val square = SquareViewfinderView(container.context).apply {
                layoutParams = old.layoutParams
            }

            parent.removeView(old)
            parent.addView(square, index)

            // 让库内部字段指向新 View
            val field = DecoratedBarcodeView::class.java
                .getDeclaredField("viewFinder")
                .apply { isAccessible = true }
            field.set(container, square)

            AppLog.i("Scan", "取景框已替换为正方形")
        }.onFailure {
            // 替换失败不影响扫码，只是取景框还是默认形状
            AppLog.w("Scan", "取景框替换失败: ${it.message}")
        }
    }
}
