package com.life798.mini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏毛玻璃。移植自 SonyPods 的 `ui/components/BarBlur.kt`，只保留底栏部分。
 *
 * 原理：内容层用 [BarBackdropContent] 把自己录进一个 [LayerBackdrop]，
 * 底栏再用 [textureBlur] 采样这张图 —— 所以滚动时内容会在栏下虚化，
 * 而不是栏自己糊自己。
 *
 * 只有开启开关且设备支持 RuntimeShader 时才启用；否则降级为不透明 surface，
 * 不会出现「栏是透明的但背后没东西」的黑块。
 */
internal val LocalBottomBarBlurEnabled = staticCompositionLocalOf { false }

internal val LocalBarBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun BarBlurHost(
    bottomBarBlurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if (bottomBarBlurEnabled && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    CompositionLocalProvider(
        LocalBottomBarBlurEnabled provides bottomBarBlurEnabled,
        LocalBarBlurBackdrop provides backdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/** 标记「这一层的内容会被底栏采样」，必须包住页面内容，且不能包住底栏本身。 */
@Composable
internal fun BarBackdropContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBarBlurBackdrop.current
    Box(
        modifier = modifier.then(
            if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        ),
    ) {
        content()
    }
}

/** 底栏背景。模糊不可用时退回不透明 surface。 */
@Composable
internal fun Modifier.barBlurBackground(shape: Shape): Modifier {
    val backdrop = LocalBarBlurBackdrop.current
    val blurEnabled = LocalBottomBarBlurEnabled.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val contentColor = MiuixTheme.colorScheme.onSurface
    return if (blurEnabled && backdrop != null) {
        textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = BAR_BLUR_RADIUS,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(color = surfaceColor.copy(alpha = BOTTOM_BAR_SURFACE_ALPHA)),
                    BlendColorEntry(color = contentColor.copy(alpha = BAR_GLASS_TINT_ALPHA)),
                ),
            ),
        )
    } else {
        background(color = surfaceColor, shape = shape)
    }
}

private const val BAR_BLUR_RADIUS = 16f
private const val BOTTOM_BAR_SURFACE_ALPHA = 0.58f
private const val BAR_GLASS_TINT_ALPHA = 0.025f
