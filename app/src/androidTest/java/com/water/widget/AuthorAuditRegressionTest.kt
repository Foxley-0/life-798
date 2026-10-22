package com.water.widget

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.ui.DashboardUiStateFactory
import com.water.widget.ui.TaskLogFormatter
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real entry points with virtual accounts; every HTTP request is intercepted locally. */
class AuthorAuditRegressionTest {
    companion object {
        @Volatile private var response: (Connection) -> String = { """{"code":-99}""" }

        @JvmStatic @BeforeClass fun interceptNetwork() {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "http" && protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = Connection(url)
                }
            }
        }

        @JvmStatic @AfterClass fun blockRemainingNetwork() {
            response = { throw java.io.IOException("Offline audit completed") }
        }

        private class Connection(url: URL) : HttpURLConnection(url) {
            val body = ByteArrayOutputStream()
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getOutputStream() = body
            override fun getInputStream(): java.io.InputStream {
                if (!url.toString().startsWith(BuildConfig.API_GATEWAY + "/")) {
                    throw java.io.IOException("Offline audit: other network disabled")
                }
                return ByteArrayInputStream(response(this).toByteArray())
            }
        }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        response = { connection ->
            when {
                connection.url.path.endsWith("/acc/wallet/owner") -> wallets()
                connection.url.path.endsWith("/prd/lst") -> products()
                connection.url.path.endsWith("/acc/score/acc-score") -> """{"code":0,"data":{"score":2000}}"""
                connection.url.path.endsWith("/acc/score/mission-lst") -> """{"code":0,"data":{"missions":[],"accScoreRsp":{"validScore":2000,"totalScore":2000,"limits":[]}}}"""
                connection.url.path.endsWith("/bill/lst-owner") || connection.url.path.endsWith("/acc/score/score-lst") -> """{"code":0,"data":[]}"""
                connection.url.path.endsWith("/ui/app/master") -> """{"code":0,"data":{"favos":[]}}"""
                else -> """{"code":-99}"""
            }
        }
    }

    @After fun cleanup() {
        WaterControlState.clear(77)
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
    }

    @Test fun accountChangeClearsRechargeDraftAndOnlyFreshWalletCanBeSubmitted() {
        val accountA = Account("offline-a").apply { appToken = "offline-app-a" }
        val accountB = Account("offline-b").apply { appToken = "offline-app-b" }
        AccountStore.addOrUpdate(context, accountB)
        AccountStore.addOrUpdate(context, accountA)
        val pendingWallet = CountDownLatch(1)
        val releaseWallet = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        var submittedToken = ""
        var submittedBody = ""
        val normalResponse = response
        response = { connection ->
            when {
                connection.url.path.endsWith("/acc/wallet/owner") && connection.getRequestProperty("Authorization") == "offline-app-b" -> {
                    pendingWallet.countDown()
                    check(releaseWallet.await(10, TimeUnit.SECONDS))
                    wallets().replace("old-", "new-")
                }
                connection.url.path.endsWith("/prd/lst") && connection.getRequestProperty("Authorization") == "offline-app-b" -> products().replace("old-", "new-")
                connection.url.path.endsWith("/bill/save") -> {
                    submittedToken = connection.getRequestProperty("Authorization")
                    submittedBody = connection.body.toString("UTF-8")
                    submitted.countDown()
                    """{"code":-99}"""
                }
                else -> normalResponse(connection)
            }
        }
        try {
            ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
                awaitCondition {
                    var loaded = false
                    scenario.onActivity { loaded = !walletState(it).loading && walletState(it).products.isNotEmpty() }
                    loaded
                }
                scenario.onActivity { setWalletState(it, walletState(it).copy(selectedProductId = "old-product",
                    showRechargeConfirmation = true, pendingRechargeOrderId = "offline-old-order")) }
                scenario.moveToState(Lifecycle.State.STARTED)
                AccountStore.setCurrent(context, accountB.phone)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(pendingWallet.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertTrue(walletState(activity).loading)
                    assertFalse(walletState(activity).showRechargeConfirmation)
                    assertNull(walletState(activity).pendingRechargeOrderId)
                    assertTrue(walletState(activity).wallets.isEmpty())
                    assertTrue(walletState(activity).products.isEmpty())
                    WalletActivity::class.java.getDeclaredMethod("startRecharge").apply { isAccessible = true }.invoke(activity)
                }
                assertFalse(submitted.await(250, TimeUnit.MILLISECONDS))
                releaseWallet.countDown()
                awaitCondition {
                    var loaded = false
                    scenario.onActivity { loaded = !walletState(it).loading && walletState(it).products.isNotEmpty() }
                    loaded
                }
                scenario.onActivity { activity ->
                    assertNull(walletState(activity).selectedProductId)
                    setWalletState(activity, walletState(activity).copy(selectedProductId = "new-product"))
                    WalletActivity::class.java.getDeclaredMethod("startRecharge").apply { isAccessible = true }.invoke(activity)
                }
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                assertEquals("offline-app-b", submittedToken)
                val body = JSONObject(submittedBody)
                assertEquals("new-owner", body.getJSONObject("owner").getString("id"))
                assertEquals("new-ep", body.getJSONObject("ep").getString("id"))
                instrumentation.waitForIdleSync()
            }
        } finally {
            releaseWallet.countDown()
        }
    }

    @Test fun pendingRechargeCanBeQueriedWithoutRecreatingAnOrderOrClaimingBalanceRefreshed() {
        AccountStore.addOrUpdate(context, Account("offline-a").apply { appToken = "offline-app" })
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            awaitCondition {
                var loaded = false
                scenario.onActivity { loaded = !walletState(it).loading && walletState(it).products.isNotEmpty() }
                loaded
            }
            val normal = response
            val billStatus = java.util.concurrent.atomic.AtomicInteger(2)
            val createdOrders = java.util.concurrent.atomic.AtomicInteger()
            response = { connection -> when {
                connection.url.path.endsWith("/bill/view-full") ->
                    """{"code":0,"data":{"bill":{"id":"offline-order","status":${billStatus.get()}}}}"""
                connection.url.path.endsWith("/bill/save") -> { createdOrders.incrementAndGet(); """{"code":-99}""" }
                connection.url.path.endsWith("/acc/wallet/owner") -> throw java.io.IOException("offline refresh failure")
                else -> normal(connection)
            } }
            scenario.onActivity {
                setWalletState(it, walletState(it).copy(pendingRechargeOrderId = "offline-order", selectedProductId = "old-product"))
                WalletActivity::class.java.getDeclaredMethod("checkRecharge").apply { isAccessible = true }.invoke(it)
            }
            awaitCondition {
                var finished = false
                scenario.onActivity { finished = !walletState(it).loading }
                finished
            }
            scenario.onActivity {
                assertEquals("offline-order", walletState(it).pendingRechargeOrderId)
                WalletActivity::class.java.getDeclaredMethod("startRecharge").apply { isAccessible = true }.invoke(it)
            }
            assertEquals(0, createdOrders.get())
            scenario.recreate()
            awaitCondition {
                var restored = false
                scenario.onActivity { restored = !walletState(it).loading && walletState(it).pendingRechargeOrderId == "offline-order" }
                restored
            }
            billStatus.set(3)
            scenario.onActivity {
                WalletActivity::class.java.getDeclaredMethod("checkRecharge").apply { isAccessible = true }.invoke(it)
            }
            awaitCondition {
                var failed = false
                scenario.onActivity { failed = walletState(it).errorMessage != null && !walletState(it).loading }
                failed
            }
            scenario.onActivity {
                val state = walletState(it)
                assertNull(state.pendingRechargeOrderId)
                assertTrue(state.errorMessage!!.contains("充值订单已付款"))
                assertFalse(state.errorMessage.contains("余额已刷新"))
            }
        }
    }

    @Test fun billsLoadPagesOnlyOnDemandAndKeepRowsWhenTheNextPageFails() {
        AccountStore.addOrUpdate(context, Account("offline-a").apply { appToken = "offline-app" })
        val normal = response
        val nextRequests = java.util.concurrent.atomic.AtomicInteger()
        var failNext = true
        val first = org.json.JSONArray()
        repeat(20) { index -> first.put(JSONObject().put("id", if (index == 0) "water-0" else "recharge-$index")
            .put("type", 91).put("cata", if (index == 0) 6 else 1).put("status", 3).put("utime", 1_800_000_000_000L)) }
        val second = org.json.JSONArray().put(first.getJSONObject(0)).put(
            JSONObject(first.getJSONObject(0).toString()).put("id", "water-1"))
        response = { connection ->
            if (!connection.url.path.endsWith("/bill/lst-owner")) normal(connection)
            else if (connection.url.query.contains("page=0")) JSONObject().put("code", 0).put("data", first).toString()
            else {
                nextRequests.incrementAndGet()
                if (failNext) throw java.io.IOException("offline page failure")
                JSONObject().put("code", 0).put("data", second).toString()
            }
        }
        ActivityScenario.launch(ScoreActivity::class.java).use { scenario ->
            fun states(activity: ScoreActivity): List<com.water.widget.ui.ScoreUiState> {
                @Suppress("UNCHECKED_CAST")
                return ScoreActivity::class.java.getDeclaredMethod("getStates").apply { isAccessible = true }
                    .invoke(activity) as List<com.water.widget.ui.ScoreUiState>
            }
            fun awaitLoaded() = awaitCondition {
                var loaded = false
                scenario.onActivity { loaded = states(it).firstOrNull()?.billLoading == false }
                loaded
            }
            fun loadNext() = scenario.onActivity {
                ScoreActivity::class.java.getDeclaredMethod("loadMoreBills", String::class.java)
                    .apply { isAccessible = true }.invoke(it, "offline-a")
            }
            awaitLoaded()
            assertEquals(0, nextRequests.get())
            loadNext()
            awaitLoaded()
            scenario.onActivity {
                assertEquals(0, states(it).single().billPage)
                assertEquals(listOf("water-0"), states(it).single().bills.map { bill -> bill.id })
                assertTrue(states(it).single().hasMoreBills)
            }
            failNext = false
            loadNext()
            awaitLoaded()
            scenario.onActivity {
                assertEquals(1, states(it).single().billPage)
                assertEquals(listOf("water-0", "water-1"), states(it).single().bills.map { bill -> bill.id })
                assertFalse(states(it).single().hasMoreBills)
            }
            loadNext()
            assertEquals(2, nextRequests.get())
        }
    }

    @Test fun pendingImportDoesNotRestoreDeletedAccount() {
        val phone = "13800000000"
        AccountStore.addOrUpdate(context, Account(phone).apply { appToken = "offline-old-app"; selectDevice("old-device") })
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val normalResponse = response
        response = { connection ->
            if (connection.getRequestProperty("Authorization") != "offline-import-app") normalResponse(connection)
            else when {
                connection.url.path.endsWith("/acc/view-info") -> {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    """{"code":0,"data":{"id":"offline-uid","phone":"13800000000"}}"""
                }
                connection.url.path.endsWith("/acc/score/mission-lst") -> """{"code":0,"data":{"missions":[{"stype":[1]}]}}"""
                else -> """{"code":0,"data":{}}"""
            }
        }
        try {
            ActivityScenario.launch(AccountsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val snapshot = AccountStore.get(context, phone)
                    AccountsActivity::class.java.getDeclaredMethod("verifyAndSaveTokens", String::class.java,
                        String::class.java, String::class.java, Account::class.java, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(activity, phone, "", "offline-import-app", snapshot, false)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                AccountStore.remove(context, phone)
                assertNull(AccountStore.get(context, phone))
                release.countDown()
                awaitCondition {
                    var complete = false
                    scenario.onActivity { activity ->
                        complete = (AccountsActivity::class.java.getDeclaredField("pendingImports")
                            .apply { isAccessible = true }.get(activity) as Map<*, *>).isEmpty()
                    }
                    complete
                }
                assertNull(AccountStore.get(context, phone))
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun pendingImportPreservesDeviceChangesMadeDuringVerification() {
        val phone = "13800000000"
        AccountStore.addOrUpdate(context, Account(phone).apply { appToken = "offline-old-app"; selectDevice("old-device") })
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val normalResponse = response
        response = { connection ->
            if (connection.getRequestProperty("Authorization") != "offline-import-app") normalResponse(connection)
            else when {
                connection.url.path.endsWith("/acc/view-info") -> {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    """{"code":0,"data":{"id":"offline-uid","phone":"13800000000"}}"""
                }
                connection.url.path.endsWith("/acc/score/mission-lst") -> """{"code":0,"data":{"missions":[{"stype":[1]}]}}"""
                else -> """{"code":0,"data":{}}"""
            }
        }
        try {
            ActivityScenario.launch(AccountsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    AccountsActivity::class.java.getDeclaredMethod("verifyAndSaveTokens", String::class.java,
                        String::class.java, String::class.java, Account::class.java, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(activity, phone, "", "offline-import-app", AccountStore.get(context, phone), false)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                AccountStore.addOrUpdate(context, AccountStore.get(context, phone).apply { selectDevice("new-device") })
                release.countDown()
                awaitCondition { AccountStore.get(context, phone)?.appToken == "offline-import-app" }
                assertEquals("new-device", AccountStore.get(context, phone).selectedDeviceId())
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun runningDeviceCannotBeRemovedFromStopControls() {
        val account = Account("offline-a").apply { appToken = "offline-app"; selectDevice("running-device") }
        AccountStore.addOrUpdate(context, account)
        ActivityScenario.launch(ConfigActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                WaterControlState.begin(77, account.phone, "running-device")
                WaterControlState.started(77)
                ConfigActivity::class.java.getDeclaredMethod("removeDevice", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, "running-device")
                assertEquals("running-device", WaterControlState.state.value?.deviceId)
                assertEquals("running-device", DashboardUiStateFactory.from(AccountStore.getCurrent(context), 1).devices.single().id)
            }
        }
    }

    @Test fun serviceLogsUseAccountKeysEvenWhenDisplayNamesMatch() {
        val a = Account("offline-a").apply { name = "同名账号" }
        val b = Account("offline-b").apply { name = "同名账号" }
        assertNotEquals(a.phone, b.phone)
        response = { """{"code":0,"data":{"totalScore":105}}""" }
        val verify = TaskForegroundService::class.java.getDeclaredMethod("verifyAccountIncrease",
            Account::class.java, TaskPointsTracker::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Function1::class.java).apply { isAccessible = true }
        var service: TaskForegroundService? = null
        try {
            instrumentation.runOnMainSync {
                service = TaskForegroundService().also { current ->
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }.invoke(current, context)
                    current.onCreate()
                    TaskForegroundService::class.java.getDeclaredField("running")
                        .apply { isAccessible = true; setBoolean(current, true) }
                    TaskRunRepository.start(listOf(TaskLaneState(1, 1, accountCount = 2)))
                }
            }
            listOf(a, b).forEach { account ->
                account.appToken = "offline-app"
                val done = CountDownLatch(1)
                val callback: (Int?) -> Unit = { done.countDown() }
                instrumentation.runOnMainSync { verify.invoke(service, account, TaskPointsTracker(100), 1, 0, false, callback) }
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            val groups = TaskLogFormatter.groupByAccount(TaskRunRepository.state.value.logs)
            assertEquals(setOf(a.phone, b.phone), groups.map { it.account }.toSet())
            val state = com.water.widget.ui.TaskUiStateFactory.from(listOf(a, b), true, 10, TaskRunRepository.state.value.logs)
            assertEquals(a.toString(), state.accountLabels[a.phone])
            assertEquals(b.toString(), state.accountLabels[b.phone])
        } finally {
            instrumentation.runOnMainSync { service?.onDestroy() }
        }
    }

    private fun walletState(activity: WalletActivity): WalletUiState =
        WalletActivity::class.java.getDeclaredMethod("getState").apply { isAccessible = true }.invoke(activity) as WalletUiState

    private fun setWalletState(activity: WalletActivity, state: WalletUiState) {
        WalletActivity::class.java.getDeclaredMethod("setState", WalletUiState::class.java)
            .apply { isAccessible = true }.invoke(activity, state)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Audit condition timed out" }
            Thread.sleep(25)
        }
    }

    private fun wallets() = """{"code":0,"data":{"aw":{"ep":{"id":"old-ep","name":"旧钱包"},"owner":{"id":"old-owner"},"total":10},"eps":[]}}"""
    private fun products() = """{"code":0,"data":[{"id":"old-product","name":"旧档位","curPrice":1}]}"""
}
