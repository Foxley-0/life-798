package com.life798.mini.ui.components

/*
 * Adapted from compose-miuix-ui NumberPicker (Apache-2.0), via HUEL-yktwx.
 *
 * 为什么不用 miuix 自带的 NumberPicker：
 * 那个组件是给「单个数值」设计的，每一列自带一条独立的选中高亮条和上下渐变遮罩。
 * 两个并排放在一起就是两条断开的横线、两套渐变在中间对撞 —— 这是结构问题，
 * 调间距调不出来。这里去掉高亮条，改用颜色渐变 + 缩放 + 透明度区分选中项，
 * 并排时视觉才是一体的。
 *
 * 另一个关键设计是 displayRange 与 selectableRange 分离：
 * - displayRange：滚轮上画出来的完整范围（月份固定 1..12，视觉连续）
 * - selectableRange：真正允许停下的范围（只有有数据的部分）
 * 边界外的值看得见但停不上去，拖出去会被橡皮筋弹回。
 * 这样既不会出现「滚一下跳两个月」，也不会停在没有记录的地方。
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.exp

/**
 * 单列滚轮。
 *
 * @param value 当前值，必须落在 [selectableRange] 内
 * @param selectableRange 允许停下的范围
 * @param displayRange 画出来的完整范围，必须包含 [selectableRange]
 * @param label 数值转显示文字
 * @param visibleItemCount 可见行数，必须是奇数且 >= 3
 */
