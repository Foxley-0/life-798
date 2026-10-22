package com.water.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.water.widget.ui.ScoreDashboardScreen
import com.water.widget.ui.ScoreUiState
import com.water.widget.ui.ScoreUiStateFactory
import com.water.widget.ui.WaterBillUiStateFactory
import com.water.widget.ui.WaterTheme

/** 按账号展示积分明细与接水消费；列表展示时加载支付金额与明细。 */
class ScoreActivity : ComponentActivity() {
    private var states by mutableStateOf<List<ScoreUiState>>(emptyList())
    private var accounts = emptyList<Account>()
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                ScoreDashboardScreen(
                    states = states,
                    refreshing = states.any { it.scoreLoading || it.billLoading },
                    onRefresh = ::refreshScores,
                    onLoadBill = ::loadBill,
                    onBack = ::finish,
                    onLoadMoreBills = ::loadMoreBills
                )
            }
        }
        refreshScores()
    }

    private fun refreshScores() {
        val generation = ++refreshGeneration
        accounts = AccountStore.list(this).filter { it.hasToken() || it.hasAppToken() }
        states = accounts.map { ScoreUiStateFactory.loading(it).copy(
            billLoading = it.hasAppToken(),
            billMessage = if (it.hasAppToken()) "正在加载接水记录…" else "完成设备登录后可查看接水记录"
        ) }
        accounts.forEachIndexed { index, account ->
            val token = account.token?.takeIf { it.isNotBlank() } ?: account.appToken
            IlifeApi.missionLstWithToken(token, !account.hasToken()) { missionJson, missionErr ->
                IlifeApi.scoreLstWithToken(token) { scoreJson, scoreErr ->
                    runOnUiThread {
                        updateState(generation, index) { current ->
                            val loginError = TokenValidity.expiredMessage(missionJson, !account.hasToken())
                                ?: TokenValidity.expiredMessage(scoreJson, !account.hasToken())
                            ScoreUiStateFactory.from(account, missionJson, scoreJson, loginError ?: missionErr ?: scoreErr).copy(
                                bills = current.bills, billLoading = current.billLoading, billMessage = current.billMessage,
                                billPage = current.billPage, hasMoreBills = current.hasMoreBills
                            )
                        }
                    }
                }
            }
            if (account.hasAppToken()) loadBillPage(index, generation, 0)
        }
    }

    private fun loadMoreBills(accountKey: String) {
        val index = states.indexOfFirst { it.accountKey == accountKey }
        if (index < 0) return
        val current = states[index]
        if (current.billLoading || !current.hasMoreBills) return
        loadBillPage(index, refreshGeneration, current.billPage + 1)
    }

    private fun loadBillPage(index: Int, generation: Int, page: Int) {
        updateState(generation, index) { it.copy(billLoading = true) }
        IlifeApi.billListWithToken(accounts[index].appToken, page) { json, err ->
            runOnUiThread {
                updateState(generation, index) { current ->
                    try {
                        require(TokenValidity.from(json) != TokenValidity.EXPIRED) {
                            requireNotNull(TokenValidity.expiredMessage(json, true))
                        }
                        require(err == null) { "接水记录获取失败，请重试" }
                        val records = WaterBillUiStateFactory.records(json)
                        val hasMore = json!!.getJSONArray("data").length() == 20
                        val bills = (current.bills + records).distinctBy { it.id }
                        current.copy(bills = bills, billLoading = false, billPage = page, hasMoreBills = hasMore,
                            billMessage = when {
                                bills.isEmpty() && !hasMore -> "暂无已完成的接水记录"
                                bills.isEmpty() -> "已读取的账单中暂无接水消费，可继续加载"
                                hasMore -> "已加载 ${page + 1} 页账单 · 仅显示接水消费"
                                else -> "已加载全部可查询账单 · 仅显示接水消费"
                            })
                    } catch (error: IllegalArgumentException) {
                        current.copy(billLoading = false, billMessage = error.message.orEmpty())
                    }
                }
            }
        }
    }

    private fun loadBill(accountKey: String, billId: String) {
        val index = states.indexOfFirst { it.accountKey == accountKey }
        if (index < 0) return
        val record = states[index].bills.firstOrNull { it.id == billId } ?: return
        if (record.loading || record.payment != null) return
        val generation = refreshGeneration
        val token = accounts[index].appToken
        updateState(generation, index) { current -> current.copy(bills = current.bills.map {
            if (it.id == billId) it.copy(loading = true, error = null) else it
        }) }
        IlifeApi.exchangeBillWithToken(token, billId) { json, err ->
            runOnUiThread {
                updateState(generation, index) { current -> current.copy(bills = current.bills.map {
                    if (it.id != billId) it else try {
                        require(err == null) { "支付明细获取失败，请重试" }
                        it.copy(payment = WaterBillUiStateFactory.payment(json, billId), loading = false)
                    } catch (error: IllegalArgumentException) {
                        it.copy(loading = false, error = error.message)
                    }
                }) }
            }
        }
    }

    private fun updateState(generation: Int, index: Int, transform: (ScoreUiState) -> ScoreUiState) {
        if (isDestroyed || generation != refreshGeneration) return
        states = states.toMutableList().also { it[index] = transform(it[index]) }
    }
}
