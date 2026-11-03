package com.life798.mini.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 底栏与 Pager 之间的唯一状态源。
 *
 * 为什么不用 `pagerState.currentPage` 直接当高亮：
 *
 * 1. **点击不跟手** —— `currentPage` 是「离 snap 位置最近的页」，动画要滚过半页才翻。
 *    点击后手指已经抬起来了，高亮却要等半页之后才跳，看着像没反应。
 *    这里点下去立刻把 [selectedPage] 置成目标页，高亮瞬间到位，Pager 在后面追。
 *
 * 2. **跨页会闪中间项** —— 0→2 的动画必然经过第 1 页，`currentPage` 中途变成 1，
 *    高亮就闪一下「用量」再跳到「设置」。这里动画期间不读 `currentPage`
 *    （[isNavigating] 期间 [syncPage] 直接返回），只在动画收尾时校验一次。
 *
 * 3. **连点丢** —— 如果高亮由 Pager 回写，两次点击之间 Pager 可能刚好把状态写成
 *    用户接下来要点的那个页，第二次点击被当成「已经在目标页」直接吞掉。
 *    这里点击无条件推进 [selectedPage]，取消上一次动画后按**当前位置**重新算距离。
 *
 * 滚动的实现用 `animateScrollBy(像素距离)` 而不是 `animateScrollToPage(页号)`：
 * 后者由 Pager 内部的 spring 驱动，被 cancel 后会留下半截惯性，连点时两段动画打架。
 * 前者是一段固定时长、EaseInOut 的位移，随时打断都干净。
 */
internal class MainTabsPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope
) {
    /** 底栏高亮读这个，不是 pagerState.currentPage。 */
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    /** 点击触发的动画进行中。期间不允许 [syncPage] 覆盖 [selectedPage]。 */
    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetPage: Int) {
        // 高亮已经在目标页、Pager 也停在目标页 → 没什么可做。
        // 两个条件都要满足：上一次动画被手势打断时，高亮可能已经是目标页，
        // 但 Pager 还停在半路，这时候必须滚过去。
        if (targetPage == selectedPage && pagerState.currentPage == targetPage) return

        navJob?.cancel()
        selectedPage = targetPage
        isNavigating = true

        val layoutInfo = pagerState.layoutInfo
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        // 按「当前实际位置」算距离：上一次动画被打断停在半路时，页号差是不准的
        val distanceInPages =
            targetPage - pagerState.currentPage - pagerState.currentPageOffsetFraction
        val scrollPixels = distanceInPages * pageSize
        // 相邻页 300ms，跨两页 300ms，三页 400ms —— 距离越远给的时间略多一点，
        // 但不要让用户等出「卡住」的感觉
        val duration = 100 * abs(targetPage - pagerState.currentPage).coerceAtLeast(2) + 100

        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.animateScrollBy(
                    value = scrollPixels,
                    animationSpec = tween(easing = EaseInOut, durationMillis = duration)
                )
            } finally {
                // 被新的一次点击取消时，让位给新动画，不要动 selectedPage
                if (navJob == currentJob) {
                    isNavigating = false
                    // 动画因为手势打断等原因没到位 → 以 Pager 的实际页为准
                    if (pagerState.currentPage != targetPage) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    /** 手指滑动时同步高亮。由 `LaunchedEffect(pagerState.currentPage)` 调用。 */
    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }

    /** 登出 / 重置时用：取消动画并回到第 0 页。 */
    fun reset() {
        navJob?.cancel()
        navJob = null
        isNavigating = false
        selectedPage = 0
    }
}