@Composable
internal fun WheelPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    selectableRange: IntRange,
    displayRange: IntRange,
    modifier: Modifier = Modifier,
    label: (Int) -> String,
    visibleItemCount: Int = 5,
    // 选中项用主色 —— MIUI 的选择器就是蓝字，之前用 onSurface（纯黑）看着不像系统控件
    selectedColor: Color = MiuixTheme.colorScheme.primary,
    // 未选中项用 onSurfaceSecondary 而不是 VariantSummary：后者太淡，叠上 alpha 渐变后几乎看不见
    unselectedColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
    textStyle: TextStyle = MiuixTheme.textStyles.title1,
    itemHeight: Dp = 44.dp
) {
    require(visibleItemCount % 2 == 1 && visibleItemCount >= 3) {
        "visibleItemCount 必须是奇数且 >= 3"
    }
    require(selectableRange.first >= displayRange.first && selectableRange.last <= displayRange.last) {
        "selectableRange 必须落在 displayRange 内"
    }
    // 空数据时调用方会传一个兜底区间，这里再兜一次，避免崩在滚轮里
    val safeValue = value.coerceIn(selectableRange.first, selectableRange.last)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val currentIndex = safeValue - displayRange.first
    val minIndex = selectableRange.first - displayRange.first
    val maxIndex = selectableRange.last - displayRange.first
    val minOffset = (minIndex - currentIndex).toFloat()
    val maxOffset = (maxIndex - currentIndex).toFloat()
    val halfVisibleCount = visibleItemCount / 2
    val totalHeight = itemHeight * visibleItemCount

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val flingOffset = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    val totalOffset by remember { derivedStateOf { dragOffset + flingOffset.value } }

    // 外部改值（比如换年后月份被夹紧）时，把残留的惯性归零
    LaunchedEffect(safeValue) {
        if (!isDragging && dragOffset == 0f) flingOffset.snapTo(0f)
    }

    val draggableState = rememberDraggableState { delta ->
        if (itemHeightPx > 0) {
            dragOffset = rubberBand(
                value = dragOffset - delta / itemHeightPx,
                minimum = minOffset,
                maximum = maxOffset
            )
        }
    }

    val resolvedTextStyle = if (textStyle.fontWeight == null) {
        textStyle.copy(fontWeight = FontWeight.SemiBold)
    } else {
        textStyle
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .clipToBounds()
            .semantics {
                contentDescription =
                    "${label(safeValue)}, ${selectableRange.first} - ${selectableRange.last}"
            }
            .onSizeChanged { itemHeightPx = it.height / visibleItemCount }
            .draggable(
                orientation = Orientation.Vertical,
                state = draggableState,
                onDragStarted = {
                    flingOffset.stop()
                    dragOffset += flingOffset.value
                    flingOffset.snapTo(0f)
                    isDragging = true
                },
                onDragStopped = { velocity ->
                    isDragging = false
                    if (itemHeightPx > 0) {
                        val currentDragOffset = dragOffset
                        dragOffset = 0f
                        flingOffset.snapTo(currentDragOffset)

                        if (currentDragOffset < minOffset) {
                            // 拖过下边界：弹回去，并夹到可选范围的第一项
                            flingOffset.animateTo(
                                targetValue = minOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)
                            )
                            if (safeValue != selectableRange.first) {
                                currentOnValueChange(selectableRange.first)
                            }
                        } else if (currentDragOffset > maxOffset) {
                            flingOffset.animateTo(
                                targetValue = maxOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)
                            )
                            if (safeValue != selectableRange.last) {
                                currentOnValueChange(selectableRange.last)
                            }
                        } else {
                            // 惯性滑动 → 取整吸附
                            val velocityInItems = -velocity / itemHeightPx
                            flingOffset.updateBounds(minOffset, maxOffset)
                            flingOffset.animateDecay(
                                velocityInItems,
                                exponentialDecay(frictionMultiplier = 2f)
                            )
                            val snapped = flingOffset.value
                                .fastRoundToInt()
                                .toFloat()
                                .coerceIn(minOffset, maxOffset)
                            flingOffset.animateTo(
                                snapped,
                                spring(dampingRatio = 1f, stiffness = 400f)
                            )
                            val newIndex = (currentIndex + flingOffset.value.fastRoundToInt())
                                .coerceIn(minIndex, maxIndex)
                            val newValue = displayRange.first + newIndex
                            if (newValue != safeValue) currentOnValueChange(newValue)
                            flingOffset.updateBounds(
                                Float.NEGATIVE_INFINITY,
                                Float.POSITIVE_INFINITY
                            )
                        }
                        flingOffset.snapTo(0f)
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (itemHeightPx > 0) {
            val offset = totalOffset
            val centerOffset = offset - offset.fastRoundToInt()
            val roundedOffset = offset.fastRoundToInt()
            // 多画一行，滚动时上下边缘不会突然露白
            for (i in -halfVisibleCount - 1..halfVisibleCount + 1) {
                val itemIndex = currentIndex + i + roundedOffset
                if (itemIndex !in 0 until displayRange.count()) continue
                val displayValue = displayRange.first + itemIndex
                val distance = i.toFloat() - centerOffset
                val normalized = (abs(distance) / (halfVisibleCount + 0.5f)).coerceIn(0f, 1f)
                // 越远越淡、越小，形成柱面感
                val alpha = (1f - normalized) * (1f - normalized * 0.5f)
                val scale = 1f - 0.2f * normalized
                Text(
                    text = label(displayValue),
                    modifier = Modifier.graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        translationY = distance * itemHeightPx
                    },
                    style = resolvedTextStyle,
                    color = Color(
                        red = lerp(selectedColor.red, unselectedColor.red, normalized),
                        green = lerp(selectedColor.green, unselectedColor.green, normalized),
                        blue = lerp(selectedColor.blue, unselectedColor.blue, normalized),
                        alpha = lerp(selectedColor.alpha, unselectedColor.alpha, normalized)
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 拖过边界时的橡皮筋阻尼：越拖越硬，最多再走 [MAX_EDGE_OFFSET] 项。 */
private fun rubberBand(value: Float, minimum: Float, maximum: Float): Float = when {
    value < minimum -> minimum - edgeResistance(minimum - value)
    value > maximum -> maximum + edgeResistance(value - maximum)
    else -> value
}

private fun edgeResistance(distance: Float): Float =
    MAX_EDGE_OFFSET * (1f - exp(-distance / EDGE_DISTANCE_SCALE))

private const val MAX_EDGE_OFFSET = 0.42f
private const val EDGE_DISTANCE_SCALE = 0.55f
