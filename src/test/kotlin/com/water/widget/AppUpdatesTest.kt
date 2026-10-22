package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppUpdatesTest {
    private fun release(tag: String) = JSONObject().put("tag_name", tag).put("body", "修复问题")
        .put("assets", JSONArray().put(JSONObject().put("name", "WaterWidget-$tag-release.apk").put("size", 123)))

    @Test
    fun versionComparisonIsNumericAndNeverOffersDowngrade() {
        assertTrue(AppRelease.fromJson(release("v5.10.0")).isNewerThan("5.9.9"))
        assertFalse(AppRelease.fromJson(release("v5.4.3")).isNewerThan("5.4.3"))
        assertFalse(AppRelease.fromJson(release("v5.4.2")).isNewerThan("5.4.3"))
        assertEquals("$PROJECT_URL/releases/tag/v5.4.4", AppRelease.fromJson(release("v5.4.4")).url)
    }

    @Test
    fun onlyPublishedStableReleaseWithApkCanBeOffered() {
        assertThrows(IOException::class.java) { AppRelease.fromJson(release("v5.4.4").put("draft", true)) }
        assertThrows(IOException::class.java) { AppRelease.fromJson(release("v5.4.4").put("prerelease", true)) }
        assertThrows(IOException::class.java) { AppRelease.fromJson(release("v5.4.4").put("assets", JSONArray())) }
        assertThrows(IOException::class.java) { AppRelease.fromJson(release("https://other.example")) }
    }

    @Test
    fun automaticChecksRespectOffDailyWeeklyAndOnOpen() {
        val day = 86_400_000L
        assertFalse(AutoUpdateCheck.OFF.isDue(10 * day, 0))
        assertTrue(AutoUpdateCheck.DAILY.isDue(day, 0))
        assertFalse(AutoUpdateCheck.DAILY.isDue(2 * day - 1, day))
        assertTrue(AutoUpdateCheck.DAILY.isDue(2 * day, day))
        assertFalse(AutoUpdateCheck.WEEKLY.isDue(7 * day, day))
        assertTrue(AutoUpdateCheck.WEEKLY.isDue(8 * day, day))
        assertTrue(AutoUpdateCheck.ON_OPEN.isDue(day, day))
    }

    private class Response(url: String, private val status: Int, private val location: String? = null) : java.net.HttpURLConnection(java.net.URL(url)) {
        var closed = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun limitedApiUsesOfficialWebsiteWithoutDownloadingApk() {
        for (status in listOf(403, 429)) {
            val responses = mutableListOf<Response>()
            val result = AppUpdateClient.latest { url ->
                val response = when (url) {
                    LATEST_RELEASE_API -> Response(url, status)
                    "$PROJECT_URL/releases/latest" -> Response(url, 302, "$PROJECT_URL/releases/tag/v5.4.4")
                    "$PROJECT_URL/releases/download/v5.4.4/WaterWidget-v5.4.4-release.apk" -> Response(url, 302)
                    else -> error("Unexpected URL: $url")
                }
                responses.add(response)
                response
            }
            assertEquals("5.4.4", result.version)
            assertEquals("", result.notes)
            assertEquals(3, responses.size)
            assertTrue(responses.all { it.closed })
            assertTrue(responses.drop(1).all { it.requestMethod == "HEAD" && !it.instanceFollowRedirects })
        }
    }

    @Test fun websiteRejectsForeignRedirectAndMissingApk() {
        for (foreign in listOf(true, false)) {
            assertThrows(IOException::class.java) {
                AppUpdateClient.latest { url ->
                    when (url) {
                        LATEST_RELEASE_API -> Response(url, 403)
                        "$PROJECT_URL/releases/latest" -> Response(url, 302,
                            if (foreign) "https://other.example/releases/tag/v5.4.4" else "$PROJECT_URL/releases/tag/v5.4.4")
                        else -> { assertFalse(foreign); Response(url, 404) }
                    }
                }
            }
        }
    }
}
