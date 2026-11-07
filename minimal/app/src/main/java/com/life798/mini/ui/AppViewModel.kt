package com.life798.mini.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.life798.mini.BuildConfig
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.PangguaiLaundryStore
import com.life798.mini.core.laundry.LaundrySession
import com.life798.mini.core.BillStore
import com.life798.mini.core.DevicePrefs
import com.life798.mini.core.DeviceSession
import com.life798.mini.core.DeviceSessionStore
import com.life798.mini.core.LoginForm
import com.life798.mini.core.Platform
import com.life798.mini.core.PlatformAuth
import com.life798.mini.core.PlatformSession
import com.life798.mini.core.PlatformSessionStore
import com.life798.mini.core.RawSession
import com.life798.mini.core.SessionState
import com.life798.mini.core.UpdateInfo
import com.life798.mini.core.auth.Hui798Auth
import com.life798.mini.core.auth.PangguaiAuth
import com.life798.mini.core.isAuthError
import com.life798.mini.core.isNewerVersion
import com.life798.mini.net.GithubMirror
import com.life798.mini.net.PangguaiApi
import com.life798.mini.net.PangguaiWasherApi
import com.life798.mini.net.PgWasherLaunch
import com.life798.mini.net.PgAlipayLaunch
import com.life798.mini.net.PgSku
import com.life798.mini.net.PgWasherReady
import com.life798.mini.net.UpdateApi
import com.life798.mini.net.WaterApi
import com.life798.mini.scan.QrParser
import com.life798.mini.scan.QrResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 关闭设备后等服务端结算的时间。
 *
 * 抓包实测：关闭后 3 秒 `out` 还是旧值，6 秒才跳。等到 8 秒再拉账单，
 * 这样用户关完水切到「用量」页就能看到这一笔，不用手动下拉。
 */
private const val BILL_SETTLE_DELAY_MS = 8000L

/**
 * 冷启动校验 token 的超时。
 *
 * 校验本身只是一次 `GET /acc/view-info`，正常几百毫秒。
 * 但网络差的时候不能让用户盯着加载页等，超时就按「已登录」放行 ——
 * 宁可后面某个请求失败再处理，也不能因为没网把人挡在门外。
 */
private const val AUTH_CHECK_TIMEOUT_MS = 3000L

/**
 * 启动成功后补 `gene.time`（会话身份证）的节奏。
 *
 * `dev/start` 只是把指令下发下去，设备转成 `status=1` 有延迟，`time` 字段要等它出现。
 * 实测 3 秒时还查不到、8 秒时才有，所以不能只试一两次：
 * 每 1 秒查一次、最多 20 次（约 20 秒），拿到就停。
 * 记录本身在启动成功那一刻就已经落盘，这里只是补一个更精确的判定依据。
 */
private const val SESSION_FINGERPRINT_INTERVAL_MS = 1000L
private const val SESSION_FINGERPRINT_ATTEMPTS = 20

/** 支付结果确认：立即查一次，之后最多再查 2 次，间隔 5 秒。 */
private const val PAY_CONFIRM_ATTEMPTS = 3
private const val PAY_CONFIRM_INTERVAL_MS = 5000L

/**
 * 扫码页上「胖乖洗衣机」卡片的状态。
 *
 * 只有扫到胖乖码才非 null；798 的扫码流程完全不碰它。
 * 预检（只读）完成后 [ready] 才有值，用户点按钮才走 [PangguaiWasherApi.launch]。
 */
data class PgWasherUi(
    val nqt: String,
    val ready: PgWasherReady? = null,
    /** 当前阶段文案，启动时逐步更新。 */
    val stage: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val launched: PgWasherLaunch? = null,
    /** 用户选中的程序；null = 未选择，界面不默认选中任何一项。 */
    val selectedSkuId: String? = null,
    /** `trade/preview/v4.realPrice`，底部应付金额以此为准。 */
    val payable: String? = null,
    val quoteLoading: Boolean = false,
    /** 一次性支付宝唤起事件。 */
    val alipayLaunch: PgAlipayLaunch? = null,
    /** 已创建的订单号；响应丢失时复用，绝不重复 create。 */
    val orderNo: String = "",
    val paymentSent: Boolean = false,
)

