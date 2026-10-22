package com.water.widget

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.water.widget.ui.LoginPlatform
import com.water.widget.ui.AccountCardState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 全部 HTTP 由进程内替身处理，未匹配的请求也不会连接外网。 */
class AuditRegressionTest {
    companion object {
        @Volatile private var response: (HttpURLConnection) -> String = { error("Unexpected request") }

        @JvmStatic @AfterClass fun blockRemainingNetwork() {
            response = { throw java.io.IOException("Offline audit: network disabled") }
        }

        @JvmStatic @BeforeClass fun interceptNetwork() {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https" && protocol != "http") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getResponseCode() = 200
                        override fun getOutputStream() = ByteArrayOutputStream()
                        override fun getInputStream(): java.io.InputStream {
                            if (!url.toString().startsWith(BuildConfig.API_GATEWAY + "/")) {
                                throw java.io.IOException("Offline audit: non-business network disabled")
                            }
                            return ByteArrayInputStream(response(this).toByteArray())
                        }
                    }
                }
            }
        }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun loginResponseUsesSubmittedAccountAndPlatformDespiteFormChanges() {
        val uidDone = CountDownLatch(1)
        response = { connection ->
            check(connection.url.path.endsWith("/acc/view-info"))
            uidDone.countDown()
            """{"code":-99}"""
        }
        val prefs = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        try {
            ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    AccountStore.addOrUpdate(context, Account("submitted").apply { token = "existing-mini" })
                    AccountStore.addOrUpdate(context, Account("edited").apply { appToken = "keep-edited" })
                    LoginActivity::class.java.getDeclaredField("phone").apply { isAccessible = true; set(activity, "edited") }
                    LoginActivity::class.java.getDeclaredField("platform").apply { isAccessible = true; set(activity, LoginPlatform.ALIPAY) }
                    val result = JSONObject().put("code", 0).put("data", JSONObject().put("al",
                        JSONObject().put("token", "new-app").put("uid", "uid")))
                    LoginActivity::class.java.getDeclaredMethod("handleLoginResult", JSONObject::class.java,
                        String::class.java, LoginPlatform::class.java).apply { isAccessible = true }
                        .invoke(activity, result, "submitted", LoginPlatform.APP)
                    assertEquals("new-app", AccountStore.get(context, "submitted").appToken)
                    assertEquals("existing-mini", AccountStore.get(context, "submitted").token)
                    assertEquals("keep-edited", AccountStore.get(context, "edited").appToken)
                }
            }
            assertTrue(uidDone.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally {
            prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        }
    }

    @Test fun concurrentAccountWritesAndUidMergePreserveOtherChanges() {
        val prefs = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        try {
            val start = CountDownLatch(1)
            val workers = (0..1).map { worker -> Thread {
                start.await()
                repeat(20) { index ->
                    AccountStore.addOrUpdateKeepingCurrent(context, Account("account-$worker-$index").apply { appToken = "session" })
                }
            }.apply { start() } }
            start.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertEquals(40, AccountStore.list(context).size)
            val a = AccountStore.get(context, "account-0-0")
            a.selectDevice("new-device")
            AccountStore.addOrUpdate(context, a)
            AccountStore.setCurrent(context, "account-1-0")
            AccountStore.updateUid(context, a.phone, "session", "new-uid")
            assertEquals("new-device", AccountStore.get(context, a.phone).selectedDeviceId())
            assertEquals("new-uid", AccountStore.get(context, a.phone).uid)
            assertEquals("account-1-0", AccountStore.getCurrent(context).phone)
            a.appToken = "new-session"
            a.uid = "current-uid"
            AccountStore.addOrUpdateKeepingCurrent(context, a)
            AccountStore.updateUid(context, a.phone, "session", "stale-uid")
            assertEquals("current-uid", AccountStore.get(context, a.phone).uid)
            AccountStore.remove(context, a.phone)
            AccountStore.updateUid(context, a.phone, "session", "deleted-uid")
            assertNull(AccountStore.get(context, a.phone))
        } finally {
            prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        }
    }

    @Test fun expiredMiniDoesNotBlockAppAndCodeZeroWithoutCreditIsNotCounted() {
        runTasks(expiredMini = true, used = 0, credited = 0, expectedSubmissions = 1)
    }

    @Test fun loginChecksAreIndependentAndDoNotDeleteExpiredCredentials() {
        val prefs = context.getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        val requests = AtomicInteger()
        val account = Account("13800000000").apply {
            token = "expired-synthetic-mini"
            appToken = "valid-synthetic-app"
            uid = "synthetic-user-id"
            selectDevice("keep-device")
        }
        AccountStore.addOrUpdate(context, account)
        response = { connection ->
            requests.incrementAndGet()
            when (connection.url.path.removePrefix(URL(BuildConfig.API_GATEWAY).path)) {
                "/acc/score/mission-lst" -> """{"code":-99,"msg":"登录状态已过期"}"""
                "/acc/stat" -> """{"code":0,"data":{"al":{"token":"different-synthetic-app"}}}"""
                else -> error("Unexpected login check")
            }
        }
        try {
            ActivityScenario.launch(AccountsActivity::class.java).use { scenario ->
                var card: AccountCardState? = null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                do {
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST")
                        val cards = AccountsActivity::class.java.getDeclaredMethod("getAccounts")
                            .apply { isAccessible = true }.invoke(activity) as List<AccountCardState>
                        card = cards.single()
                    }
                    if (card?.alipayStatus == TokenValidity.EXPIRED && card.appStatus == TokenValidity.VALID) break
                    Thread.sleep(20)
                } while (System.nanoTime() < deadline)
                assertEquals(TokenValidity.EXPIRED, card?.alipayStatus)
                assertEquals(TokenValidity.VALID, card?.appStatus)
                assertEquals(2, requests.get())
                assertEquals(account.token, AccountStore.get(context, account.phone).token)
                assertEquals(account.appToken, AccountStore.get(context, account.phone).appToken)
                assertEquals("keep-device", AccountStore.get(context, account.phone).selectedDeviceId())
            }
            ActivityScenario.launch<LoginActivity>(LoginActivity.reloginIntent(context, account.phone, false)).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(LoginPlatform.ALIPAY, LoginActivity::class.java.getDeclaredField("platform")
                        .apply { isAccessible = true }.get(activity))
                    assertEquals(account.phone, LoginActivity::class.java.getDeclaredField("phone")
                        .apply { isAccessible = true }.get(activity))
                }
            }
            assertEquals(2, requests.get())
        } finally {
            prefs.edit().clear().putBoolean("migrated_v3", true).commit()
        }
    }

    @Test fun serverCompletedLimitPreventsResubmission() {
        runTasks(expiredMini = false, used = 1, credited = 0, expectedSubmissions = 0)
    }

    @Test fun creditIsVerifiedImmediatelyAndFinalReadDoesNotDuplicateIt() {
        runTasks(expiredMini = false, used = 0, credited = 7, expectedSubmissions = 1)
    }

    @Test fun failedFinalVerificationDoesNotInventPoints() {
        runTasks(expiredMini = false, used = 1, credited = 0, expectedSubmissions = 0, verificationFails = true)
        assertTrue(TaskRunRepository.state.value.logs.any { it.contains("到账核验失败") })
    }

    @Test fun failedFinalVerificationPreservesImmediateCredit() {
        runTasks(expiredMini = false, used = 0, credited = 7, expectedSubmissions = 1, finalReadFails = true)
    }

    @Test fun persistentRateLimitIsRetriedOnceThenOtherMissionRuns() {
        runTasks(expiredMini = false, used = 0, credited = 7, expectedSubmissions = 3, limited = true)
        assertTrue(TaskRunRepository.state.value.logs.any { it.contains("重试仍受限") })
    }

    @Test fun midRunTokenExpiryMarksPartialButOtherLoginCanStillVerify() {
        response = { connection ->
            check(connection.url.path.endsWith("/acc/score/acc-score"))
            if (connection.getRequestProperty("ApplicationType") == "1,5") """{"code":-99}"""
            else """{"code":0,"data":{"totalScore":107}}"""
        }
        val account = Account("synthetic").apply { token = "mini"; appToken = "app" }
        val points = TaskPointsTracker(100)
        val verify = TaskForegroundService::class.java.getDeclaredMethod("verifyAccountIncrease",
            Account::class.java, TaskPointsTracker::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Function1::class.java)
            .apply { isAccessible = true }
        var service: TaskForegroundService? = null
        try {
            instrumentation.runOnMainSync {
                service = TaskForegroundService().also { current ->
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }.invoke(current, context)
                    current.onCreate()
                    TaskForegroundService::class.java.getDeclaredField("running")
                        .apply { isAccessible = true; setBoolean(current, true) }
                    TaskRunRepository.start(listOf(TaskLaneState(laneId = 1, laneCount = 1, accountCount = 1)))
                }
            }
            repeat(2) { round ->
                val complete = CountDownLatch(1)
                var reported: Int? = -1
                val callback: (Int?) -> Unit = { reported = it; complete.countDown() }
                instrumentation.runOnMainSync { verify.invoke(service, account, points, 1, 0, false, callback) }
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                if (round == 0) assertNull(reported) else assertEquals(7, reported)
            }
            assertEquals("", account.token)
            assertEquals("app", account.appToken)
            assertEquals(7, TaskRunRepository.state.value.totalGained)
            assertTrue(TaskForegroundService::class.java.getDeclaredField("hadFailures")
                .apply { isAccessible = true }.getBoolean(service))
        } finally {
            instrumentation.runOnMainSync { service?.onDestroy() }
        }
    }

    private fun runTasks(expiredMini: Boolean, used: Int, credited: Int, expectedSubmissions: Int,
                         verificationFails: Boolean = false, finalReadFails: Boolean = false, limited: Boolean = false) {
        val submits = AtomicInteger()
        val scoreReads = AtomicInteger()
        response = { connection ->
            when (connection.url.path.removePrefix(URL(BuildConfig.API_GATEWAY).path)) {
                "/acc/score/mission-lst" -> if (connection.getRequestProperty("ApplicationType") == "1,5") {
                    check(expiredMini)
                    """{"code":-99,"msg":"expired"}"""
                } else {
                    check(connection.getRequestProperty("ApplicationType") == "1,1")
                    val missions = JSONArray().put(JSONObject().put("refId", "test-ad")
                        .put("name", "test task").put("score", 10).put("limit", if (limited) 3 else 1))
                    if (limited) missions.put(JSONObject().put("refId", "other-ad")
                        .put("name", "other task").put("score", 10).put("limit", 1))
                    JSONObject().put("code", 0).put("data", JSONObject()
                        .put("accScoreRsp", JSONObject().put("totalScore", 100)
                            .put("limits", JSONArray().put(JSONObject().put("refId", "test-ad").put("limit", 0))))
                        .put("missions", missions)).toString()
                }
                "/acc/score/acc-score" -> {
                    val read = scoreReads.incrementAndGet()
                    if (read == 1) """{"code":0,"data":{"totalScore":100,"limits":[{"refId":"test-ad","limit":$used}]}}"""
                    else if (verificationFails || (finalReadFails && read > 2)) """{"code":-99}""" else
                        """{"code":0,"data":{"totalScore":${100 + credited}}}"""
                }
                "/acc/score/score-send" -> {
                    check(connection.getRequestProperty("ApplicationType") == "1,1")
                    val submit = submits.incrementAndGet()
                    if (limited && submit <= 2) """{"code":-98,"msg":"请求过于频繁"}"""
                    else """{"code":0}"""
                }
                else -> error("Unexpected request: "+connection.url.path)
            }
        }
        val complete = CountDownLatch(1)
        val verified = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Main)
        var service: TaskForegroundService? = null
        var reported: Int? = -1
        instrumentation.runOnMainSync {
            service = TaskForegroundService().also { current ->
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(current, context)
                current.onCreate()
                TaskForegroundService::class.java.getDeclaredField("running").apply { isAccessible = true; setBoolean(current, true) }
                TaskRunRepository.start(listOf(TaskLaneState(laneId = 1, laneCount = 1, accountCount = 1)))
                scope.launch {
                    TaskRunRepository.state.collect { if (credited > 0 && it.totalGained == credited) verified.countDown() }
                }
                TaskExecutionCoordinator.tokenRateLimiter.clear()
                val callback: (Int?) -> Unit = { reported = it; complete.countDown() }
                val account = Account("synthetic").apply {
                    uid = "synthetic-uid-12345678"
                    appToken = "synthetic-app-12345678"
                    if (expiredMini) token = "expired-mini-12345678"
                }
                TaskForegroundService::class.java.getDeclaredMethod("loadAndRunAccountTasks", Account::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Function1::class.java)
                    .apply { isAccessible = true }.invoke(current, account, 1, 0, callback)
            }
        }
        try {
            if (credited > 0 && !limited) {
                assertTrue("Credit was not verified before the task wait", verified.await(5, TimeUnit.SECONDS))
                assertEquals(1L, complete.count)
            }
            assertTrue("Task did not finish", complete.await(if (limited) 150 else 45, TimeUnit.SECONDS))
            assertEquals(expectedSubmissions, submits.get())
            assertEquals(if (expectedSubmissions > 0) 3 else 2, scoreReads.get())
            if (verificationFails || finalReadFails) assertNull(reported) else assertEquals(credited, reported)
            assertEquals(credited, TaskRunRepository.state.value.totalGained)
            assertFalse(TaskRunRepository.state.value.logs.any { it.contains("成功 +10") })
        } finally {
            instrumentation.runOnMainSync { service?.onDestroy() }
            scope.cancel()
        }
    }
}
