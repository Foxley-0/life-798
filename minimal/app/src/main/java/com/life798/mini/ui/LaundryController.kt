package com.life798.mini.ui

import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.PangguaiLaundryStore
import com.life798.mini.core.laundry.LaundryPhase
import com.life798.mini.core.laundry.LaundrySession
import com.life798.mini.net.PangguaiWasherApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 胖乖洗衣运行态控制器。
 *
 * 状态机移植自 Mercury000/HUEL-yktwx 的 `LifeDevicesViewModel`（GPL-3.0，作者已授权），
 * 按本工程范围精简成「启动后 → 轮询订单 → 显示进度 → 服务端确认完成」：
 *
 * - 每 5 秒遍历仍在进行中的会话，读 `/order/detail`；
 * - `orderStatus=2` 运行中、`3` 完成、`1` 已关闭、`0` 待支付；
 * - **倒计时归零不判完成**：只有服务端 `3` 才标记 `Ended`；
 * - 用户手动移除后，迟到的轮询结果不会把记录加回来；
 * - 已完成 / 已关闭仍保留在列表里展示，由用户手动移除。
 *
 * 独立成类而不是塞进 AppViewModel：那边已经 1100+ 行且全部围绕 798。
 */
class LaundryController(
    private val scope: CoroutineScope,
    private val api: PangguaiWasherApi,
    private val store: PangguaiLaundryStore,
    private val tokenProvider: () -> String,
    private val emit: (List<LaundrySession>) -> Unit,
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
        // 立刻读一次，尽快拿到 completeTime，别让用户等 5 秒才看到倒计时
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

    private fun persist() {
        store.save(sessions)
        emit(sessions)
    }

    private fun schedule() {
        if (pollJob?.isActive == true) return
        if (sessions.none { it.running }) return
        pollJob = scope.launch {
            while (isActive && sessions.any { it.running }) {
                sessions.filter { it.running }.forEach { refresh(it.sessionId) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun refresh(sessionId: String) {
        val current = sessions.firstOrNull { it.sessionId == sessionId } ?: return
        val token = tokenProvider()
        if (token.isBlank()) return
        scope.launch {
            when (val r = api.orderDetail(token, current.orderNo)) {
                is ApiResult.Ok -> applyReading(sessionId, r.data)
                is ApiResult.Err -> AppLog.w("Laundry", "订单查询失败 ${current.orderNo}: ${r.message}")
            }
        }
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
        const val POLL_INTERVAL_MS = 5000L
    }
}
