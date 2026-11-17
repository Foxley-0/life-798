package com.life798.mini.ui

import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.PangguaiLaundryStore
import com.life798.mini.core.Platform
import com.life798.mini.core.isAuthError
import com.life798.mini.core.laundry.LaundryPhase
import com.life798.mini.core.laundry.LaundrySession
import com.life798.mini.net.PangguaiWasherApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [LaundryController.refreshNow] 的结果。
 *
 * 必须区分「失效」和「普通失败」：前者要停轮询、标账号失效、弹重新登录，
 * 后者只需把原因显示出来。以前混成一个 `String?`，两者没法分辨。
 */
sealed interface LaundryRefresh {
    data object Ok : LaundryRefresh
    data class AuthExpired(val code: Int) : LaundryRefresh
    data class Failed(val message: String) : LaundryRefresh
}

/**
 * 胖乖洗衣运行态控制器。
 *
 * 状态机移植自 Mercury000/HUEL-yktwx 的 `LifeDevicesViewModel`（GPL-3.0，作者已授权），
 * 按本工程范围精简成「启动后 → 轮询订单 → 显示进度 → 服务端确认完成」：
 *
 * - 每 20 秒遍历仍在进行中的会话，读 `/order/detail`；
 * - `orderStatus=2` 运行中、`3` 完成、`1` 已关闭、`0` 待支付；
 * - **倒计时归零不判完成**：只有服务端 `3` 才标记 `Ended`；
 * - 用户手动移除后，迟到的轮询结果不会把记录加回来；
 * - 已完成 / 已关闭仍保留在列表里展示，由用户手动移除。
 *
 * 独立成类而不是塞进 AppViewModel：那边已经 1100+ 行且全部围绕 798。
 *
 * ## token 必须挂起等待
 *
 * `restore()` 由 AppViewModel 字段初始化时调用，比账号从磁盘载入还早，那一瞬
 * [tokenProvider] 返回空串。旧实现直接 `return` 跳过且不打日志，于是轮询空转、
 * 一次请求都没发出去，卡片永久停在「洗衣中」。现在改成 [awaitToken] 挂起等待，
 * 与饮水 `PangguaiWaterViewModel.token()` 的思路一致。
 */
