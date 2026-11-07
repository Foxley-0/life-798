package com.life798.mini.ui.water

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.Platform
import com.life798.mini.core.PlatformSessionStore
import com.life798.mini.core.water.PangguaiWaterStore
import com.life798.mini.core.water.PgWaterDevice
import com.life798.mini.core.water.PgWaterPhase
import com.life798.mini.core.water.PgWaterSession
import com.life798.mini.net.PangguaiWaterApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 胖乖用水独立状态。全部在 `pangguai-` 命名空间，不进 798 的 UiState.session。 */
data class PgWaterState(
    val device: PgWaterDevice? = null,
    val phase: PgWaterPhase = PgWaterPhase.IDLE,
    /** 已扫码、等待用户点启动；MainActivity 用它决定 push 哪个路由。 */
    val pending: Boolean = false,
    val orderNo: String = "",
    val startedAt: Long? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val busy: Boolean = false,
    val stopping: Boolean = false,
) {
    val running: Boolean
        get() = phase == PgWaterPhase.STARTING ||
            phase == PgWaterPhase.RUNNING ||
            phase == PgWaterPhase.STOPPING

    /** 投影成与 798 共用的视图数据；两个页面组件因此无需知道平台。 */
    fun toWaterUi(): WaterUi = WaterUi(
        deviceName = device?.name ?: "正在识别设备…",
        deviceCode = device?.deviceId.orEmpty(),
        busy = busy,
        stopping = stopping,
        running = running,
        closed = phase == PgWaterPhase.ENDED,
        startedAt = startedAt,
        message = message,
        messageIsError = messageIsError,
    )
}

/**
 * 胖乖用水（饮水机 / 淋浴）独立 ViewModel。
 *
 * 直接读 `PlatformSessionStore` 的 `Platform.PANGGUAI` token，不依赖 AppViewModel；
 * 会话、常用设备、运行态都落在 `pangguai-water-*` key 里，与 798 物理隔离。
 *
 * 链路：选后付渠道(15) → unlock → 轮询 → 主动结束 → 等 status=5 → 零用量跳过后付同步。
 * **不引支付宝 SDK、不传 prepayParam、不创建 trade 订单** —— 扣款由服务端免密代扣完成。
 */
class PangguaiWaterViewModel(app: Application) : AndroidViewModel(app) {

    private val api = PangguaiWaterApi(app)
    private val store = PangguaiWaterStore(app)
    private val sessions = PlatformSessionStore(app)

