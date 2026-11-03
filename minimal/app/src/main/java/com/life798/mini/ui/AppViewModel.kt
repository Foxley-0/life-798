package com.life798.mini.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.BillStore
import com.life798.mini.core.DeviceSession
import com.life798.mini.core.DeviceSessionStore
import com.life798.mini.core.Prefs
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
import kotlin.random.Random

/**
 * 关闭设备后等服务端结算的时间。
 *
 * 抓包实测：关闭后 3 秒 `out` 还是旧值，6 秒才跳。等到 8 秒再拉账单，
 * 这样用户关完水切到「用量」页就能看到这一笔，不用手动下拉。
 */
private const val BILL_SETTLE_DELAY_MS = 8000L

/**
 * 启动成功后、读 `gene.time` 之前的等待。
 *
 * `dev/start` 只是把指令下发下去，设备真正转成 `status=1` 有几百毫秒延迟。
 * 立刻查会拿到 `status=99` 且 `time` 字段缺失，会话就失去了身份证 ——
 * 之后冷启动对账只能靠「还在出水」这一条弱条件判断。
 */
private const val SESSION_FINGERPRINT_DELAY_MS = 1200L

data class UiState(
    val ready: Boolean = false,
    val token: String = "",
    val phone: String = "",
    val deviceId: String = "",
    /** 设备名，来自 `/ui/app/dev/home`。拿不到时为空，界面退化成显示编号。 */
    val deviceName: String = "",
    val captchaKey: String = "",
    val captchaImage: ByteArray? = null,
    val captchaLoading: Boolean = false,
    val captchaError: String? = null,
    /** 正在启动设备。 */
    val busy: Boolean = false,
    /** 正在关闭设备。和 [busy] 分开，两个按钮各自有自己的转圈。 */
    val stopping: Boolean = false,
    /**
     * 当前用水会话。null 表示没有在用水。
     *
     * 它只回答「我现在是不是还在用水」，不参与记账 ——
     * 账全部来自服务端账单（见 [BillStore]）。
     */
    val session: DeviceSession? = null,
    /** 刚关闭成功，用水页显示「已关闭」。 */
    val closed: Boolean = false,
    /**
     * 冷启动对账成功、从磁盘恢复出一条活会话时 +1。
     *
     * 界面拿它当一次性信号：变了就直接跳到用水页。用版本号而不是布尔量，
     * 是因为用户可能中途返回过、又被恢复到同一条会话，布尔量不会再次触发。
     */
    val sessionRestoreTick: Int = 0,
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
    private val billStore = BillStore(app)

    /** 当前会话的落盘记录，用来在进程被杀后重新认领「还在流水」的设备。 */
    private val sessionStore = DeviceSessionStore(app)

    private val _ui = MutableStateFlow(UiState(configured = api.isConfigured))

    /** 同一个码 3 秒内只提示一次，否则每帧识别都会触发一次 */
    private var lastRejectKey: String? = null
    private var lastRejectAt = 0L
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var smsTimer: Job? = null

    init {
        viewModelScope.launch {
            val token = prefs.token.first()
            val phone = prefs.phone.first()
            // 设备编号平时不落盘，只有「正在用水」这一次例外，见 restoreSession
            _ui.value = _ui.value.copy(
                ready = true,
                token = token,
                phone = phone
            )
            // 未登录才需要图形验证码；已登录直接进首页，不必白跑一次请求。
            if (token.isBlank()) {
                refreshCaptcha()
            } else {
                restoreSession(token)
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
            // 会话记录也要清：换账号登录后不能把上一账号的水认领过来
            sessionStore.clear()
            // 设备号也要清：否则重新登录后可能对着上一台设备直接点启动
            _ui.value = _ui.value.copy(
                token = "",
                deviceId = "",
                deviceName = "",
                session = null,
                closed = false,
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
                // 设备编号平时只活在本次会话。名字要再请求一次，界面先显示编号。
                // 扫的还是正在用水的那台设备时，名字和「已关闭」状态都保留，别抖一下。
                val cur = _ui.value
                val sameDevice = cur.session?.deviceId == r.deviceId
                _ui.value = cur.copy(
                    deviceId = r.deviceId,
                    deviceName = if (sameDevice) cur.deviceName else "",
                    closed = if (sameDevice) cur.closed else false,
                    message = null
                )
                if (!sameDevice || cur.deviceName.isBlank()) loadDeviceName(r.deviceId)
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
     *
     * 注意只清「这次扫码选中的设备」，正在用水的那条会话记录不动 ——
     * 水还在流，记录一清，杀进程回来就再也找不到了。
     */
    fun clearDevice() {
        AppLog.i("Device", "清除设备编号 did=${_ui.value.deviceId}")
        _ui.value = _ui.value.copy(
            deviceId = "",
            deviceName = "",
            closed = false,
            message = null,
            messageIsError = false
        )
    }

    fun startDevice() {
        val s = _ui.value
        if (s.deviceId.isBlank()) return note("请先扫码添加设备")
        if (s.busy || s.stopping) return
        // 同时只允许一条会话：水还在流的时候再去开另一台，只会让两边都对不上账
        if (s.session != null) return note("有设备正在出水，请先在用水页关闭")

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null, closed = false)
            when (val r = api.devStart(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    // 等一下再读 gene.time：dev/start 返回时设备往往还没真正出水，
                    // 立刻查会拿到 status=99 且没有 time，会话就失去了身份证。
                    delay(SESSION_FINGERPRINT_DELAY_MS)
                    // 读不到也不阻塞流程：sessionTime 为空时，对账只看出水状态。
                    val status = api.devStatus(s.token, s.deviceId)
                    val sessionTime = if (status is ApiResult.Ok) status.data.sessionTime else null
                    val session = DeviceSession(
                        deviceId = s.deviceId,
                        deviceName = s.deviceName.takeIf { it.isNotBlank() },
                        startedAt = System.currentTimeMillis(),
                        sessionTime = sessionTime
                    )
                    // commit() 同步落盘：用户可能马上就杀进程，这条记录不能丢
                    sessionStore.save(session)
                    AppLog.i(
                        "Device",
                        "启动成功 did=${s.deviceId} time=${sessionTime ?: "(未取到)"}"
                    )
                    _ui.value = _ui.value.copy(
                        busy = false,
                        session = session,
                        closed = false,
                        message = null,
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
     * 只负责把出水停掉、把会话记录清掉。用量和金额不在这里算 ——
     * 账由服务端结算后从账单接口取（见 [syncBills]），所以关闭后等一会儿再同步一次。
     */
    fun stopDevice() {
        val s = _ui.value
        val session = s.session ?: return note("没有正在运行的设备")
        if (s.stopping) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(stopping = true, message = null)
            when (val r = api.devEnd(s.token, session.deviceId)) {
                is ApiResult.Ok -> {
                    AppLog.i("Device", "关闭成功 did=${session.deviceId}")
                    sessionStore.clear()
                    _ui.value = _ui.value.copy(
                        stopping = false,
                        session = null,
                        closed = true,
                        message = null,
                        messageIsError = false
                    )
                    // 服务端结算实测要 6 秒左右，等一等再拉，
                    // 这样用户切到「用量」页就能直接看到这一笔，不用手动下拉。
                    delay(BILL_SETTLE_DELAY_MS)
                    syncBills()
                }

                is ApiResult.Err -> {
                    AppLog.w(
                        "Device",
                        "关闭失败 did=${session.deviceId}: ${r.message} code=${r.code}"
                    )
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
     * 补设备名。
     *
     * `/ui/app/dev/home` 响应里塞满了广告位，这里只取 `device.name`。
     * 名字只是显示用，失败就不显示，不打扰用户。
     */
    private fun loadDeviceName(deviceId: String) {
        val token = _ui.value.token
        if (deviceId.isBlank() || token.isBlank()) return

        viewModelScope.launch {
            when (val r = api.devHome(token, deviceId)) {
                is ApiResult.Ok -> {
                    val name = r.data.trim()
                    if (name.isBlank()) return@launch
                    AppLog.i("Device", "设备名 did=$deviceId → $name")
                    val cur = _ui.value
                    if (cur.deviceId != deviceId) return@launch
                    _ui.value = cur.copy(deviceName = name)
                    // 会话里也补上，下次冷启动才有名字可显示
                    cur.session?.takeIf { it.deviceId == deviceId && it.deviceName != name }?.let {
                        sessionStore.save(it.copy(deviceName = name))
                    }
                }

                is ApiResult.Err -> AppLog.w("Device", "设备名获取失败: ${r.message}")
            }
        }
    }

    /**
     * 冷启动对账。
     *
     * 进程可能在上一次用水途中被杀，磁盘里留了一条会话记录。这里向服务端核对：
     * - 设备还在出水，且 `gene.time` 和记录里一致 → 还是同一次用水，认领回来
     * - 已停水 / 时间对不上（说明这是新的一次）/ 设备对不上 → 记录作废，清掉
     *
     * 服务端没有「列出我的设备」接口（`/ui/app/master` 只有账号、`/acc/stat` 只有登录态），
     * 所有设备接口都必须带 did，所以设备号只能从这个本地记录里取。
     */
    private fun restoreSession(token: String) {
        val saved = sessionStore.load() ?: return
        AppLog.i(
            "Device",
            "发现未结算会话 did=${saved.deviceId} time=${saved.sessionTime ?: "(无)"}"
        )

        viewModelScope.launch {
            when (val r = api.devStatus(token, saved.deviceId)) {
                is ApiResult.Ok -> {
                    val status = r.data
                    // 任一方没有时间就只看出水状态，不硬判
                    val sameSession = saved.sessionTime == null ||
                        status.sessionTime == null ||
                        saved.sessionTime == status.sessionTime
                    AppLog.i(
                        "Device",
                        "对账 running=${status.running} status=${status.rawStatus} " +
                            "time=${status.sessionTime ?: "(无)"} same=$sameSession"
                    )
                    if (status.running && sameSession) {
                        _ui.value = _ui.value.copy(
                            deviceId = saved.deviceId,
                            deviceName = saved.deviceName.orEmpty(),
                            session = saved,
                            closed = false,
                            sessionRestoreTick = _ui.value.sessionRestoreTick + 1
                        )
                        // 记录里没名字（启动那次请求失败）就补一次
                        if (saved.deviceName.isNullOrBlank()) loadDeviceName(saved.deviceId)
                    } else {
                        AppLog.i("Device", "会话已失效，清除记录")
                        sessionStore.clear()
                    }
                }

                // 网络失败不销毁记录：下次启动还有机会认领，误清就等于把这笔水丢了
                is ApiResult.Err -> AppLog.w("Device", "对账失败，保留记录: ${r.message}")
            }
        }
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
