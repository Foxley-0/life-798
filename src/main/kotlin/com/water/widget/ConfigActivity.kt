package com.water.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.water.widget.ui.AutoUpdateSettingsDialog
import com.water.widget.ui.AppUpdateResultDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.water.widget.ui.DashboardScreen
import com.water.widget.ui.DashboardViewModel
import com.water.widget.ui.WaterTheme
import com.water.widget.ui.UsageHistoryStore
import com.water.widget.ui.WaterBillUiStateFactory
import kotlinx.coroutines.launch

/**
 * Compose 版主页入口。
 * 继续复用原有账户、设备、Widget 与磁贴逻辑，只替换主界面的呈现层。
 */
class ConfigActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_WATER_RECOVERY = "com.water.widget.EXTRA_OPEN_WATER_RECOVERY"
    }

    private val viewModel: DashboardViewModel by viewModels()
    private val updateViewModel: AppUpdateViewModel by viewModels()
    private val ui = Handler(Looper.getMainLooper())
    private var scoreGeneration = 0
    private var historyGeneration = 0
    private var hasLoadedHistory = false
    private var deviceSyncGeneration = 0
    private var destroyed = false
    private var notificationRequestInFlight = false
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationRequestInFlight = false
        if (!granted) {
            toast("未开启通知权限，功能仍会运行，但通知栏不会显示进度")
        }
    }
    private val addDevice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.reloadAccounts()
            updateWidgets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        AppNotifications.ensureChannels(this)
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val updateState by updateViewModel.state.collectAsStateWithLifecycle()
                var showUpdateSettings by rememberSaveable { mutableStateOf(false) }
                if (showUpdateSettings) AutoUpdateSettingsDialog(updateState.policy, updateViewModel::setPolicy) { showUpdateSettings = false }
                AppUpdateResultDialog(updateState, ::openWebsite, updateViewModel::dismissDialog)
                DashboardScreen(
                    state = state,
                    updateStatus = updateState.status,
                    autoUpdateLabel = updateState.policy.label,
                    onCheckUpdate = { updateViewModel.check() },
                    onUpdateSettings = { showUpdateSettings = true },
                    onProject = { openWebsite(PROJECT_URL) },
                    onOpenSupportLink = ::openWebsite,
                    onStopDevice = viewModel::stopDevice,
                    onLogin = { startActivity(Intent(this, LoginActivity::class.java)) },
                    onAccounts = { startActivity(Intent(this, AccountsActivity::class.java)) },
                    themeMode = ThemeSettings.mode(this),
                    onThemeModeChange = { mode ->
                        ThemeSettings.setMode(this, mode)
                        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
                        recreate()
                    },
                    onRunTasks = { runWithNotificationPermission(::runTasksInHome) },
                    onScores = { startActivity(Intent(this, ScoreActivity::class.java)) },
                    onWallet = { startActivity(Intent(this, WalletActivity::class.java)) },
                    onSelectAccount = { phone -> switchAccount(phone) },
                    onFetchDevices = { fetchDevices(showFeedback = true, showProgress = true) },
                    onRefreshHome = ::refreshHome,
                    onAddDevice = { openAddDevice() },
                    onEditDevice = { deviceId, alias -> editDevice(deviceId, alias) },
                    onRemoveDevice = { deviceId -> removeDevice(deviceId) },
                    onSelectDevice = { deviceId -> selectControlCenterDevice(deviceId) },
                    onStartDevice = { deviceId ->
                        runWithNotificationPermission { startDevice(deviceId) }
                    }
                )
            }
        }
        observeTaskCompletion()
        observeWaterSettlement()
        consumeRecoveryIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRecoveryIntent(intent)
    }

    private fun consumeRecoveryIntent(intent: Intent) {
        val openedForRecovery = intent.getBooleanExtra(EXTRA_OPEN_WATER_RECOVERY, false)
        if (openedForRecovery) {
            intent.removeExtra(EXTRA_OPEN_WATER_RECOVERY)
            ui.post { toast("控制中心出水失败，请检查用水登录信息、设备与签约状态") }
        }
    }

    override fun onResume() {
        super.onResume()
        TaskForegroundService.clearIdleProgressNotification(this)
        updateViewModel.checkAutomatically()
        val accountChanged = viewModel.reloadAccounts()
        if (accountChanged || !hasLoadedHistory) refreshRecentWaterBills()
        refreshCurrentScore()
        updateWidgets()
        fetchDevices(showFeedback = false)
    }

    private fun openWebsite(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {
            toast("没有可打开网页的应用，请先安装浏览器")
        }
    }

    override fun onDestroy() {
        destroyed = true
        scoreGeneration++
        historyGeneration++
        invalidateDeviceSync()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 页面只发送启动请求，真正的任务生命周期由 TaskForegroundService 持有。 */
    private fun runTasksInHome(): Boolean =
        when (TaskForegroundService.start(this)) {
            TaskForegroundService.StartResult.STARTED -> true
            TaskForegroundService.StartResult.ALREADY_RUNNING -> {
                toast("积分任务正在运行，请勿重复启动")
                true
            }
            TaskForegroundService.StartResult.RISK_NOT_CONFIRMED -> {
                toast("请先进入积分任务页，阅读风险提示并确认")
                false
            }
            TaskForegroundService.StartResult.NO_ACCOUNTS -> {
                toast("没有可运行账号，请先完成用水登录或积分登录")
                false
            }
            TaskForegroundService.StartResult.FAILED -> {
                toast("积分任务启动失败，请保持应用在前台后重试")
                false
            }
        }

    /**
     * 先启动用户请求，再申请通知权限；即使权限弹窗期间页面重建，任务也不会丢失。
     */
    private fun runWithNotificationPermission(action: () -> Boolean) {
        if (!action()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationRequestInFlight
        ) {
            notificationRequestInFlight = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observeTaskCompletion() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = TaskRunRepository.state.value.running
                var gained = TaskRunRepository.state.value.totalGained
                TaskRunRepository.state.collect { state ->
                    val finished = wasRunning && !state.running &&
                        (state.result == TaskRunResult.COMPLETED || state.result == TaskRunResult.PARTIAL)
                    if (state.totalGained > gained || finished) {
                        refreshCurrentScore()
                    }
                    wasRunning = state.running
                    gained = state.totalGained
                }
            }
        }
    }

    private fun switchAccount(phone: String) {
        val account = viewModel.selectAccount(phone)
        if (account == null) {
            toast("账号不存在")
            return
        }
        toast("已切换到 $account")
        refreshRecentWaterBills()
        refreshCurrentScore()
        updateWidgets()
    }

    private fun refreshHome() {
        var pendingRequests = 2
        val onRequestComplete = {
            pendingRequests--
            if (pendingRequests == 0) viewModel.setHomeRefreshing(false)
        }

        viewModel.setHomeRefreshing(true)
        refreshRecentWaterBills()
        refreshCurrentScore(onRequestComplete)
        fetchDevices(showFeedback = false, showProgress = true, onComplete = onRequestComplete)
    }

    private fun refreshCurrentScore(onComplete: () -> Unit = {}) {
        val generation = ++scoreGeneration
        val account = AccountStore.getCurrent(this)
        val token = account?.token?.takeIf { it.isNotBlank() } ?: account?.appToken?.takeIf { it.isNotBlank() }
        if (account == null || token.isNullOrBlank()) {
            viewModel.setCurrentScoreData(null, null, null)
            onComplete()
            return
        }
        val (onMission, onLogs, onWallet) = scoreRefreshCallbacks(generation, account, onComplete)
        val walletToken = account.appToken?.takeIf { it.isNotBlank() } ?: token
        IlifeApi.missionLstWithToken(token, !account.hasToken(), onMission)
        IlifeApi.scoreLstWithToken(token, onLogs)
        IlifeApi.walletOwnerWithToken(walletToken, onWallet)
    }

    private fun scoreRefreshCallbacks(
        generation: Int,
        account: Account,
        onComplete: () -> Unit
    ): Triple<IlifeApi.JsonCallback, IlifeApi.JsonCallback, IlifeApi.JsonCallback> {
        var pending = 3
        val shownLoginMessages = mutableSetOf<String>()
        fun callback(useApp: Boolean, checkLogin: Boolean, store: (org.json.JSONObject?) -> Unit) = IlifeApi.JsonCallback { json, _ ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                val current = AccountStore.getCurrent(this)
                if (generation == scoreGeneration && current?.phone == account.phone &&
                    current.token == account.token && current.appToken == account.appToken) {
                    if (checkLogin) LoginStatusStore.record(current, useApp, TokenValidity.from(json))
                    store(json)
                    TokenValidity.expiredMessage(json, useApp)?.let {
                        if (shownLoginMessages.add(it)) toast(it)
                    }
                }
                pending--
                if (pending == 0) onComplete()
            }
        }
        return Triple(
            callback(!account.hasToken(), checkLogin = true) { json ->
                val score = json?.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")
                    ?.optJSONObject("accScoreRsp")?.optInt("validScore")
                viewModel.setCurrentScoreData(score = score)
            },
            callback(!account.hasToken(), checkLogin = false) { json -> viewModel.setCurrentScoreData(logs = json) },
            callback(account.hasAppToken() || !account.hasToken(), checkLogin = account.hasAppToken()) { json ->
                val balance = try {
                    json?.let { WalletResponseParser.dashboardBalance(it, account.eid) }
                } catch (_: IllegalArgumentException) {
                    null
                }
                viewModel.setCurrentWalletData(balance,
                    if (balance == null) "钱包刷新失败，请下拉重试" else null)
            }
        )
    }

    private fun observeWaterSettlement() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsageHistoryStore.waterSettled.collect { accountKey ->
                    val account = AccountStore.getCurrent(this@ConfigActivity)
                    val currentKey = account?.phone?.takeIf(String::isNotBlank) ?: account?.uid
                    if (accountKey == currentKey) refreshCurrentScore()
                }
            }
        }
    }

    private fun refreshRecentWaterBills() {
        hasLoadedHistory = true
        val generation = ++historyGeneration
        val account = AccountStore.getCurrent(this)
        val token = account?.appToken?.takeIf(String::isNotBlank)
        val accountKey = account?.phone?.takeIf(String::isNotBlank) ?: account?.uid
        if (token == null || accountKey.isNullOrBlank()) {
            viewModel.setHistorySyncNote("仅统计已获取的记录")
            return
        }
        fun isCurrent(): Boolean = !destroyed && generation == historyGeneration &&
            AccountStore.getCurrent(this)?.phone == account.phone
        fun failed() = viewModel.setHistorySyncNote("部分记录未同步，下拉可重试")
        // 列表仅提供最近 20 笔订单；逐笔读详情才能包含积分抵扣，不阻塞余额展示。
        fun loadDetails(ids: List<String>, index: Int) {
            if (!isCurrent()) return
            if (index == ids.size) {
                viewModel.setHistorySyncNote("近期账单已同步")
                return
            }
            val id = ids[index]
            IlifeApi.exchangeBillWithToken(token, id) { json, err ->
                runOnUiThread {
                    if (!isCurrent()) return@runOnUiThread
                    if (err != null) {
                        failed()
                        return@runOnUiThread
                    }
                    val consumption = try {
                        WaterBillUiStateFactory.consumption(json, id)
                    } catch (_: IllegalArgumentException) {
                        failed()
                        return@runOnUiThread
                    }
                    if (consumption != null) UsageHistoryStore.record(this, accountKey, consumption)
                    loadDetails(ids, index + 1)
                }
            }
        }
        viewModel.setHistorySyncNote("正在补齐近期消费记录…")
        IlifeApi.billListWithToken(token) { json, err ->
            runOnUiThread {
                if (!isCurrent()) return@runOnUiThread
                if (err != null) {
                    failed()
                    return@runOnUiThread
                }
                val ids = try {
                    WaterBillUiStateFactory.records(json).map { it.id }
                } catch (_: IllegalArgumentException) {
                    failed()
                    return@runOnUiThread
                }
                loadDetails(ids, 0)
            }
        }
    }

    private fun openAddDevice() {
        addDevice.launch(Intent(this, DeviceAddActivity::class.java))
    }

    private fun editDevice(deviceId: String, alias: String) {
        val account = AccountStore.getCurrent(this)
        if (account == null || deviceId.isBlank()) return
        account.setDeviceAlias(deviceId, alias)
        AccountStore.updateCurrent(this, account)
        viewModel.reloadAccounts()
        toast("设备名称已保存")
    }

    private fun removeDevice(deviceId: String) {
        val account = AccountStore.getCurrent(this)
        if (account == null || deviceId.isBlank()) return
        if (WaterControlState.state.value?.deviceId == deviceId || viewModel.uiState.value.stoppingDeviceId == deviceId) {
            toast("请先停止接水，再移除设备")
            return
        }
        invalidateDeviceSync()
        account.forgetDevice(deviceId)
        AccountStore.updateCurrent(this, account)
        viewModel.reloadAccounts()
        updateWidgets()
        toast("设备已从本地移除")

        IlifeApi.deviceFavorite(this, deviceId, true) { _, err ->
            if (err != null) runOnUiThread {
                if (!destroyed) toast("本地已移除，服务器收藏未同步")
            }
        }
    }

    private fun selectControlCenterDevice(deviceId: String) {
        val account = AccountStore.getCurrent(this)
        if (account == null || deviceId.isBlank()) return
        if (account.selectedDeviceId() == deviceId) {
            toast("已是控制中心默认设备")
            return
        }
        account.selectDevice(deviceId)
        AccountStore.updateCurrent(this, account)
        viewModel.reloadAccounts()
        updateWidgets()
        toast("已设为控制中心默认设备")
    }

    private fun invalidateDeviceSync(): Int {
        viewModel.setDeviceSyncing(false)
        return ++deviceSyncGeneration
    }

    /** 拉取主页收藏设备列表；首次同步时自动选择第一台设备。 */
    private fun fetchDevices(
        showFeedback: Boolean,
        showProgress: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        val generation = invalidateDeviceSync()
        val account = AccountStore.getCurrent(this)
        if (account == null || (!account.hasToken() && !account.hasAppToken())) {
            if (showFeedback) toast("请先登录")
            onComplete()
            return
        }

        if (showFeedback) toast("正在同步设备…")
        if (showProgress) viewModel.setDeviceSyncing(true)
        val useApp = account.hasAppToken()
        val token = if (useApp) account.appToken else account.token
        IlifeApi.masterWithToken(token, useApp) { json, err ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                try {
                    applyDeviceSyncResult(generation, account.phone, json, err, showFeedback)
                } finally {
                    if (generation == deviceSyncGeneration) viewModel.setDeviceSyncing(false)
                    onComplete()
                }
            }
        }
    }

    private fun applyDeviceSyncResult(
        generation: Int,
        accountPhone: String,
        json: org.json.JSONObject?,
        err: String?,
        showFeedback: Boolean
    ) {
        if (generation != deviceSyncGeneration) return
        // 使用最新本地账户，保留同步期间新增的设备、别名和默认选择。
        val account = AccountStore.get(this, accountPhone) ?: return
        val feedback = showFeedback && AccountStore.getCurrent(this)?.phone == accountPhone
        if (json == null) {
            if (feedback) toast("同步失败：${err ?: "未知错误"}")
            return
        }
        val code = json.optInt("code", -999)
        when {
            code == -99 -> {
                if (feedback) toast(requireNotNull(TokenValidity.expiredMessage(json, account.hasAppToken())))
                return
            }
            code != 0 -> {
                if (feedback) toast("同步失败：code=$code")
                return
            }
        }
        val favos = json.optJSONObject("data")?.optJSONArray("favos")
        if (favos == null || favos.length() == 0) {
            if (feedback) toast("暂无已收藏设备")
            return
        }
        val devices = mutableListOf<Pair<String, String>>()
        for (index in 0 until favos.length()) {
            favos.optJSONObject(index)?.let { device ->
                device.optString("id", "").takeIf(String::isNotBlank)?.let { id ->
                    devices += id to device.optString("name", "")
                }
            }
        }
        val selectedDevice = account.selectedDeviceId()
        devices.asReversed().forEach { (id, name) -> account.rememberDevice(id, name) }
        (selectedDevice.takeIf(String::isNotBlank) ?: devices.firstOrNull()?.first)
            ?.let(account::selectDevice)
        AccountStore.addOrUpdateKeepingCurrent(this, account)
        if (feedback) toast("已同步 ${devices.size} 台设备")
        viewModel.reloadAccounts()
        updateWidgets()
    }

    private fun startDevice(deviceId: String): Boolean {
        if (viewModel.uiState.value.stoppingDeviceId != null) {
            toast("停止请求正在处理，请稍后再启动")
            return false
        }
        val account = AccountStore.getCurrent(this)
        if (account == null) {
            toast("请先登录")
            return false
        }
        if (!account.hasAppToken()) {
            toast("请用同一手机号再次短信登录，并选择用水登录")
            return false
        }

        if (deviceId.isBlank()) {
            toast("请先选择设备")
            return false
        }

        return when (
            WaterService.start(this, deviceId, AppWidgetManager.INVALID_APPWIDGET_ID)
        ) {
            WaterService.StartResult.STARTED -> true
            WaterService.StartResult.ALREADY_RUNNING -> {
                toast("已有接水会话正在监测，请勿重复启动")
                true
            }
            WaterService.StartResult.FAILED -> {
                toast("设备启动失败，请稍后重试")
                false
            }
        }
    }

    private fun updateWidgets() {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, WaterWidgetProvider::class.java))
        ids.forEach { id -> manager.updateAppWidget(id, WaterWidgetProvider.buildViews(this, id, null)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