    private val _state = MutableStateFlow(PgWaterState())
    val state: StateFlow<PgWaterState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { restore() }
    }

    private suspend fun token(): String =
        sessions.flow(Platform.PANGGUAI).first().token

    /** 冷启动恢复未结束的会话。 */
    private suspend fun restore() {
        val list = store.sessions()
        val s = list.lastOrNull() ?: return
        val d = store.devices().firstOrNull { it.goodsId == s.goodsId } ?: return
        _state.value = PgWaterState(
            device = d,
            phase = PgWaterPhase.RUNNING,
            pending = true,
            orderNo = s.orderNo,
            startedAt = s.startedAt,
        )
        startPolling()
    }

    /** 扫到 `SN` 后只读预检：识别设备 → 详情 → SKU。不发任何写请求。 */
    fun onScan(sn: String) {
        pollJob?.cancel()
        _state.value = PgWaterState(
            phase = PgWaterPhase.LOADING,
            pending = true,
            busy = true,
            message = "正在识别设备…",
        )
        viewModelScope.launch {
            val token = token()
            if (token.isBlank()) return@launch fail("胖乖登录态已失效，请到设置页重新登录")
            val scan = when (val r = api.scan(token, sn)) {
                is ApiResult.Ok -> r.data
                is ApiResult.Err -> return@launch fail(r.message)
            }
            if (scan.categoryCode !in setOf("04", "08")) {
                return@launch fail("这不是饮水机 / 淋浴设备（${scan.categoryName}）")
            }
            val detail = when (val r = api.details(token, scan.goodsId)) {
                is ApiResult.Ok -> r.data
                is ApiResult.Err -> return@launch fail(r.message)
            }
            val sku = when (val r = api.skus(token, scan.goodsId)) {
                is ApiResult.Ok -> r.data
                is ApiResult.Err -> return@launch fail(r.message)
            }
            val device = PgWaterDevice(
                goodsId = scan.goodsId,
                sn = sn,
                deviceId = detail.deviceId,
                imei = detail.imei,
                name = detail.name,
                orgName = detail.orgName,
                categoryCode = scan.categoryCode,
                categoryName = scan.categoryName,
                routeShopId = scan.routeShopId,
                skuId = sku.skuId,
                skuName = sku.name,
                supportAppUserFinish = detail.supportAppUserFinish,
            )
            store.saveDevice(device)
            AppLog.i(
                "PangguaiWater",
                "预检完成 goodsId=${device.goodsId} name=${device.name} " +
                    "deviceId=${device.deviceId} category=${device.categoryCode} " +
                    "supportAppUserFinish=${device.supportAppUserFinish}"
            )
            _state.value = _state.value.copy(
                device = device,
                phase = PgWaterPhase.READY,
                pending = true,
                busy = false,
                message = null,
            )
        }
    }

    /** 点「启 动」：选后付渠道 → unlock → 落盘 → 开始轮询。 */
    fun start() {
        val cur = _state.value
        val device = cur.device ?: return
        if (cur.busy || cur.running || cur.phase == PgWaterPhase.ENDED) return
        _state.value = cur.copy(
            phase = PgWaterPhase.STARTING,
            busy = true,
            message = "正在启动设备…",
            messageIsError = false,
        )
        viewModelScope.launch {
            val token = token()
            if (token.isBlank()) return@launch fail("胖乖登录态已失效，请到设置页重新登录")
            // 抓包里启动前必调；失败不阻断演示，只记日志。
            when (val r = api.selectAfterPay(token)) {
                is ApiResult.Err -> AppLog.w("PangguaiWater", "选定后付渠道失败: ${r.message}")
                is ApiResult.Ok -> Unit
            }
            val unlock = when (val r = api.unlock(token, device.skuId, device.categoryCode)) {
                is ApiResult.Ok -> r.data
                is ApiResult.Err -> return@launch fail(r.message)
            }
            if (unlock.msgId.isNotBlank()) api.message(token, unlock.msgId)
            val session = PgWaterSession(
                goodsId = device.goodsId,
                skuId = device.skuId,
                name = device.name,
                deviceId = device.deviceId,
                categoryCode = device.categoryCode,
                orderNo = unlock.orderNo,
                msgId = unlock.msgId,
            )
            store.saveSessions(store.sessions().filterNot { it.goodsId == device.goodsId } + session)
            AppLog.i("PangguaiWater", "启动成功 orderNo=${unlock.orderNo} msgId=${unlock.msgId}")
            _state.value = _state.value.copy(
                phase = PgWaterPhase.RUNNING,
                orderNo = unlock.orderNo,
                startedAt = session.startedAt,
                busy = false,
                message = null,
            )
            startPolling()
        }
    }

    /** 点「关闭设备」：不看 supportAppUserFinish，一律发结束指令，实测后端是否放行。 */
    fun stop() {
        val cur = _state.value
        val device = cur.device ?: return
        if (cur.stopping || cur.orderNo.isBlank() || cur.phase == PgWaterPhase.ENDED) return
        _state.value = cur.copy(stopping = true, message = "正在关闭设备…", messageIsError = false)
        viewModelScope.launch {
            val token = token()
            if (token.isBlank()) return@launch fail("胖乖登录态已失效，请到设置页重新登录")
            when (val r = api.end(token, device.goodsId, device.categoryCode)) {
                is ApiResult.Ok -> {
                    AppLog.i("PangguaiWater", "结束指令返回 data=${r.data}（不等于设备已结束）")
                    _state.value = _state.value.copy(
                        phase = PgWaterPhase.STOPPING,
                        message = "已发送结束指令，等待设备确认…",
                    )
                    // 不在这里判完成，等轮询拿到 status=5 / workStatus=1
                }
                is ApiResult.Err -> {
                    AppLog.w("PangguaiWater", "结束指令被拒: ${r.message}")
                    _state.value = _state.value.copy(
                        stopping = false,
                        phase = PgWaterPhase.RUNNING,
                        message = r.message,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 1.5s 轮询；只有 identify 匹配本次订单且 status=5 / workStatus=1 才算结束。 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val token = token()
            var failures = 0
            while (isActive && _state.value.running) {
                delay(POLL_INTERVAL_MS)
                val cur = _state.value
                val device = cur.device ?: break
                when (val r = api.sync(token, device.skuId)) {
                    is ApiResult.Ok -> {
                        failures = 0
                        val reading = r.data
                        val ended = reading.status == 5 && reading.workStatus == 1 &&
                            reading.orderNo == cur.orderNo
                        if (ended) {
                            finalize(token, cur.orderNo, reading.amount)
                            break
                        }
                    }
                    is ApiResult.Err -> {
                        failures++
                        if (failures >= MAX_POLL_FAILURES) {
                            _state.value = _state.value.copy(
                                message = "设备状态查询失败：${r.message}",
                                messageIsError = true,
                            )
                            failures = 0
                        }
                    }
                }
            }
        }
    }

    /** 设备确认结束后：零用量跳过后付同步，非零用量发两个订单同步 POST（不是支付）。 */
    private suspend fun finalize(token: String, orderNo: String, amount: String?) {
        val zero = amount?.toDoubleOrNull() == 0.0
        if (!zero) {
            when (val r = api.createAfterPay(token, orderNo)) {
                is ApiResult.Err -> AppLog.w("PangguaiWater", "后付创建失败: ${r.message}")
                is ApiResult.Ok -> when (val s = api.syncOrder(token, orderNo)) {
                    is ApiResult.Err -> AppLog.w("PangguaiWater", "订单同步失败: ${s.message}")
                    is ApiResult.Ok -> Unit
                }
            }
        }
        store.saveSessions(emptyList())
        _state.value = _state.value.copy(
            phase = PgWaterPhase.ENDED,
            stopping = false,
            busy = false,
            message = "本次用水已结束",
            messageIsError = false,
        )
        AppLog.i("PangguaiWater", "已结束 orderNo=$orderNo 零用量=$zero")
    }

    /** 离开启动页时清预检；运行中不清，保留会话。 */
    fun dismiss() {
        val cur = _state.value
        if (cur.running || cur.phase == PgWaterPhase.ENDED) return
        pollJob?.cancel()
        _state.value = PgWaterState()
    }

    private fun fail(message: String) {
        AppLog.w("PangguaiWater", "失败: $message")
        _state.value = _state.value.copy(
            busy = false,
            stopping = false,
            phase = PgWaterPhase.READY,
            message = message,
            messageIsError = true,
        )
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_POLL_FAILURES = 5
    }
}
