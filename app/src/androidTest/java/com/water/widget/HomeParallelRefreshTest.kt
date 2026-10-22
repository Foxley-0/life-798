package com.water.widget

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.ui.DashboardViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeParallelRefreshTest {
    @Test
    fun loginRejectionUpdatesHomeAndSwitcherWithoutBeingClearedBySuccessfulLogs() = withHome { activity ->
        val account = Account("home-login-rejected").apply { token = "offline-main"; appToken = "offline-app" }
        AccountStore.addOrUpdate(activity, account)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.reloadAccounts()
        val (onMission, onLogs, onWallet) = callbacks(activity, account) {}
        onMission.onResult(mission(500), null)
        onWallet.onResult(JSONObject().put("code", -99), null)
        onLogs.onResult(JSONObject().put("code", 0).put("data", JSONArray()), null)

        val state = model.uiState.value
        assertEquals("用水登录需重新验证 · 积分登录有效", state.summary.accountSubtitle)
        assertEquals(state.summary.accountSubtitle, state.accounts.single().subtitle)
        assertEquals("需重新验证", state.summary.appLoginStatusText)
        assertEquals("offline-app", AccountStore.getCurrent(activity).appToken)
    }

    @Test
    fun homeRefreshFailureDoesNotClaimLoginIsValidOrExpired() = withHome { activity ->
        val account = Account("home-login-network-failure").apply { token = "offline-main"; appToken = "offline-app" }
        AccountStore.addOrUpdate(activity, account)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.reloadAccounts()
        val (onMission, _, onWallet) = callbacks(activity, account) {}
        onMission.onResult(null, "模拟网络失败")
        onWallet.onResult(null, "模拟网络失败")

        assertEquals("用水登录检测失败，请重试 · 积分登录检测失败，请重试", model.uiState.value.summary.accountSubtitle)
    }

    @Test
    fun walletAndBalancePublishWithoutWaitingForSlowLogs() = withHome { activity ->
        val account = Account("account-a")
        AccountStore.addOrUpdate(activity, account)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        var completions = 0
        val (onMission, onLogs, onWallet) = callbacks(activity, account) { completions++ }

        onWallet.onResult(wallet(), null)
        assertTrue(model.uiState.value.summary.scoreSubtitle.contains("钱包 ¥2.50"))
        onMission.onResult(mission(500), null)
        assertEquals(0, completions)
        assertEquals("≈¥3.00", model.uiState.value.summary.scoreTitle)
        onLogs.onResult(JSONObject().put("code", 0).put("data", JSONArray()), null)

        assertEquals(1, completions)
        assertEquals("≈¥3.00", model.uiState.value.summary.scoreTitle)
    }

    @Test
    fun failedRefreshKeepsSameAccountBalanceWithAnExplicitStaleNote() = withHome { activity ->
        val account = Account("account-a")
        AccountStore.addOrUpdate(activity, account)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.setCurrentScoreData(1000, null, 2.0)
        var completions = 0
        val (onMission, onLogs, onWallet) = callbacks(activity, account) { completions++ }
        onLogs.onResult(null, "模拟网络错误")
        onWallet.onResult(null, "模拟网络错误")
        onMission.onResult(mission(2000), null)

        assertEquals(1, completions)
        assertEquals("≈¥4.00", model.uiState.value.summary.scoreTitle)
        assertTrue(model.uiState.value.summary.scoreSubtitle.contains("显示上次余额"))
        assertTrue(model.uiState.value.summary.scoreSubtitle.contains("积分 2000"))
    }

    @Test
    fun replacingCredentialsClearsBalanceAndRejectsOldCallbacks() = withHome { activity ->
        val old = Account("account-a").apply { appToken = "offline-old" }
        AccountStore.addOrUpdate(activity, old)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.reloadAccounts()
        model.setCurrentScoreData(1000, null, 2.0)
        assertTrue(!model.reloadAccounts())
        assertEquals("≈¥3.00", model.uiState.value.summary.scoreTitle)
        val pending = callbacks(activity, old) {}
        AccountStore.addOrUpdate(activity, Account("account-a").apply { appToken = "offline-new" })
        assertTrue(model.reloadAccounts())
        deliver(pending, 9999)
        assertEquals("--", model.uiState.value.summary.scoreTitle)
        assertEquals("用水登录未检测 · 积分登录未完成", model.uiState.value.summary.accountSubtitle)
    }

    @Test
    fun oldResponsesCannotOverwriteANewerRefreshOrAnotherAccount() = withHome { activity ->
        val account = Account("account-a")
        AccountStore.addOrUpdate(activity, account)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        var completions = 0
        val old = callbacks(activity, account) { completions++ }
        val field = ConfigActivity::class.java.getDeclaredField("scoreGeneration").apply { isAccessible = true }
        field.setInt(activity, field.getInt(activity) + 1)
        val newer = callbacks(activity, account) { completions++ }
        deliver(newer, 700)
        deliver(old, 9999)
        assertEquals("≈¥3.20", model.uiState.value.summary.scoreTitle)
        assertEquals(2, completions)

        val pendingA = callbacks(activity, account) { completions++ }
        AccountStore.addOrUpdate(activity, Account("account-b"))
        model.selectAccount("account-b")
        deliver(pendingA, 9999)
        assertEquals("account-b", AccountStore.getCurrent(activity).phone)
        assertEquals("--", model.uiState.value.summary.scoreTitle)
        assertEquals(3, completions)
    }

    // 页面以空账号启动，随后注入虚拟账号及回调，不请求业务接口。
    private fun withHome(check: (ConfigActivity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("migrated_v3", true).commit()
        context.getSharedPreferences("usage_history_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(ConfigActivity::class.java).use { scenario -> scenario.onActivity(check) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callbacks(activity: ConfigActivity, account: Account, complete: () -> Unit):
        Triple<IlifeApi.JsonCallback, IlifeApi.JsonCallback, IlifeApi.JsonCallback> {
        val generation = ConfigActivity::class.java.getDeclaredField("scoreGeneration")
            .apply { isAccessible = true }.getInt(activity)
        return ConfigActivity::class.java.getDeclaredMethod(
            "scoreRefreshCallbacks", Int::class.javaPrimitiveType, Account::class.java, Function0::class.java
        ).apply { isAccessible = true }.invoke(activity, generation, AccountStore.get(activity, account.phone), complete) as
            Triple<IlifeApi.JsonCallback, IlifeApi.JsonCallback, IlifeApi.JsonCallback>
    }

    private fun deliver(callbacks: Triple<IlifeApi.JsonCallback, IlifeApi.JsonCallback, IlifeApi.JsonCallback>, score: Int) {
        callbacks.first.onResult(mission(score), null)
        callbacks.second.onResult(JSONObject().put("code", 0).put("data", JSONArray()), null)
        callbacks.third.onResult(wallet(), null)
    }

    private fun mission(score: Int) = JSONObject().put("code", 0).put("data",
        JSONObject().put("accScoreRsp", JSONObject().put("validScore", score)))

    private fun wallet() = JSONObject().put("code", 0).put("data", JSONObject().put("aw",
        JSONObject().put("ep", JSONObject().put("id", "ep-test").put("name", "示例钱包"))
            .put("owner", JSONObject().put("id", "owner-test")).put("total", 2.5)))
}
