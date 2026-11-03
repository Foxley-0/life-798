package com.life798.mini.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.BillStore
import com.life798.mini.core.Prefs
import com.life798.mini.core.UsageLedger
import com.life798.mini.net.WaterApi
import com.life798.mini.scan.QrParser
import com.life798.mini.scan.QrResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/**
 * 结算轮询：每 2 秒一次，最多 10 次。
 *
 * 抓包实测 6 秒左右能拿到结果，但设备网络差、后端队列堵的时候会更久，
 * 留到 20 秒，避免明明结完了却因为超时丢掉这一笔。
 */
private const val SETTLE_INTERVAL_MS = 2000L
private const val SETTLE_ATTEMPTS = 10

data class UiState(
    val ready: Boolean = false,
    val token: String = "",
    val phone: String = "",
    val deviceId: String = "",
    val captchaKey: String = "",
    val captchaImage: ByteArray? = null,
    val captchaLoading: Boolean = false,
    val captchaError: String? = null,
    val busy: Boolean = false,
    /** 正在关闭设备。和 [busy] 分开，两个按钮各自有自己的转圈。 */
    val stopping: Boolean = false,
    /** 设备是否正在出水。进页面时向服务端同步，启动/关闭成功后本地更新。 */
    val deviceRunning: Boolean = false,
    /** 关闭后正在等服务端结算。结算完成前拿不到本次用量。 */
    val settling: Boolean = false,
    /** 最近一次结算出的用量（升）。null 表示本次还没有有效结果。 */
    val lastUsageLiters: Double? = null,
    /** 账本版本号：每落一笔 +1，用量页拿它当刷新键。 */
    val ledgerRevision: Int = 0,
    /** 正在同步服务端账单（拉列表 + 补详情）。 */
    val billsSyncing: Boolean = false,
    /** 账单版本号：同步一次 +1，账本页拿它当刷新键。 */
    val billsRevision: Int = 0,
    /** 账单同步失败的原因。本地数据照常显示，只在页面顶部提一句。 */
    val billsError: String? = null,
    val message: String? = null,
    /** 配合 [message]：true 表示这是错误提示，界面上用错误色。 */
    val messageIsError: Boolean = false,
    val smsCountdown: Int = 0,
    val configured: Boolean = true
) {
    val loggedIn: Boolean get() = token.isNotBlank()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val api = WaterApi()
    private val ledger = UsageLedger(app)
    private val billStore = BillStore(app)

    private val _ui = MutableStateFlow(UiState(configured = api.isConfigured))

    /**
     * 本次用量的起点：扫码进页面时读到的累计出水值。
     *
     * 服务端的 `gene.out` 是设备装机以来的总量，不是本次用量，必须做差。
     * 每结算完一次就把它更新成结算后的值，这样同一页面连续接两次水也不会串。
     * 只在内存里活着，不落盘。
     */
    private var baselineOut: Double? = null

    /** 同一个码 3 秒内只提示一次，否则每帧识别都会触发一次 */
    private var lastRejectKey: String? = null
    private var lastRejectAt = 0L
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var smsTimer: Job? = null

    init {
        viewModelScope.launch {
            val token = prefs.token.first()
            val phone = prefs.phone.first()
            // 设备编号不落盘：每次启动 App 都必须重新扫码
            _ui.value = _ui.value.copy(
                ready = true,
                token = token,
                phone = phone
            )
            // 未登录才需要图形验证码；已登录直接进首页，不必白跑一次请求。
            if (token.isBlank()) {
                refreshCaptcha()
            }
        }
    }

    // ---------------- 登录 ----------------

    fun refreshCaptcha() {
        if (!api.isConfigured) {
            _ui.value = _ui.value.copy(
                captchaLoading = false,
                captchaImage = null,
                captchaError = "未配置 API 网关"
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(captchaLoading = true, captchaError = null)
            val key = randomKey()
            when (val r = api.captcha(key)) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "验证码已加载 ${r.data.size}B")
                    _ui.value = _ui.value.copy(
                        captchaLoading = false,
                        captchaKey = key,
                        captchaImage = r.data,
                        captchaError = null
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "验证码加载失败: ${r.message}")
                    _ui.value = _ui.value.copy(
                        captchaLoading = false,
                        captchaImage = null,
                        captchaError = r.message
                    )
                }
            }
        }
    }

    fun sendSms(phone: String, graphCode: String) {
        if (phone.isBlank()) return note("请输入手机号")
        if (graphCode.isBlank()) return note("请输入图形验证码")
        if (_ui.value.smsCountdown > 0) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.sendSms(phone.trim(), graphCode.trim(), _ui.value.captchaKey)) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "短信已发送 → ${AppLog.maskPhone(phone)}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = "验证码已发送",
                        messageIsError = false
                    )
                    startSmsCountdown()
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "发送短信失败: ${r.message}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = r.message,
                        messageIsError = true
                    )
                    refreshCaptcha()
                }
            }
        }
    }

    fun login(phone: String, smsCode: String) {
        if (phone.isBlank()) return note("请输入手机号")
        if (smsCode.isBlank()) return note("请输入短信验证码")

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.login(phone.trim(), smsCode.trim())) {
                is ApiResult.Ok -> {
                    val session = r.data
                    val uid = when (val info = api.viewInfo(session.token)) {
                        is ApiResult.Ok -> info.data
                        is ApiResult.Err -> {
                            AppLog.w("Login", "取 uid 失败: ${info.message}")
                            ""
                        }
                    }
                    prefs.saveSession(session.token, uid, session.phone)
                    AppLog.i(
                        "Login",
                        "登录成功 uid=$uid token=${AppLog.mask(session.token)}"
                    )
                    _ui.value = _ui.value.copy(
                        busy = false,
                        token = session.token,
                        phone = session.phone,
                        message = null
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "登录失败: ${r.message} (code=${r.code})")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = r.message,
                        messageIsError = true
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            prefs.clearSession()
            // 设备号也要清：否则重新登录后可能对着上一台设备直接点启动
            _ui.value = _ui.value.copy(
                token = "",
                deviceId = "",
                message = null,
                messageIsError = false
            )
            refreshCaptcha()
        }
    }

    // ---------------- 账单 ----------------

    /**
     * 从服务端同步账单。
     *
     * 分两步：
     * 1. 拉第一页列表合并入库 —— 时间、金额、设备名立刻可显示
     * 2. 本地缺升数的账单逐条补详情 —— 列表接口不带用量，只有详情有
     *
     * 已经补过详情的不会重复拉，所以日常只多 1~2 次请求。
     * 失败时不清本地数据，只把原因放进 [UiState.billsError]。
     */
    fun syncBills() {
        val token = _ui.value.token
        if (token.isBlank()) return
        if (_ui.value.billsSyncing) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(billsSyncing = true, billsError = null)

            when (val list = api.billList(token, page = 0)) {
                is ApiResult.Err -> {
                    AppLog.w("Bill", "账单列表同步失败: ${list.message}")
                    _ui.value = _ui.value.copy(billsSyncing = false, billsError = list.message)
                    return@launch
                }

                is ApiResult.Ok -> {
                    val added = billStore.merge(list.data)
                    AppLog.i("Bill", "账单列表 ${list.data.size} 条，新增 $added")

                    var filled = 0
                    var failed = 0
                    for (id in billStore.missingDetail()) {
                        when (val detail = api.billDetail(token, id)) {
                            is ApiResult.Ok -> {
                                val bill = detail.data
                                if (bill.liters != null) {
                                    billStore.updateDetail(id, bill.liters, bill.secs, bill.deviceName)
                                    filled++
                                }
                            }
                            is ApiResult.Err -> {
                                failed++
                                AppLog.w("Bill", "详情失败 id=$id: ${detail.message}")
                            }
                        }
                    }
                    AppLog.i("Bill", "补详情 $filled 条，失败 $failed 条")

                    _ui.value = _ui.value.copy(
                        billsSyncing = false,
                        billsRevision = _ui.value.billsRevision + 1,
                        billsError = null
                    )
                }
            }
        }
    }

    // ---------------- 设备 ----------------

    /**
     * 扫码结果处理。
     * @return true 表示已解析出慧生活798 的设备编号（调用方可以离开扫码页）
     */
    fun onQrDecoded(raw: String): Boolean {
        AppLog.i("Scan", "二维码原文: ${raw.take(120)}")

        return when (val r = QrParser.parse(raw)) {
            is QrResult.Ok -> {
                AppLog.i("Scan", "设备编号=${r.deviceId} host=${r.host.ifBlank { "(纯编号)" }}")
                // 设备编号只活在本次会话，不写盘。
                // deviceRunning 先归零，避免沿用上一台设备的状态，再向服务端核对。
                _ui.value = _ui.value.copy(
                    deviceId = r.deviceId,
                    deviceRunning = false,
                    message = null
                )
                syncDeviceStatus(r.deviceId)
                true
            }

            is QrResult.WrongPlatform -> {
                AppLog.w("Scan", "非慧生活798设备 host=${r.host}")
                rejectOnce(r.host, "非慧生活798设备，请使用对应 App 扫码")
                false
            }

            is QrResult.Unrecognized -> {
                AppLog.w("Scan", "无法识别，原文长度=${r.raw.length}")
                rejectOnce(r.raw, "无法识别的二维码：${r.raw.take(30)}")
                false
            }
        }
    }

    /**
     * 清除设备编号。
     *
     * 按业务要求：离开启动页后编号即作废，必须重新扫码，避免误用上一台设备。
     */
    fun clearDevice() {
        AppLog.i("Device", "清除设备编号 did=${_ui.value.deviceId}")
        // 基线跟着设备走：换设备后旧基线毫无意义，留着只会算出错误的差值
        baselineOut = null
        _ui.value = _ui.value.copy(
            deviceId = "",
            deviceRunning = false,
            stopping = false,
            settling = false,
            lastUsageLiters = null,
            message = null,
            messageIsError = false
        )
    }

    fun startDevice() {
        val s = _ui.value
        if (s.deviceId.isBlank()) return note("请先扫码添加设备")
        if (s.busy || s.stopping) return
        if (s.settling) return note("上一笔还在结算，请稍候")
        if (s.deviceRunning) return note("设备已经在运行中")

        viewModelScope.launch {
            // 上一笔的用量结果不该带到这一笔上
            _ui.value = _ui.value.copy(busy = true, message = null, lastUsageLiters = null)
            when (val r = api.devStart(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    AppLog.i("Device", "启动成功 did=${s.deviceId}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        deviceRunning = true,
                        message = "已启动，请在设备上使用",
                        messageIsError = false
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "启动失败 did=${s.deviceId}: ${r.message} code=${r.code}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = r.message,
                        messageIsError = true
                    )
                    if (r.code == -99) logout()
                }
            }
        }
    }

    /**
     * 关闭设备。
     *
     * 关闭后账单由设备本体结算，App 侧只负责把出水停掉并刷新状态，
     * 不做计时、不做账单查询 —— 和启动一样保持「启动器」的定位。
     */
    fun stopDevice() {
        val s = _ui.value
        if (s.deviceId.isBlank()) return note("请先扫码添加设备")
        if (s.busy || s.stopping) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(stopping = true, message = null)
            when (val r = api.devEnd(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    AppLog.i("Device", "关闭成功 did=${s.deviceId}")
                    // 水是停了，但服务端还没结完账：这时候查 out 拿到的还是旧值，
                    // 所以先标成「结算中」，由 awaitSettlement 轮询等结果。
                    _ui.value = _ui.value.copy(
                        stopping = false,
                        deviceRunning = false,
                        settling = true,
                        message = "已关闭，正在结算",
                        messageIsError = false
                    )
                    awaitSettlement(s.deviceId)
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "关闭失败 did=${s.deviceId}: ${r.message} code=${r.code}")
                    _ui.value = _ui.value.copy(
                        stopping = false,
                        message = r.message,
                        messageIsError = true
                    )
                    if (r.code == -99) logout()
                }
            }
        }
    }

    /**
     * 向服务端核对设备出水状态。
     *
     * 进入启动页时跑一次：用户可能从别处已经开过水，或者上次进程被杀导致本地状态丢了。
     * 失败不打扰用户 —— 保持当前 UI，用户仍可以点按钮尝试。
     */
    private fun syncDeviceStatus(expectedDeviceId: String) {
        val s = _ui.value
        if (expectedDeviceId.isBlank() || s.token.isBlank()) return

        viewModelScope.launch {
            when (val r = api.devStatus(s.token, expectedDeviceId)) {
                is ApiResult.Ok -> {
                    val out = r.data.outLiters
                    // 第一次拿到有效累计值时记基线。后续轮询不能覆盖，
                    // 否则每次查询都把起点往后推，差值永远是 0。
                    if (baselineOut == null && out > 0.0) {
                        baselineOut = out
                        AppLog.i("Device", "用量基线 did=$expectedDeviceId out=$out")
                    }
                    AppLog.i(
                        "Device",
                        "状态同步 did=$expectedDeviceId running=${r.data.running} " +
                            "raw=${r.data.rawStatus} out=$out"
                    )
                    // 设备号变了、或者用户正在点按钮，说明这次结果已经过期，丢掉
                    val cur = _ui.value
                    if (cur.deviceId == expectedDeviceId && !cur.busy && !cur.stopping && !cur.settling) {
                        _ui.value = cur.copy(deviceRunning = r.data.running)
                    }
                }

                is ApiResult.Err -> AppLog.w("Device", "状态查询失败: ${r.message}")
            }
        }
    }

    /**
     * 关闭后等结算。
     *
     * 服务端不是在 dev/end 返回时就结完账的：抓包实测关闭后 3 秒 `out` 还是旧值、
     * `status` 还是 1，6 秒后才跳到新值并转成 99。所以每 2 秒查一次、最多 6 次，
     * 一旦 status 变 99 或 out 有增长就收工。
     */
    private fun awaitSettlement(deviceId: String) {
        viewModelScope.launch {
            repeat(SETTLE_ATTEMPTS) { attempt ->
                delay(SETTLE_INTERVAL_MS)
                val token = _ui.value.token
                if (token.isBlank()) return@launch

                when (val r = api.devStatus(token, deviceId)) {
                    is ApiResult.Ok -> {
                        val out = r.data.outLiters
                        val base = baselineOut
                        val grew = base != null && out > base + 0.0005
                        val settled = r.data.rawStatus == 99 || grew
                        AppLog.i(
                            "Device",
                            "结算轮询 #${attempt + 1} status=${r.data.rawStatus} " +
                                "out=$out base=$base settled=$settled"
                        )
                        if (settled) {
                            finishSettlement(out)
                            return@launch
                        }
                    }

                    is ApiResult.Err -> AppLog.w("Device", "结算轮询失败: ${r.message}")
                }
            }
            // 20 秒还没结完：不硬猜数字，把结论交回用户
            AppLog.w("Device", "结算超时 did=$deviceId，本次未记账")
            _ui.value = _ui.value.copy(
                settling = false,
                message = "已关闭，结算结果稍后可在设备上查看"
            )
        }
    }

    /** 结算完成：算差值、落账、把基线推到新值。 */
    private fun finishSettlement(out: Double) {
        val base = baselineOut
        // 没有基线就算不出这一笔用了多少。宁可空着也不要把累计值当本次用量记进去。
        if (base == null) {
            AppLog.w("Device", "结算完成但没有基线 out=$out，本次未记账")
            baselineOut = out
            _ui.value = _ui.value.copy(
                settling = false,
                lastUsageLiters = null,
                message = "已关闭，本次未能取到用量",
                messageIsError = false
            )
            return
        }

        val used = if (out >= base) out - base else 0.0
        // 这一笔的终点就是下一笔的起点
        baselineOut = out
        if (used > 0.0) ledger.append(liters = used)

        AppLog.i(
            "Device",
            "结算完成 base=$base out=$out used=${String.format(Locale.CHINA, "%.3f", used)}L"
        )
        _ui.value = _ui.value.copy(
            settling = false,
            lastUsageLiters = used,
            ledgerRevision = if (used > 0.0) _ui.value.ledgerRevision + 1 else _ui.value.ledgerRevision,
            message = if (used > 0.0) "已关闭，本次用量 ${liters(used)} 升" else "已关闭，本次未用水",
            messageIsError = false
        )
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // ---------------- 内部 ----------------

    private fun startSmsCountdown() {
        smsTimer?.cancel()
        smsTimer = viewModelScope.launch {
            for (i in 60 downTo 1) {
                _ui.value = _ui.value.copy(smsCountdown = i)
                delay(1000)
            }
            _ui.value = _ui.value.copy(smsCountdown = 0)
        }
    }

    private fun note(msg: String) {
        _ui.value = _ui.value.copy(message = msg, messageIsError = true)
        // 系统 Toast：样式和位置由 ROM 决定（vivo 上是白卡+图标，悬浮在底栏上方）
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 扫码被拒绝时的提示，带 3 秒冷却。
     *
     * 不加冷却的话：码一直对着镜头，每秒 30 帧都会识别到同一个码，
     * 就会每秒推 30 条提示。
     */
    private fun rejectOnce(key: String, msg: String) {
        val now = System.currentTimeMillis()
        if (key == lastRejectKey && now - lastRejectAt < 3000L) return
        lastRejectKey = key
        lastRejectAt = now
        note(msg)
    }

    private fun randomKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(10) {
            repeat(10) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        smsTimer?.cancel()
    }
}