data class UiState(
    val ready: Boolean = false,
    /**
     * 两个平台各自的登录态。**门禁只看这里**：
     * `accounts.values.any { it.isActive }`，任一平台在线就不回登录页。
     */
    val accounts: Map<Platform, PlatformSession> = Platform.entries.associateWith {
        PlatformSession(it)
    },
    /** 两个平台各自的登录表单。切 Tab 不互相冲掉，倒计时也各走各的。 */
    val forms: Map<Platform, LoginForm> = Platform.entries.associateWith { LoginForm(it) },
    /** 登录页当前选中的平台；从设置页「添加账号」进来时是目标平台。 */
    val loginTarget: Platform = Platform.HUI798,
    val deviceId: String = "",
    /** 设备名，来自 `/ui/app/dev/home`。拿不到时为空，界面退化成显示编号。 */
    val deviceName: String = "",
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
    val configured: Boolean = true,
    /** 胖乖接口地址是否配置。两个平台各自判断，不互相禁用登录按钮。 */
    val pangguaiConfigured: Boolean = true,
    /** 正在检查更新，关于页那一行显示「检查中…」。 */
    val updateChecking: Boolean = false,
    /** 检查到的新版本；null 表示没有可提示的更新。 */
    val updateInfo: UpdateInfo? = null,
    /** 手动检查失败的原因；自动检查失败不写这里（静默）。 */
    val updateError: String? = null,
    /** 手动检查完、确认已是最新。 */
    val updateUpToDate: Boolean = false,
    /** 用户点了「以后再说」，本次进程不再弹。 */
    val updateDismissed: Boolean = false,
    /** 正在探测下载线路，按钮显示「准备中…」。 */
    val updatePreparing: Boolean = false,
    /** 探测好的下载地址，界面拿到就打开浏览器并清空。 */
    val updateDownloadUrl: String? = null,
    /** 胖乖洗衣机扫码卡片；null 表示当前没有待启动的胖乖设备。 */
    val pangguaiWasher: PgWasherUi? = null,
    /** 胖乖洗衣运行态（可多台），与 798 的 [session] 完全独立。 */
    val laundrySessions: List<LaundrySession> = emptyList(),
) {
    fun account(platform: Platform): PlatformSession =
        accounts[platform] ?: PlatformSession(platform)

    fun form(platform: Platform): LoginForm =
        forms[platform] ?: LoginForm(platform)

    /**
     * 主业务（扫码 / 账单 / 设备）的 token，**只指向 798**。
     *
     * 绝不能 fallback 到胖乖：798 的接口拿着胖乖的 token 去请求，
     * 结果是 401，且账单会写进错误的账本。
     */
    val token: String get() = account(Platform.HUI798).token
    val phone: String get() = account(Platform.HUI798).phone

    /** 数据仓库的隔离键，带平台前缀。本期恒为 798，为将来留口子。 */
    val activeOwnerKey: String get() = account(Platform.HUI798).ownerKey

    /** 门禁：任一平台在线，就不回登录页。 */
    val loggedIn: Boolean get() = accounts.values.any { it.isActive }

    val hui798Active: Boolean get() = account(Platform.HUI798).isActive
    val pangguaiActive: Boolean get() = account(Platform.PANGGUAI).isActive
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val api = WaterApi()
    private val pangguaiApi = PangguaiApi(app)

    /** 胖乖洗衣机启动链路，复用 pangguaiApi 的请求头与信封解析。 */
    private val washerApi = PangguaiWasherApi(pangguaiApi)

    /**
     * 两个平台的登录实现。登录页按当前平台取对应实现，
     * 两条链路互不调用、互不影响。
     */
    private val auths: Map<Platform, PlatformAuth> = mapOf(
        Platform.HUI798 to Hui798Auth(api),
        Platform.PANGGUAI to PangguaiAuth(pangguaiApi),
    )

    /** 两个平台的登录态落盘。key 由 `Platform.prefix` 统一生成。 */
    private val sessionPrefs = PlatformSessionStore(app)

    /**
     * 账单仓库。**按当前 798 登录手机号取**，所以是个 getter 而不是字段 ——
     * 换账号后拿到的自然是另一个 key 的账本。构造成本只有一个 SP 引用，可以随便建。
     *
     * 本期主业务仍然只属于 798；胖乖只维护登录态，不产生账单。
     */
    private val billStore: BillStore
        get() = BillStore(app, _ui.value.phone, Platform.HUI798)

    /** 常用设备 + 最近 7 天流水。同样按手机号隔离，跟账单各管各的。 */
    private val devicePrefs: DevicePrefs
        get() = DevicePrefs(app, _ui.value.phone, Platform.HUI798)

    /** 当前会话的落盘记录，用来在进程被杀后重新认领「还在流水」的设备。 */
    private val sessionStore = DeviceSessionStore(app, Platform.HUI798)

    private val _ui = MutableStateFlow(
        UiState(
            configured = api.isConfigured,
            pangguaiConfigured = pangguaiApi.isConfigured,
        )
    )

    private val laundryStore = PangguaiLaundryStore(app)

    /** 运行态轮询与落盘，独立于 798 的会话链路。 */
    private val laundryController = LaundryController(
        scope = viewModelScope,
        api = washerApi,
        store = laundryStore,
        tokenProvider = { _ui.value.account(Platform.PANGGUAI).token },
        emit = { list -> _ui.update { it.copy(laundrySessions = list) } },
    )

    init {
        // 冷启动先恢复未结束的洗衣记录，卡片与倒计时不会被重启清掉
        laundryController.restore()
        // 「按账号隔离」之前的单份数据已经分不清是谁的了，启动时清一次。
        // 新 key（bills_<owner> / favorites_<owner>）一个都不碰。
        BillStore.clearLegacy(app)
        DevicePrefs.clearLegacy(app)
    }

    /** 同一个码 3 秒内只提示一次，否则每帧识别都会触发一次 */
    private var lastRejectKey: String? = null
    private var lastRejectAt = 0L
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 两个平台的短信倒计时各走各的，退出一个平台不影响另一个的计时。 */
    private val smsTimers = mutableMapOf<Platform, Job>()

    /**
     * 正在跑的账单同步。
     *
     * 用它做互斥，而不是 [UiState.billsSyncing] 那个布尔标志：标志只能防重入，
     * 拦不住「登出后旧同步还在跑」，也没法在换号时把它掐掉。
     * 协程一旦 cancel，会在下一个挂起点（下一次网络请求）直接抛
     * CancellationException 退出，不需要在循环里手工查账号。
     */
    private var billSyncJob: Job? = null

    private val updateApi = UpdateApi()
    private var updateJob: Job? = null

    init {
        // 启动自动检查更新：单独一条协程，失败完全静默，不影响任何主流程。
        // 延迟 1.5 秒是为了等首屏渲染完，别跟登录校验、账单同步抢带宽。
        viewModelScope.launch {
            delay(1500)
            checkUpdate(silent = true)
        }

        viewModelScope.launch {
            val stored = sessionPrefs.loadAll()
            _ui.update { it.copy(accounts = stored, ready = false) }

            // 两个平台并行校验。任一通过就足以放行；
            // 某个平台网络失败不影响另一个平台，也不把人挡在门外。
            val pending = stored.values.filter { it.token.isNotBlank() }
            supervisorScope {
                pending.map { session ->
                    async { verifyStored(session) }
                }.awaitAll()
            }

            _ui.update { it.copy(ready = true) }

            if (_ui.value.hui798Active) {
                restoreSession(_ui.value.token)
            } else {
                // 798 没登录（或已失效）也要准备好验证码，用户随时可以补登
                refreshCaptcha(Platform.HUI798)
            }
        }
    }

    /**
     * 冷启动校验一个平台。
     *
     * - 通过：标记 ACTIVE，并以服务端返回的手机号为准（防止磁盘上的 phone 与 token 不匹配）
     * - 明确失效：标记 EXPIRED，只清这个平台
     * - 超时 / 网络失败：按已登录放行，沿用原有策略；真过期了下一次业务请求会走 handleAuthError
     */
    private suspend fun verifyStored(session: PlatformSession) {
        val auth = auths[session.platform] ?: return
        val result = withTimeoutOrNull(AUTH_CHECK_TIMEOUT_MS) { auth.verify(session.token) }
        when {
            result is ApiResult.Ok -> {
                AppLog.i("Auth", "${session.platform.label} 冷启动校验通过")
                markActive(
                    platform = session.platform,
                    token = session.token,
                    uid = session.uid,
                    phone = result.data.ifBlank { session.phone },
                )
            }

            result is ApiResult.Err && isAuthError(session.platform, result.code) -> {
                AppLog.w("Auth", "${session.platform.label} 凭证已失效 code=${result.code}，清除")
                markExpired(session.platform)
            }

            else -> {
                AppLog.w("Auth", "${session.platform.label} 冷启动校验未完成，按已登录放行")
            }
        }
    }

    // ---------------- 登录 ----------------

    /**
     * 刷新图形验证码。只有 798 需要，胖乖直接返回。
     *
     * 每个平台各存一份，互不覆盖。
     */
    fun refreshCaptcha(platform: Platform) {
        if (!platform.needsGraphCaptcha) return
        val auth = auths[platform] ?: return

        if (platform == Platform.HUI798 && !api.isConfigured) {
            updateForm(platform) {
                it.copy(captchaLoading = false, captchaImage = null, captchaError = "未配置 API 网关")
            }
            return
        }

        viewModelScope.launch {
            updateForm(platform) { it.copy(captchaLoading = true, captchaError = null) }
            val key = randomKey()
            when (val r = auth.captcha(key)) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "${platform.label} 验证码已加载 ${r.data.size}B")
                    updateForm(platform) {
                        it.copy(
                            captchaLoading = false,
                            captchaKey = key,
                            captchaImage = r.data,
                            captchaError = null,
                        )
                    }
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "${platform.label} 验证码加载失败: ${r.message}")
                    updateForm(platform) {
                        it.copy(
                            captchaLoading = false,
                            captchaImage = null,
                            captchaError = r.message,
                        )
                    }
                }
            }
        }
    }

    fun sendSms(platform: Platform, phone: String, graphCode: String) {
        if (phone.isBlank()) return note(platform, "请输入手机号")
        if (platform.needsGraphCaptcha && graphCode.isBlank()) {
            return note(platform, "请输入图形验证码")
        }
        val form = _ui.value.form(platform)
        if (form.countdown > 0) return
        val auth = auths[platform] ?: return

        viewModelScope.launch {
            updateForm(platform) { it.copy(busy = true, message = null) }
            val r = auth.sendSms(phone.trim(), graphCode.trim(), form.captchaKey)
            when (r) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "${platform.label} 短信已发送 → ${AppLog.maskPhone(phone)}")
                    updateForm(platform) {
                        it.copy(busy = false, message = "验证码已发送", messageIsError = false)
                    }
                    startSmsCountdown(platform)
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "${platform.label} 发送短信失败: ${r.message}")
                    updateForm(platform) {
                        it.copy(busy = false, message = r.message, messageIsError = true)
                    }
                    // 图形码是一次性的，发码失败要换一张
                    if (platform.needsGraphCaptcha) refreshCaptcha(platform)
                }
            }
        }
    }

    /**
     * 登录。
     *
     * 只写当前平台的三个 key，另一平台的会话与数据一个字节都不碰 ——
     * 这是「两套登录互不冲突、可共存」的落点。
     */
    fun login(platform: Platform, phone: String, smsCode: String) {
        if (phone.isBlank()) return note(platform, "请输入手机号")
        if (smsCode.isBlank()) return note(platform, "请输入短信验证码")
        val auth = auths[platform] ?: return

        viewModelScope.launch {
            updateForm(platform) { it.copy(busy = true, message = null) }
            when (val r = auth.login(phone.trim(), smsCode.trim())) {
                is ApiResult.Ok -> {
                    val session = r.data
                    sessionPrefs.save(platform, session.token, session.uid, session.phone)
                    AppLog.i(
                        "Login",
                        "${platform.label} 登录成功 uid=${session.uid} token=${AppLog.mask(session.token)}"
                    )
                    _ui.update { state ->
                        state.copy(
                            accounts = state.accounts + (
                                platform to PlatformSession(
                                    platform = platform,
                                    token = session.token,
                                    uid = session.uid,
                                    phone = session.phone,
                                    state = SessionState.ACTIVE,
                                )
                                ),
                            forms = state.forms + (platform to LoginForm(platform)),
                            loginTarget = platform,
                        )
                    }
                    // 只有 798 有主业务需要恢复（会话认领、账单同步）
                    if (platform == Platform.HUI798) restoreSession(session.token)
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "${platform.label} 登录失败: ${r.message} (code=${r.code})")
                    updateForm(platform) {
                        it.copy(busy = false, message = r.message, messageIsError = true)
                    }
                }
            }
        }
    }

    /**
     * 登录表单输入。只改当前平台，另一个平台的内容原样保留。
     *
     * 传 null 表示这一项不动；传空串表示清空。
     */
    fun updateLoginForm(
        platform: Platform,
        phone: String? = null,
        graphCode: String? = null,
        smsCode: String? = null,
    ) {
        updateForm(platform) {
            it.copy(
                phone = phone ?: it.phone,
                graphCode = graphCode ?: it.graphCode,
                smsCode = smsCode ?: it.smsCode,
            )
        }
    }

    /** 从设置页进入「添加账号」时，指定登录页默认选中哪个平台。 */
    fun beginAddAccount(platform: Platform) {
        _ui.update { it.copy(loginTarget = platform) }
    }

    /** 登录页切换平台。首登时两个 Tab 都可以点。 */
    fun selectLoginPlatform(platform: Platform) {
        _ui.update { it.copy(loginTarget = platform) }
        if (platform.needsGraphCaptcha && _ui.value.form(platform).captchaImage == null) {
            refreshCaptcha(platform)
        }
    }

    /**
     * 统一的鉴权失败出口。
     *
     * **只清出问题的那个平台**：另一个平台仍在线时，不跳页、不切换、不弹登录页。
     * 两个平台功能不同，拿一个平台的会话去顶另一个平台的业务没有意义。
     */
    private suspend fun handleAuthError(platform: Platform, code: Int): Boolean {
        if (!isAuthError(platform, code)) return false
        AppLog.w("Auth", "${platform.label} 登录态失效 code=$code，只清除该平台")
        markExpired(platform)
        return true
    }

    /**
     * 退出登录。**只退出指定的平台。**
     *
     * 退出胖乖时，798 的 token、账单、常用设备、用水会话全部原样保留；
     * 退出 798 时，胖乖的登录态也不受影响。
     */
    fun logout(platform: Platform) {
        // 798 才有的后台任务：账单同步握着旧 token，必须先掐掉
        if (platform == Platform.HUI798) {
            billSyncJob?.cancel()
            billSyncJob = null
        }

        viewModelScope.launch {
            sessionPrefs.clear(platform)
            if (platform == Platform.HUI798) {
                // 会话记录也要清：换账号登录后不能把上一账号的水认领过来
                sessionStore.clear()
            }

            _ui.update { state ->
                val is798 = platform == Platform.HUI798
                state.copy(
                    // 冷启动校验失败时是从 ready=false 进来的，这里必须置回 true，
                    // 否则会一直卡在加载页
                    ready = true,
                    accounts = state.accounts + (platform to PlatformSession(platform)),
                    forms = state.forms + (platform to LoginForm(platform)),
                    // phone 是账单和设备仓库的存储 key，必须和账号一起清；
                    // 只清磁盘不清内存的话，getter 仍会造出 enabled 的仓库
                    deviceId = if (is798) "" else state.deviceId,
                    deviceName = if (is798) "" else state.deviceName,
                    session = if (is798) null else state.session,
                    closed = if (is798) false else state.closed,
                    // 同步可能正跑到一半，残留的 true 会让下一个账号永远拉不了账单
                    billsSyncing = if (is798) false else state.billsSyncing,
                    message = null,
                    messageIsError = false,
                )
            }

            if (platform == Platform.HUI798) refreshCaptcha(Platform.HUI798)
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
        // 互斥看 Job 而不是 billsSyncing：标志只能防重入，
        // 判断不了「上一条是不是已经被登出掐掉了」。
        if (billSyncJob?.isActive == true) return
        val token = _ui.value.token
        if (token.isBlank()) return

        // 仓库实例在入口钉死。billStore 是 getter，每访问一次就重读
        // _ui.value.phone 并 new 一个仓库；补详情是逐条发请求的长循环，
        // 要是每轮都重新解引用，换号之后后半程就会写到新账号的账本上。
        //
        // 光钉死实例还不够保险（cancel 只在挂起点生效），所以登出时
        // 还会把整条协程 cancel 掉 —— 见 logout()。
        val store = billStore

        billSyncJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(billsSyncing = true, billsError = null)

            when (val list = api.billList(token, page = 0)) {
                is ApiResult.Err -> {
                    AppLog.w("Bill", "账单列表同步失败: ${list.message}")
                    // 鉴权失效：清凭证回登录页。留一条「同步失败」只会误导用户
                    if (handleAuthError(Platform.HUI798, list.code)) return@launch
                    _ui.value = _ui.value.copy(billsSyncing = false, billsError = list.message)
                    return@launch
                }

                is ApiResult.Ok -> {
                    val added = store.merge(list.data)
                    AppLog.i("Bill", "账单列表 ${list.data.size} 条，新增 $added")

                    var filled = 0
                    var failed = 0
                    for (id in store.missingDetail()) {
                        when (val detail = api.billDetail(token, id)) {
                            is ApiResult.Ok -> {
                                val bill = detail.data
                                if (bill.liters != null) {
                                    store.updateDetail(id, bill.liters, bill.secs, bill.deviceName)
                                    filled++
                                }
                            }
                            is ApiResult.Err -> {
                                // 鉴权失效：后面每一条都会失败，别再刷请求
                                if (handleAuthError(Platform.HUI798, detail.code)) return@launch
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
    fun onQrDecoded(raw: String, record: Boolean = true): Boolean {
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
                    message = null,
                    // 扫到 798 设备就把上一张胖乖卡片收起来，两个平台不同屏
                    pangguaiWasher = null
                )
                if (!sameDevice || cur.deviceName.isBlank()) loadDeviceName(r.deviceId)
                // 记一笔使用流水（不去重，同一台扫几次就记几条）。
                // 名字这会儿多半还没有，等 loadDeviceName 回来再补。
                // 从设备列表点进来不算「用过」，不能记账 —— 否则点一次多一条。
                if (record) {
                    devicePrefs.recordVisit(
                        id = r.deviceId,
                        name = if (sameDevice) cur.deviceName else ""
                    )
                }
                true
            }

            is QrResult.Pangguai -> {
                AppLog.i("Scan", "胖乖设备码 nqt=${r.nqt} host=${r.host.ifBlank { "(裸NQT)" }}")
                if (!_ui.value.pangguaiActive) {
                    rejectOnce(r.nqt, "请先在设置页登录胖乖生活")
                    false
                } else {
                    // 先落一张「识别中」的卡片，再异步跑只读预检。
                    // 返回 true 让扫码页停相机；MainActivity 看到 pangguaiWasher 就不会
                    // 往栈上 push 798 的启动页，用户留在扫码页点「启动桶自洁」。
                    _ui.update {
                        it.copy(
                            pangguaiWasher = PgWasherUi(
                                nqt = r.nqt,
                                stage = "正在识别设备…",
                                busy = true,
                            ),
                            message = null,
                        )
                    }
                    preparePangguaiWasher(r.nqt)
                    true
                }
            }

            is QrResult.PangguaiWater -> {
                // MainActivity 已在扫码回调里把用水设备分流给 PangguaiWaterViewModel，
                // 这里只作 when 穷尽兜底，正常不会走到。
                AppLog.w("Scan", "胖乖用水设备码误入 798 处理 sn=${r.sn}")
                rejectOnce(r.sn, "请重新扫描用水设备")
                false
            }

            is QrResult.WrongPlatform -> {
                AppLog.w("Scan", "非本应用支持的设备 host=${r.host}")
                rejectOnce(r.host, "非慧生活798 / 胖乖生活设备，请使用对应 App 扫码")
                false
            }

            is QrResult.Unrecognized -> {
                AppLog.w("Scan", "无法识别，原文长度=${r.raw.length}")
                rejectOnce(r.raw, "无法识别的二维码：${r.raw.take(30)}")
                false
            }
        }
    }

    // ---------------- 胖乖洗衣机（本期只做 0 元桶自洁） ----------------

    /** 扫码后的只读预检：识别设备 → 读详情 → 找 0 元桶自洁 SKU。不创建订单。 */
    private fun preparePangguaiWasher(nqt: String) {
        val token = _ui.value.account(Platform.PANGGUAI).token
        if (token.isBlank()) {
            _ui.update {
                it.copy(
                    pangguaiWasher = it.pangguaiWasher?.copy(
                        busy = false,
                        stage = "",
                        error = "胖乖登录态已失效，请到设置页重新登录",
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            when (
                val r = washerApi.prepare(token, nqt) { stage ->
                    _ui.update {
                        it.copy(pangguaiWasher = it.pangguaiWasher?.copy(stage = stage))
                    }
                }
            ) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(
                        pangguaiWasher = it.pangguaiWasher?.copy(
                            ready = r.data, busy = false, stage = "", error = null,
                        )
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Pangguai", "扫码预检失败: ${r.message}")
                    _ui.update {
                        it.copy(
                            pangguaiWasher = it.pangguaiWasher?.copy(
                                busy = false, stage = "", error = r.message,
                            )
                        )
                    }
                }
            }
        }
    }

    /** 用户点「启动桶自洁」：整个工程里唯一会创建胖乖订单并启动设备的入口。 */
    /** 选中洗衣程序：不默认选，选中后才拉服务端应付价。 */
    fun selectPangguaiSku(skuId: String) {
        val cur = _ui.value.pangguaiWasher ?: return
        val ready = cur.ready ?: return
        if (cur.busy || ready.skus.none { it.skuId == skuId }) return
        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    selectedSkuId = skuId, payable = null, quoteLoading = true, error = null,
                )
            )
        }
        val token = _ui.value.account(Platform.PANGGUAI).token
        if (token.isBlank()) return note("胖乖登录态已失效，请到设置页重新登录")
        viewModelScope.launch {
            when (val r = washerApi.preview(token, ready.goods.goodsId, skuId)) {
                is ApiResult.Ok -> _ui.update {
                    it.copy(
                        pangguaiWasher = it.pangguaiWasher?.copy(
                            payable = r.data.price, quoteLoading = false,
                        )
                    )
                }

                is ApiResult.Err -> _ui.update {
                    it.copy(
                        pangguaiWasher = it.pangguaiWasher?.copy(
                            quoteLoading = false, error = r.message,
                        )
                    )
                }
            }
        }
    }

    /** 「选好了」：0 元走原有启动链路，付费走支付宝。 */
    fun startPangguaiSelfClean() {
        val cur = _ui.value.pangguaiWasher ?: return
        val ready = cur.ready ?: return
        val skuId = cur.selectedSkuId ?: return note("请先选择洗衣程序")
        val sku = ready.skus.firstOrNull { it.skuId == skuId }
            ?: return note("洗衣程序已变化，请重新选择")
        if (cur.busy || cur.paymentSent) return

        val token = _ui.value.account(Platform.PANGGUAI).token
        if (token.isBlank()) return note("胖乖登录态已失效，请到设置页重新登录")

        val price = cur.payable ?: sku.price
        if (isZeroPrice(price)) startZeroAmount(token, ready.copy(sku = sku))
        else startAlipay(token, ready, sku, price)
    }

    /** 0 元（桶自洁）：沿用抓包验证过的完整链路。 */
    private fun startZeroAmount(token: String, ready: PgWasherReady) {
        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    busy = true, stage = "准备启动…", error = null, launched = null,
                )
            )
        }
        viewModelScope.launch {
            when (
                val r = washerApi.launch(token, ready) { stage ->
                    _ui.update {
                        it.copy(pangguaiWasher = it.pangguaiWasher?.copy(stage = stage))
                    }
                }
            ) {
                is ApiResult.Ok -> {
                    AppLog.i("Pangguai", "桶自洁启动成功 orderNo=${r.data.orderNo}")
                    recordLaundry(ready, ready.sku, r.data.orderNo, r.data.price)
                }

                is ApiResult.Err -> {
                    AppLog.w("Pangguai", "桶自洁启动失败: ${r.message}")
                    failPangguai(r.message)
                }
            }
        }
    }

    /**
     * 付费段：渠道校验 → 设备检查 → 创建订单 → 取支付宝签名串。
     * 已创建的 orderNo 会复用，绝不重复 create；**不在这里判付款成功**。
     */
    private fun startAlipay(token: String, ready: PgWasherReady, sku: PgSku, price: String) {
        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    busy = true, paymentSent = true, stage = "正在创建订单…", error = null,
                )
            )
        }
        viewModelScope.launch {
            val existing = _ui.value.pangguaiWasher?.orderNo.orEmpty()
            val orderNo = if (existing.isNotBlank()) existing else when (
                val r = washerApi.createPaidOrder(
                    token = token,
                    goodsId = ready.goods.goodsId,
                    skuId = sku.skuId,
                    price = price,
                    imei = ready.goods.imei,
                    shopId = ready.goods.shopId,
                )
            ) {
                is ApiResult.Ok -> r.data
                is ApiResult.Err -> {
                    failPangguai(r.message)
                    return@launch
                }
            }
            _ui.update {
                it.copy(
                    pangguaiWasher = it.pangguaiWasher?.copy(
                        orderNo = orderNo, stage = "正在准备支付宝…",
                    )
                )
            }
            when (val r = washerApi.prepareAlipay(token, orderNo)) {
                is ApiResult.Ok -> {
                    val event = PgAlipayLaunch(System.currentTimeMillis(), orderNo, r.data)
                    _ui.update {
                        it.copy(
                            pangguaiWasher = it.pangguaiWasher?.copy(
                                alipayLaunch = event, busy = false,
                                stage = "请在支付宝中完成支付",
                            )
                        )
                    }
                }

                is ApiResult.Err -> failPangguai(r.message)
            }
        }
    }

    /**
     * 支付宝 SDK 返回后调用。**SDK 结果不算付款成功**，只以服务端
     * `order/pay_status` + `machine/device_is_start` 为准。
     */
    fun onAlipayReturned(id: Long) {
        val cur = _ui.value.pangguaiWasher ?: return
        val event = cur.alipayLaunch ?: return
        if (event.id != id) return
        val ready = cur.ready ?: return
        val skuId = cur.selectedSkuId ?: return
        val sku = ready.skus.firstOrNull { it.skuId == skuId } ?: return
        val token = _ui.value.account(Platform.PANGGUAI).token
        if (token.isBlank()) return note("胖乖登录态已失效，请到设置页重新登录")

        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    alipayLaunch = null, busy = true, stage = "正在确认支付…",
                )
            )
        }
        viewModelScope.launch {
            var paid = false
            for (i in 1..PAY_CONFIRM_ATTEMPTS) {
                when (val r = washerApi.orderPaid(token, event.orderNo)) {
                    is ApiResult.Ok -> if (r.data) {
                        paid = true
                        break
                    }

                    is ApiResult.Err -> {
                        failPangguai(r.message)
                        return@launch
                    }
                }
                if (i < PAY_CONFIRM_ATTEMPTS) delay(PAY_CONFIRM_INTERVAL_MS)
            }
            if (!paid) {
                failPangguai("支付尚未完成，可重试或到胖乖生活查看订单 ${event.orderNo.takeLast(8)}")
                return@launch
            }
            val started = washerApi.waitStarted(token, event.orderNo) { stage ->
                _ui.update {
                    it.copy(pangguaiWasher = it.pangguaiWasher?.copy(stage = stage))
                }
            }
            if (!started) {
                failPangguai("已支付但未检测到设备启动，订单号 ${event.orderNo}")
                return@launch
            }
            AppLog.i("Pangguai", "支付宝洗衣启动成功 orderNo=${event.orderNo}")
            recordLaundry(ready, sku, event.orderNo, cur.payable ?: sku.price)
        }
    }

    /** 启动成功后落运行态会话（数据层，界面后续再接）。 */
    private fun recordLaundry(
        ready: PgWasherReady,
        sku: PgSku,
        orderNo: String,
        price: String,
    ) {
        laundryController.add(
            LaundrySession(
                orderNo = orderNo,
                deviceId = ready.goods.goodsId,
                deviceName = ready.goods.name,
                skuId = sku.skuId,
                skuName = sku.name,
                shopName = ready.goods.orgName,
                price = price,
                minutes = sku.unitMinutes,
            )
        )
        val launch = PgWasherLaunch(
            orderNo = orderNo,
            deviceName = ready.goods.name,
            goodsId = ready.goods.goodsId,
            skuId = sku.skuId,
            skuName = sku.name,
            shopName = ready.goods.orgName,
            minutes = sku.unitMinutes,
            price = price,
        )
        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    launched = launch, busy = false, stage = "", error = null,
                    paymentSent = false, orderNo = "",
                )
            )
        }
    }

    /** 失败时保留 orderNo 供复用，避免重复下单。 */
    private fun failPangguai(message: String) {
        AppLog.w("Pangguai", "启动失败: $message")
        _ui.update {
            it.copy(
                pangguaiWasher = it.pangguaiWasher?.copy(
                    busy = false, stage = "", error = message,
                    paymentSent = false, alipayLaunch = null,
                )
            )
        }
    }

    private fun isZeroPrice(price: String): Boolean =
        (price.trim().toDoubleOrNull() ?: Double.NaN) == 0.0

    /** 关掉胖乖卡片，回到普通扫码。运行态在 laundrySessions 里，不受影响。 */
    fun clearPangguaiWasher() {
        _ui.update { it.copy(pangguaiWasher = null) }
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
        // 主业务只属于 798。只有胖乖在线时，扫码能扫但没有可用身份去启动设备。
        if (!s.hui798Active) return note("请先登录惠生活798")
        if (s.deviceId.isBlank()) return note("请先扫码添加设备")
        if (s.busy || s.stopping) return
        // 同时只允许一条会话：水还在流的时候再去开另一台，只会让两边都对不上账
        if (s.session != null) return note("有设备正在出水，请先在用水页关闭")

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null, closed = false)
            when (val r = api.devStart(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    var session = DeviceSession(
                        deviceId = s.deviceId,
                        deviceName = s.deviceName.takeIf { it.isNotBlank() },
                        startedAt = System.currentTimeMillis(),
                        sessionTime = null
                    )
                    // 先落盘再补身份证：用户可能启动完立刻杀进程，这条记录不能等
                    sessionStore.save(session)
                    AppLog.i("Device", "启动成功 did=${s.deviceId}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        session = session,
                        closed = false,
                        message = null,
                        messageIsError = false
                    )

                    // 补 gene.time：拿到就更新记录，拿不到就维持「只看是否出水」的弱判定
                    for (attempt in 1..SESSION_FINGERPRINT_ATTEMPTS) {
                        delay(SESSION_FINGERPRINT_INTERVAL_MS)
                        val status = api.devStatus(s.token, s.deviceId)
                        val time = if (status is ApiResult.Ok) status.data.sessionTime else null
                        if (time != null) {
                            session = session.copy(sessionTime = time)
                            sessionStore.save(session)
                            AppLog.i("Device", "会话身份证 #$attempt time=$time")
                            // 期间用户可能已经关水，那就别再往回写
                            val cur = _ui.value
                            if (cur.session?.deviceId == session.deviceId) {
                                _ui.value = cur.copy(session = session)
                            }
                            break
                        }
                    }
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "启动失败 did=${s.deviceId}: ${r.message} code=${r.code}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        message = r.message,
                        messageIsError = true
                    )
                    handleAuthError(Platform.HUI798, r.code)
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
                    handleAuthError(Platform.HUI798, r.code)
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
                    // 名字补进流水和常用列表，浮层下次渲染就能显示名字
                    devicePrefs.updateName(deviceId, name)
                    val cur = _ui.value
                    if (cur.deviceId != deviceId) return@launch
                    _ui.value = cur.copy(deviceName = name)
                    // 会话里也补上，下次冷启动才有名字可显示
                    cur.session?.takeIf { it.deviceId == deviceId && it.deviceName != name }?.let {
                        sessionStore.save(it.copy(deviceName = name))
                    }
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "设备名获取失败: ${r.message}")
                    handleAuthError(Platform.HUI798, r.code)
                }
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

                is ApiResult.Err -> {
                    // 网络失败不销毁记录：下次启动还有机会认领，误清就等于把这笔水丢了
                    AppLog.w("Device", "对账失败，保留记录: ${r.message}")
                    // 但鉴权失效不是网络问题 —— 记录留着也没意义，登出会一并清掉
                    handleAuthError(Platform.HUI798, r.code)
                }
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // ---------------- 内部 ----------------

    private fun startSmsCountdown(platform: Platform) {
        smsTimers[platform]?.cancel()
        smsTimers[platform] = viewModelScope.launch {
            for (i in 60 downTo 1) {
                updateForm(platform) { it.copy(countdown = i) }
                delay(1000)
            }
            updateForm(platform) { it.copy(countdown = 0) }
        }
    }

    /** 只改一个平台的表单，另一个平台原样保留。 */
    private inline fun updateForm(
        platform: Platform,
        crossinline block: (LoginForm) -> LoginForm,
    ) {
        _ui.update { state ->
            state.copy(forms = state.forms + (platform to block(state.form(platform))))
        }
    }

    /** 登录相关的提示写进表单，避免切换平台时看到另一个平台的报错。 */
    private fun note(platform: Platform, msg: String) {
        updateForm(platform) { it.copy(message = msg, messageIsError = true) }
        // 系统 Toast：样式和位置由 ROM 决定（vivo 上是白卡+图标，悬浮在底栏上方）
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    /** 非登录场景（扫码、设备）的提示，仍然走全局 message。 */
    private fun note(msg: String) {
        _ui.update { it.copy(message = msg, messageIsError = true) }
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    /** 校验通过：标记 ACTIVE，必要时把服务端返回的号码/uid 回写磁盘。 */
    private suspend fun markActive(
        platform: Platform,
        token: String,
        uid: String,
        phone: String,
    ) {
        val current = _ui.value.account(platform)
        val next = current.copy(
            token = token,
            uid = uid.ifBlank { current.uid },
            phone = phone.ifBlank { current.phone },
            state = SessionState.ACTIVE,
        )
        _ui.update { it.copy(accounts = it.accounts + (platform to next)) }
        if (next.phone != current.phone || next.uid != current.uid) {
            sessionPrefs.save(platform, next.token, next.uid, next.phone)
        }
    }

    /**
     * 凭证失效：只清这个平台。
     *
     * 保留 phone（设置页要显示「138****8888 已失效」，用户才知道该重登哪个号），
     * 但 token 清空、state 置 EXPIRED —— 所有业务请求都会因为 token 为空而停手。
     */
    private suspend fun markExpired(platform: Platform) {
        val current = _ui.value.account(platform)
        sessionPrefs.clear(platform)

        if (platform == Platform.HUI798) {
            billSyncJob?.cancel()
            billSyncJob = null
            sessionStore.clear()
        }

        _ui.update { state ->
            val is798 = platform == Platform.HUI798
            state.copy(
                accounts = state.accounts + (
                    platform to current.copy(token = "", state = SessionState.EXPIRED)
                    ),
                // 重登时预填失效的那个号码，省得用户再输一遍
                forms = state.forms + (
                    platform to state.form(platform).copy(
                        phone = current.phone,
                        countdown = 0,
                        busy = false,
                    )
                    ),
                deviceId = if (is798) "" else state.deviceId,
                deviceName = if (is798) "" else state.deviceName,
                session = if (is798) null else state.session,
                closed = if (is798) false else state.closed,
                billsSyncing = if (is798) false else state.billsSyncing,
            )
        }
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
        smsTimers.values.forEach { it.cancel() }
        updateJob?.cancel()
    }

    // ---------------- 检查更新 ----------------

    /**
     * 检查 GitHub 有没有新版本。
     *
     * @param silent true  = 启动时自动检查：失败不提示，只是不弹窗；
     *               false = 用户主动点「检查更新」：失败要明确告诉他为什么。
     */
    fun checkUpdate(silent: Boolean) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(
                updateChecking = true,
                updateError = null,
                updateUpToDate = false,
            )
            val result = updateApi.latest()
            _ui.value = when {
                // 有新版本 → 交给弹窗
                result is ApiResult.Ok &&
                    isNewerVersion(result.data.version, BuildConfig.VERSION_NAME) -> {
                    AppLog.i(
                        "Update",
                        "发现新版本 ${result.data.version}（当前 ${BuildConfig.VERSION_NAME}）"
                    )
                    _ui.value.copy(
                        updateChecking = false,
                        updateInfo = result.data,
                        // 重新检查到新版本时把「以后再说」的开关复位，
                        // 否则用户上次关掉之后，再点检查就永远不弹了
                        updateDismissed = false,
                        updateError = null,
                    )
                }

                // 已是最新
                result is ApiResult.Ok -> {
                    AppLog.i("Update", "已是最新 ${BuildConfig.VERSION_NAME}")
                    _ui.value.copy(
                        updateChecking = false,
                        updateInfo = null,
                        updateUpToDate = !silent,
                        updateError = null,
                    )
                }

                // 失败
                else -> {
                    val msg = (result as ApiResult.Err).message
                    if (silent) {
                        AppLog.i("Update", "自动检查失败（静默）：$msg")
                        _ui.value.copy(updateChecking = false)
                    } else {
                        _ui.value.copy(updateChecking = false, updateError = msg)
                    }
                }
            }
        }
    }

    /** 「以后再说」：只关这一次，下次启动还会提醒。 */
    fun dismissUpdate() {
        _ui.value = _ui.value.copy(updateDismissed = true)
    }

    /**
     * 「立即更新」：先探测哪条线路能通，再把地址交给界面打开。
     *
     * 探测上限约 12 秒（原链 6 秒 + ghfast.top 6 秒）；全挂就退回 release 页面，
     * 至少让用户能看到发生了什么，而不是一个点了没反应的按钮。
     */
    fun downloadUpdate() {
        val info = _ui.value.updateInfo ?: return
        if (_ui.value.updatePreparing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(updatePreparing = true)
            val apk = info.apkUrl
            val target = if (apk != null) GithubMirror.resolve(apk) ?: info.pageUrl else info.pageUrl
            AppLog.i("Update", "下载地址: $target")
            _ui.value = _ui.value.copy(updatePreparing = false, updateDownloadUrl = target)
        }
    }

    /** 界面打开链接后调用，把一次性地址清掉，避免重复打开。 */
    fun consumeDownloadUrl() {
        _ui.value = _ui.value.copy(updateDownloadUrl = null)
    }
}
