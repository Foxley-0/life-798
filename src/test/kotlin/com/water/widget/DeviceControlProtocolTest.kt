package com.water.widget

import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DeviceControlProtocolTest {
    companion object {
        private val requests = LinkedBlockingQueue<TestConnection>()
        private var response: (URL) -> String = { """{"code":0}""" }

        @JvmStatic @BeforeClass fun interceptRequests() {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "http" && protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = TestConnection(url)
                }
            }
        }
    }

    private class TestConnection(url: URL) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getOutputStream() = body
        override fun getInputStream(): java.io.InputStream {
            requests.add(this)
            return ByteArrayInputStream(response(url).toByteArray())
        }
    }

    @Before fun resetResponses() {
        requests.clear()
        response = { """{"code":0}""" }
    }

    @Test fun startUsesOfficialHeadersWithoutChangingPaymentParameters() {
        val request = capture { done ->
            IlifeApi.devStartWithToken("offline-app", "offline-device") { _, error -> done(error) }
        }
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertEquals("offline-app", request.getRequestProperty("Authorization"))
        assertEquals("GET", request.requestMethod)
        assertEquals("/api/v1/dev/start", request.url.path)
        assertEquals("did=offline-device&upgrade=true&ptype=91&rcp=false", request.url.query)
    }

    @Test fun stopUsesOfficialHeadersWithoutChangingStopParameters() {
        val request = capture { done ->
            IlifeApi.devEndWithToken("offline-app", "offline-device") { _, error -> done(error) }
        }
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertEquals("offline-app", request.getRequestProperty("Authorization"))
        assertEquals("GET", request.requestMethod)
        assertEquals("/api/v1/dev/end", request.url.path)
        assertEquals("did=offline-device&rcp=false", request.url.query)
    }

    @Test fun walletUsesOfficialAppProtocol() {
        val request = capture { done ->
            IlifeApi.walletOwnerWithToken("offline-app") { _, error -> done(error) }
        }
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertEquals("/api/v1/acc/wallet/owner", request.url.path)
    }

    @Test fun appSmsUsesOfficialHeadersAndKeepsTheCaptchaFields() {
        val request = capture { done ->
            IlifeApi.sendSms("13800000000", "graph", "captcha-key", true) { _, error -> done(error) }
        }
        assertEquals("POST", request.requestMethod)
        assertEquals("/api/v1/acc/login/code", request.url.path)
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertNull(request.getRequestProperty("Authorization"))
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals(3, body.length())
        assertEquals("graph", body.getString("authCode"))
        assertEquals("captcha-key", body.getString("s"))
        assertEquals("13800000000", body.getString("un"))
    }

    @Test fun billingRechargeAndDeviceHomeUseAppHeadersWithoutChangingMiniHeaders() {
        val calls = listOf<(IlifeApi.JsonCallback) -> Unit>(
            { IlifeApi.billListWithToken("offline-app", it) },
            { IlifeApi.exchangeBillWithToken("offline-app", "offline-bill", it) },
            { IlifeApi.rechargeProductsWithToken("offline-app", "offline-ep", it) },
            { IlifeApi.prepayAlipayWithToken("offline-app", "offline-order", it) },
            { IlifeApi.masterWithToken("offline-app", true, it) }
        )
        calls.forEach { call ->
            val request = capture { done -> call(IlifeApi.JsonCallback { _, error -> done(error) }) }
            assertEquals("1,1", request.getRequestProperty("ApplicationType"))
            assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
            assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        }
        val mini = capture { done -> IlifeApi.masterWithToken("offline-mini", false) { _, error -> done(error) } }
        assertEquals("1,5", mini.getRequestProperty("ApplicationType"))
        assertEquals("2.0.83", mini.getRequestProperty("VersionCode"))
    }

    @Test fun callbackProgrammingErrorsEscapeWithoutASecondCallback() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val done = CountDownLatch(1)
        val marker = IllegalStateException("offline callback bug")
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> failure.set(error); done.countDown() }
        try {
            IlifeApi.walletOwnerWithToken("offline-app") { _, _ ->
                calls.incrementAndGet()
                throw marker
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertSame(marker, failure.get())
            assertEquals(1, calls.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun billsOnlyRequestTheExplicitPage() {
        val request = capture { done ->
            IlifeApi.billListWithToken("offline-app", 2) { _, error -> done(error) }
        }
        assertEquals("page=2&size=20", request.url.query)
        assertEquals("GET", request.requestMethod)
        assertTrue(requests.isEmpty())
    }

    @Test fun malformedResponsesAndNetworkFailuresAreStillReturnedAsErrors() {
        for (failure in listOf(false, true)) {
            response = { if (failure) throw java.io.IOException("offline network failure") else "not JSON" }
            val done = CountDownLatch(1)
            var result: JSONObject? = null
            var error: String? = null
            IlifeApi.walletOwnerWithToken("offline-app") { json, err -> result = json; error = err; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertNull(result)
            assertNotNull(error)
        }
    }

    @Test fun miniSmsKeepsItsExistingProtocol() {
        val request = capture { done ->
            IlifeApi.sendSms("13800000000", "graph", "captcha-key", false) { _, error -> done(error) }
        }
        assertEquals("1,5", request.getRequestProperty("ApplicationType"))
        assertEquals("2.0.83", request.getRequestProperty("VersionCode"))
        assertEquals("WaterWidget/${BuildConfig.VERSION_NAME} (Android)", request.getRequestProperty("User-Agent"))
        assertNull(request.url.query)
    }

    @Test fun appSmsLoginUsesItsVerifiedSignatureAndOnlySmsFields() {
        val request = capture { done ->
            IlifeApi.login("13800000000", "123456", true) { _, error -> done(error) }
        }
        assertEquals("POST", request.requestMethod)
        assertEquals("/api/v1/acc/login", request.url.path)
        assertEquals("sign=" + Signer.signAppLogin("13800000000", "123456", BuildConfig.APP_SIGN_SALT), request.url.query)
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertNull(request.getRequestProperty("Authorization"))
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals(2, body.length())
        assertEquals("123456", body.getString("authCode"))
        assertEquals("13800000000", body.getString("un"))
    }

    @Test fun miniLoginKeepsItsExistingFieldsWithoutAppSignature() {
        val request = capture { done ->
            IlifeApi.login("13800000000", "123456", false) { _, error -> done(error) }
        }
        assertEquals("1,5", request.getRequestProperty("ApplicationType"))
        assertEquals("2.0.83", request.getRequestProperty("VersionCode"))
        assertNull(request.url.query)
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals(4, body.length())
        assertEquals("", body.getString("openCode"))
        assertEquals(BuildConfig.API_CID, body.getString("cid"))
        assertEquals("123456", body.getString("authCode"))
        assertEquals("13800000000", body.getString("un"))
    }

    @Test fun appLoginCheckSignsEmptyContentAndDoesNotUseTheWallet() {
        val before = System.currentTimeMillis()
        val request = capture { done ->
            IlifeApi.accountStatWithToken("offline-app-token", "offline-user-id") { _, error -> done(error) }
        }
        val after = System.currentTimeMillis()
        assertEquals("GET", request.requestMethod)
        assertEquals("/api/v1/acc/stat", request.url.path)
        val signatures = listOf(before, after).map {
            "sign=" + Signer.signApp("", "offline-app-token", "offline-user-id", BuildConfig.APP_SIGN_SALT, it)
        }
        assertTrue(request.url.query in signatures)
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals("Android_ilife798_3.1.9", request.getRequestProperty("User-Agent"))
        assertEquals("offline-app-token", request.getRequestProperty("Authorization"))
        assertEquals(0, request.body.size())
        assertTrue(requests.isEmpty())
    }

    @Test fun appLoginCheckLooksUpMissingUidWithAppHeaders() {
        response = { url ->
            if (url.path.endsWith("/acc/view-info")) """{"code":0,"data":{"id":"offline-user-id"}}"""
            else """{"code":0}"""
        }
        val lookup = capture { done ->
            IlifeApi.accountStatWithToken("offline-app-token", "") { _, error -> done(error) }
        }
        assertEquals("/api/v1/acc/view-info", lookup.url.path)
        assertEquals("1,1", lookup.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", lookup.getRequestProperty("VersionCode"))
        assertEquals("offline-app-token", lookup.getRequestProperty("Authorization"))
        assertEquals("/api/v1/acc/stat", requests.remove().url.path)
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectedUidLookupDoesNotSubmitAnIncompleteAccountSignature() {
        response = { """{"code":-99}""" }
        var result: JSONObject? = null
        val lookup = capture { done ->
            IlifeApi.accountStatWithToken("offline-app-token", "") { json, error ->
                result = json
                done(error)
            }
        }
        assertEquals("/api/v1/acc/view-info", lookup.url.path)
        assertEquals(-99, result!!.getInt("code"))
        assertTrue(requests.isEmpty())
    }

    @Test fun appVideoUsesRewardedVideoType() {
        val request = capture { done ->
            IlifeApi.scoreSendWithToken("offline-app-token", "offline-user-id", "1705776998", 30, true) { _, error -> done(error) }
        }
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals("POST", request.requestMethod)
        assertEquals("/api/v1/acc/score/score-send", request.url.path)
        assertEquals("1,1", request.getRequestProperty("ApplicationType"))
        assertEquals("3.1.9", request.getRequestProperty("VersionCode"))
        assertEquals(4, body.length())
        assertEquals("1705776998", body.getString("adId"))
        assertEquals(101, body.getInt("type"))
        assertEquals(2, body.getInt("addScoreType"))
        assertEquals(30, body.getInt("addScore"))
    }

    @Test fun appInterstitialKeepsItsOwnType() {
        val request = capture { done ->
            IlifeApi.scoreSendWithToken("offline-app-token", "offline-user-id", "popsreen", 10, true) { _, error -> done(error) }
        }
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals(4, body.getInt("addScoreType"))
        assertEquals(10, body.getInt("addScore"))
    }

    @Test fun miniTaskDoesNotReceiveAppRewardFields() {
        val request = capture { done ->
            IlifeApi.scoreSendWithToken("offline-mini-token", "offline-user-id", "offline-mini-task", 20, false) { _, error -> done(error) }
        }
        val body = JSONObject(request.body.toString("UTF-8"))
        assertEquals("1,5", request.getRequestProperty("ApplicationType"))
        assertEquals("2.0.178", request.getRequestProperty("VersionCode"))
        assertEquals(2, body.length())
        assertEquals("offline-mini-task", body.getString("adId"))
        assertEquals(101, body.getInt("type"))
    }

    @Test fun taskLoadingUsesIndependentProgressInsteadOfEmbeddedZeroCounts() {
        val loaded = mutableListOf<JSONObject>()
        for (useApp in listOf(false, true)) {
            val missions = if (useApp) """[
                {"refId":"1705776998","name":"video","score":30,"limit":5},
                {"refId":"popsreen","name":"interstitial","score":10,"limit":5}]
            """ else """[
                {"refId":"1435961748560572416","name":"page","score":10,"limit":1},
                {"refId":"ad_tiny_2019061465519660_202402222200083035","name":"video","score":20,"limit":5},
                {"refId":"42_2026042824202135211","name":"guess","score":10,"limit":2}]
            """
            response = { url ->
                when {
                    url.path.endsWith("/mission-lst") -> """{"code":0,"data":{
                        "missions":$missions,"dailyRSP":{"adId":"DAILY_CHECK_IN","score":5},
                        "accScoreRsp":{"totalScore":100,"limits":[]}}}"""
                    url.path.endsWith("/acc-score") -> """{"code":0,"data":{
                        "totalScore":130,"daily":{"week":8},"limits":[
                        {"refId":"1705776998","limit":1},
                        {"refId":"popsreen","limit":3},
                        {"refId":"42_2026042824202135211","limit":1}]}}"""
                    else -> error("Unexpected task-loading request")
                }
            }
            var result: JSONObject? = null
            val first = capture { done ->
                IlifeApi.taskDataWithToken("offline-token", useApp) { json, error -> result = json; done(error) }
            }
            val second = requests.remove()
            assertTrue(first.url.path.endsWith("/mission-lst"))
            assertTrue(second.url.path.endsWith("/acc-score"))
            for (request in listOf(first, second)) {
                assertEquals("GET", request.requestMethod)
                assertEquals(if (useApp) "1,1" else "1,5", request.getRequestProperty("ApplicationType"))
                assertEquals(if (useApp) "3.1.9" else "2.0.178", request.getRequestProperty("VersionCode"))
                assertEquals("offline-token", request.getRequestProperty("Authorization"))
            }
            assertTrue(requests.isEmpty())
            val data = requireNotNull(result).getJSONObject("data")
            assertEquals(130, TaskMissionPlanner.progress(data).totalScore)
            assertEquals(8, data.getJSONObject("accScoreRsp").getJSONObject("daily").getInt("week"))
            assertEquals("DAILY_CHECK_IN", data.getJSONObject("dailyRSP").getString("adId"))
            loaded += data
        }
        val missions = TaskMissionPlanner.merge(loaded[0].getJSONArray("missions"), loaded[1].getJSONArray("missions"))
        val progress = TaskMissionPlanner.progress(loaded[0])
        val work = TaskMissionPlanner.buildWork(missions, progress.completed)
        assertEquals(13, work.size)
        assertEquals(4, work.count { it.refId == "1705776998" })
        assertEquals(2, work.count { it.refId == "popsreen" })
        assertEquals(1, work.count { it.refId == "42_2026042824202135211" })
    }

    @Test fun rejectedProgressDoesNotFallBackToEmbeddedCounts() {
        response = { url ->
            if (url.path.endsWith("/mission-lst")) """{"code":0,"data":{"missions":[],"accScoreRsp":{"totalScore":100,"limits":[]}}}"""
            else """{"code":-99,"msg":"needs verification"}"""
        }
        var result: JSONObject? = null
        capture { done ->
            IlifeApi.taskDataWithToken("offline-token", false) { json, error -> result = json; done(error) }
        }
        assertEquals(-99, requireNotNull(result).getInt("code"))
        assertTrue(requests.remove().url.path.endsWith("/acc-score"))
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectedMissionListDoesNotQueryProgress() {
        response = { """{"code":-98}""" }
        var result: JSONObject? = null
        val request = capture { done ->
            IlifeApi.taskDataWithToken("offline-token", false) { json, error -> result = json; done(error) }
        }
        assertTrue(request.url.path.endsWith("/mission-lst"))
        assertEquals(-98, requireNotNull(result).getInt("code"))
        assertTrue(requests.isEmpty())
    }

    @Test fun missingIndependentProgressIsAnErrorNotAZeroCount() {
        response = { url ->
            if (url.path.endsWith("/mission-lst")) """{"code":0,"data":{"missions":[],"accScoreRsp":{"totalScore":100,"limits":[]}}}"""
            else """{"code":0}"""
        }
        val done = CountDownLatch(1)
        var result: JSONObject? = null
        var error: String? = null
        IlifeApi.taskDataWithToken("offline-token", true) { json, err -> result = json; error = err; done.countDown() }
        assertTrue("API callback timed out", done.await(5, TimeUnit.SECONDS))
        assertNull(result)
        assertNotNull(error)
        assertEquals(2, requests.size)
    }

    private fun capture(start: ((String?) -> Unit) -> Unit): TestConnection {
        val done = CountDownLatch(1)
        var error: String? = null
        start { error = it; done.countDown() }
        assertTrue("API callback timed out", done.await(5, TimeUnit.SECONDS))
        assertNull(error)
        return requests.remove()
    }
}