class LaundryController(
    private val scope: CoroutineScope,
    private val api: PangguaiWasherApi,
    private val store: PangguaiLaundryStore,
    private val tokenProvider: () -> String,
    private val emit: (List<LaundrySession>) -> Unit,
    /** 轮询或查询命中「登录失效」时回调，交给 ViewModel 统一置 EXPIRED 并停轮询。 */
    private val onAuthExpired: (Int) -> Unit = {},
) {

    private var sessions: List<LaundrySession> = emptyList()
    private var pollJob: Job? = null

    /** 冷启动恢复：读磁盘、推给界面、有进行中的就开轮询。 */
    fun restore() {
        sessions = store.load()
        emit(sessions)
        schedule()
    }

    /** 启动成功后落一条新会话。 */
    fun add(session: LaundrySession) {
        sessions = sessions.filterNot { it.orderNo == session.orderNo } + session
        persist()
        schedule()
        // 立刻读一次，尽快拿到 completeTime，别让用户等一轮才看到倒计时
        refresh(session.sessionId)
    }

    /** 用户点 × 移除；只删本地记录，不取消服务端订单。 */
    fun dismiss(sessionId: String) {
        sessions = sessions.filterNot { it.sessionId == sessionId }
        persist()
    }

    fun byId(sessionId: String): LaundrySession? =
        sessions.firstOrNull { it.sessionId == sessionId }

    fun snapshot(): List<LaundrySession> = sessions

    /**
     * 停轮询。登录失效、退出账号时调用。
     *
     * 只停，不动 [sessions] —— token 过期不改变机器还在洗的事实，
     * 卡片该继续显示；要清记录用 [clear]。
     */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 恢复轮询。重新登录成功后调用，否则只能重启 App 才会再查。 */
    fun resume() {
        schedule()
    }

    /** 退出胖乖：停轮询 + 清空记录，和 798 退出时清会话记录一个标准。 */
    fun clear() {
        stop()
        sessions = emptyList()
        persist()
    }

    private fun persist() {
        store.save(sessions)
        emit(sessions)
    }

    private fun schedule() {
        if (pollJob?.isActive == true) return
        if (sessions.none { it.running }) return
        pollJob = scope.launch {
            var failures = 0
            while (isActive && sessions.any { it.running }) {
                val token = awaitToken()
                if (token.isBlank()) {
                    // 30 秒还拿不到登录态：不空转，隔一会儿再从头试
                    AppLog.w("Laundry", "等待胖乖登录态超时，稍后重试")
                    delay(TOKEN_GIVEUP_DELAY_MS)
                    continue
                }
                val pending = sessions.filter { it.running }
                var anyOk = false
                // 串行查：sessions 是单份可变状态，并发回写会互相覆盖
                for (s in pending) {
                    if (!isActive) break
                    when (val r = api.orderDetail(token, s.orderNo)) {
                        is ApiResult.Ok -> {
                            anyOk = true
                            applyReading(s.sessionId, r.data)
                        }

                        is ApiResult.Err -> {
                            // 登录失效：继续轮询没有意义，每 20 秒发一次注定失败的请求
                            // 只会让服务端限流。先停、再交给统一出口置 EXPIRED。
                            if (isAuthError(Platform.PANGGUAI, r.code)) {
                                AppLog.w(
                                    "Laundry",
                                    "轮询命中登录失效 code=${r.code}，停止轮询"
                                )
                                onAuthExpired(r.code)
                                return@launch
                            }
                            AppLog.w("Laundry", "轮询失败 ${s.orderNo.takeLast(8)}: ${r.message}")
                        }
                    }
                }
                failures = if (anyOk) 0 else failures + 1
                if (failures > 0) {
                    AppLog.w("Laundry", "连续失败 $failures 次，${backoffMillis(failures)}ms 后重试")
                }
                delay(if (failures == 0) POLL_INTERVAL_MS else backoffMillis(failures))
            }
            AppLog.i("Laundry", "没有进行中的洗衣会话，停止轮询")
        }
    }

    private fun refresh(sessionId: String) {
        scope.launch { refreshNow(sessionId) }
    }

    /**
     * 拉一次订单并写回状态。给状态页的「刷新状态」用，也让启动后第一拉能立刻发出。
     */
    suspend fun refreshNow(sessionId: String): LaundryRefresh {
        val current = sessions.firstOrNull { it.sessionId == sessionId }
            ?: return LaundryRefresh.Failed("订单已移除")
        val token = awaitToken()
        if (token.isBlank()) return LaundryRefresh.Failed("登录态未就绪，请稍后再试")
        return when (val r = api.orderDetail(token, current.orderNo)) {
            is ApiResult.Ok -> {
                applyReading(sessionId, r.data)
                LaundryRefresh.Ok
            }

            is ApiResult.Err -> {
                if (isAuthError(Platform.PANGGUAI, r.code)) {
                    AppLog.w("Laundry", "订单查询命中登录失效 code=${r.code}，停止轮询")
                    stop()
                    onAuthExpired(r.code)
                    LaundryRefresh.AuthExpired(r.code)
                } else {
                    AppLog.w("Laundry", "订单查询失败 ${current.orderNo}: ${r.message}")
                    LaundryRefresh.Failed(r.message)
                }
            }
        }
    }

    /** 挂起直到登录态就绪；最多等 30 秒（每秒探一次），与饮水的 token() 等价。 */
    private suspend fun awaitToken(): String {
        repeat(TOKEN_WAIT_ATTEMPTS) { i ->
            val t = tokenProvider()
            if (t.isNotBlank()) {
                if (i > 0) AppLog.i("Laundry", "登录态就绪（等待 ${i}s）")
                return t
            }
            delay(TOKEN_WAIT_INTERVAL_MS)
        }
        return ""
    }

    private fun applyReading(sessionId: String, d: com.life798.mini.core.laundry.LaundryReading) {
        val current = sessions.firstOrNull { it.sessionId == sessionId } ?: return
        // 身份校验：订单号与设备对不上就不更新，避免串到别的会话
        if (d.orderNo != current.orderNo ||
            (d.deviceId.isNotBlank() && d.deviceId != current.deviceId)
        ) return

        val phase = when (d.status) {
            3 -> LaundryPhase.Ended
            1 -> if (current.phase == LaundryPhase.Starting) LaundryPhase.Closed else current.phase
            2 -> LaundryPhase.Running
            else -> current.phase
        }
        if (phase != current.phase) {
            AppLog.i(
                "Laundry",
                "订单 ${current.orderNo.takeLast(8)} 状态 ${current.phase} → $phase" +
                    if (phase == LaundryPhase.Ended) "（服务端确认完成）" else ""
            )
        }
        val next = current.copy(
            phase = phase,
            expectedEndMillis = d.expectedEndMillis ?: current.expectedEndMillis,
            finishedAtMillis = d.finishedAtMillis ?: current.finishedAtMillis,
            startedAtMillis = d.paidAtMillis ?: current.startedAtMillis,
            createdAtMillis = d.createdAtMillis ?: current.createdAtMillis,
            minutes = d.minutes ?: current.minutes,
            deviceName = d.deviceName.ifBlank { current.deviceName },
            shopName = d.shopName.ifBlank { current.shopName },
            skuName = d.program.ifBlank { current.skuName },
        )
        // 已被用户移除的记录，迟到响应不能恢复
        if (sessions.none { it.sessionId == sessionId }) return
        sessions = sessions.map { if (it.sessionId == sessionId) next else it }
        persist()
    }

    private companion object {
        /** 首页只看「有没有结束」，20 秒足够；一台一个请求，不做批量。 */
        const val POLL_INTERVAL_MS = 20_000L
        const val TOKEN_WAIT_INTERVAL_MS = 1_000L
        const val TOKEN_WAIT_ATTEMPTS = 30
        const val TOKEN_GIVEUP_DELAY_MS = 30_000L

        /** 20 → 30 → 40 秒封顶，断网时不硬刷。 */
        fun backoffMillis(failures: Int): Long =
            POLL_INTERVAL_MS + (failures - 1).coerceIn(0, 2) * 10_000L
    }
}
