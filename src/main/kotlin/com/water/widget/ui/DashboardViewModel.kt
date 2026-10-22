package com.water.widget.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.water.widget.Account
import com.water.widget.IlifeApi
import com.water.widget.WaterControlState
import com.water.widget.WaterControlSession
import com.water.widget.WaterService
import com.water.widget.WaterUsageSettings
import com.water.widget.AccountStore
import com.water.widget.LoginStatusStore
import com.water.widget.TokenValidity
import com.water.widget.TaskRunRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val summary: DashboardSummaryUiState = DashboardUiStateFactory.from(null, 0),
    val accounts: List<DashboardAccountUiState> = emptyList(),
    val tasks: TaskUiState = TaskUiStateFactory.from(emptyList(), false, 0, emptyList()),
    val homeRefreshing: Boolean = false,
    val deviceSyncing: Boolean = false,
    val waterControl: WaterControlSession? = null,
    val stoppingDeviceId: String? = null,
    val deviceStopMessage: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var currentScore: Int? = null
    private var currentWalletBalance: Double? = null
    private var currentScoreLogs: JSONObject? = null
    private var currentAccountKey = emptyList<String?>()
    private var walletMessage: String? = null
    private var homeRefreshing = false
    private var deviceSyncing = false
    private var historySyncNote = "仅统计已获取的记录"

    init {
        reloadAccounts()
        viewModelScope.launch { LoginStatusStore.changes.collect { publish() } }
        viewModelScope.launch { WaterControlState.state.collect { publish() } }
        viewModelScope.launch {
            UsageHistoryStore.changes.collect { publish() }
        }
        viewModelScope.launch {
            WaterUsageSettings.changes.collect { publish() }
        }
        viewModelScope.launch {
            TaskRunRepository.state.collect {
                publish()
            }
        }
    }

    fun reloadAccounts(resetScore: Boolean = false): Boolean {
        val account = AccountStore.getCurrent(getApplication<Application>())
        val key = listOf(account?.phone, account?.token, account?.appToken)
        val changed = key != currentAccountKey
        currentAccountKey = key
        if (resetScore || changed) {
            currentScore = null
            currentWalletBalance = null
            currentScoreLogs = null
            walletMessage = null
        }
        publish()
        return changed
    }

    fun setCurrentScoreData(score: Int? = currentScore, logs: JSONObject? = currentScoreLogs, walletBalance: Double? = currentWalletBalance) {
        currentWalletBalance = walletBalance
        currentScore = score
        currentScoreLogs = logs
        publish()
    }

    fun setCurrentWalletData(balance: Double?, message: String?) {
        if (balance != null) currentWalletBalance = balance
        walletMessage = if (message != null && currentWalletBalance != null) "$message（显示上次余额）" else message
        publish()
    }

    fun setHistorySyncNote(note: String) {
        historySyncNote = note
        publish()
    }

    fun setDeviceSyncing(syncing: Boolean) {
        deviceSyncing = syncing
        publish()
    }

    fun setHomeRefreshing(refreshing: Boolean) {
        homeRefreshing = refreshing
        publish()
    }

    fun stopDevice(deviceId: String) {
        if (_uiState.value.stoppingDeviceId != null) return
        val account = AccountStore.getCurrent(getApplication<Application>())
        val error = when {
            account == null || !account.hasAppToken() -> "请先完成设备登录"
            deviceId.isBlank() -> "请先选择设备"
            WaterService.isStarting() -> "设备正在启动，请稍后再停止"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(deviceStopMessage = error) }
            return
        }
        val current = WaterControlState.state.value ?: return
        val accountKey = account!!.phone?.takeIf(String::isNotBlank) ?: account.uid
        if (current.deviceId != deviceId || current.accountKey != accountKey) return
        val token = account.appToken
        _uiState.update { it.copy(stoppingDeviceId = deviceId, deviceStopMessage = null) }
        val context = getApplication<Application>()
        IlifeApi.devEndWithToken(token, deviceId) { _, err ->
            // 收尾不依赖页面生命周期：用户离开页面后，成功停止仍应查询本次账单。
            if (err == null) {
                WaterService.queryAfterManualStop(context, current.reservationId)
            }
            viewModelScope.launch {
                _uiState.update { it.copy(stoppingDeviceId = null, deviceStopMessage = err) }
            }
        }
    }

    fun selectAccount(phone: String): Account? {
        val storeContext = getApplication<Application>()
        val account = AccountStore.get(storeContext, phone) ?: return null
        AccountStore.setCurrent(storeContext, account.phone)
        reloadAccounts(resetScore = true)
        return account
    }

    private fun publish() {
        val storeContext = getApplication<Application>()
        val accounts = AccountStore.list(storeContext)
        val current = AccountStore.getCurrent(storeContext)
        val taskRun = TaskRunRepository.state.value
        val waterEstimate = WaterUsageSettings.read(storeContext)
        val usage = current?.let { account ->
            val accountKey = account.phone?.takeIf(String::isNotBlank)
                ?: account.uid?.takeIf(String::isNotBlank)
                ?: return@let WaterUsageUiState(showWaterEstimate = waterEstimate.visible)
            UsageHistoryStore.mergeAndRead(storeContext, accountKey, currentScoreLogs)
        }
        _uiState.update {
            DashboardUiState(
                summary = DashboardUiStateFactory.from(
                    current,
                    accounts.size,
                    currentScore,
                    currentScoreLogs,
                    usageOverride = usage?.copy(syncNote = historySyncNote),
                    walletBalance = currentWalletBalance,
                    waterEstimate = waterEstimate,
                    walletMessage = walletMessage,
                    appStatus = current?.let { account -> LoginStatusStore.status(account, true) } ?: TokenValidity.UNCHECKED,
                    alipayStatus = current?.let { account -> LoginStatusStore.status(account, false) } ?: TokenValidity.UNCHECKED
                ),
                accounts = DashboardUiStateFactory.accountsFrom(accounts, current?.phone, LoginStatusStore::status),
                tasks = TaskUiStateFactory.from(
                    accounts,
                    taskRun.running,
                    taskRun.totalGained,
                    taskRun.logs,
                    taskRun.lanes,
                    taskRun.result
                ),
                homeRefreshing = homeRefreshing,
                deviceSyncing = deviceSyncing,
                waterControl = WaterControlState.state.value?.takeIf { session ->
                    session.accountKey == (current?.phone?.takeIf(String::isNotBlank) ?: current?.uid)
                },
                stoppingDeviceId = it.stoppingDeviceId,
                deviceStopMessage = it.deviceStopMessage
            )
        }
    }

}
