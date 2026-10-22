package com.water.widget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal const val PROJECT_URL = "https://github.com/nocookies111/life-798"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/nocookies111/life-798/releases/latest"

enum class AutoUpdateCheck(val label: String, val intervalMillis: Long?) {
    OFF("关闭", null),
    ON_OPEN("每次打开应用", 0L),
    DAILY("每天一次", 24 * 60 * 60 * 1000L),
    WEEKLY("每周一次", 7 * 24 * 60 * 60 * 1000L);

    fun isDue(now: Long, lastAttempt: Long): Boolean =
        intervalMillis != null && (lastAttempt == 0L || now - lastAttempt >= intervalMillis)
}

data class AppRelease(val version: String, val notes: String, val url: String) {
    fun isNewerThan(current: String): Boolean {
        val remoteParts = versionParts(version)
        val currentParts = versionParts(current)
        for (index in remoteParts.indices) {
            if (remoteParts[index] != currentParts[index]) return remoteParts[index] > currentParts[index]
        }
        return false
    }

    companion object {
        private fun versionParts(value: String): List<Int> {
            val match = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(value)
                ?: throw IOException("更新版本信息无法识别")
            return match.groupValues.drop(1).map { it.toIntOrNull() ?: throw IOException("更新版本信息无法识别") }
        }

        fun fromJson(json: JSONObject): AppRelease {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw IOException("暂无可用的正式更新")
            val tag = json.getString("tag_name")
            versionParts(tag)
            val assets = json.getJSONArray("assets")
            val apkName = "WaterWidget-$tag-release.apk"
            val hasApk = (0 until assets.length()).any { index ->
                assets.getJSONObject(index).optString("name") == apkName && assets.getJSONObject(index).optLong("size") > 0
            }
            if (!hasApk) throw IOException("新版本安装包尚未就绪，请稍后重试")
            return AppRelease(tag.removePrefix("v"), json.optString("body"), "$PROJECT_URL/releases/tag/$tag")
        }
    }
}

internal object AppUpdateClient {
    fun latest(open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }): AppRelease {
        val connection = open(LATEST_RELEASE_API)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WaterWidget/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status == 403 || status == 429) return latestFromWebsite(open)
            if (status != 200) throw IOException("无法获取更新（HTTP $status），请稍后重试")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return AppRelease.fromJson(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun latestFromWebsite(open: (String) -> HttpURLConnection): AppRelease {
        val latest = open("$PROJECT_URL/releases/latest")
        val tag = try {
            latest.connectTimeout = 15_000
            latest.readTimeout = 15_000
            latest.instanceFollowRedirects = false
            latest.requestMethod = "HEAD"
            if (latest.responseCode != 302) throw IOException("当前网络无法检查更新，请切换网络或访问项目地址")
            val location = latest.getHeaderField("Location").orEmpty()
            val match = Regex(Regex.escape("$PROJECT_URL/releases/tag/") + "(v[0-9]+\\.[0-9]+\\.[0-9]+)").matchEntire(location)
                ?: throw IOException("更新版本信息无法识别，请访问项目地址")
            match.groupValues[1]
        } finally {
            latest.disconnect()
        }
        val asset = open("$PROJECT_URL/releases/download/$tag/WaterWidget-$tag-release.apk")
        try {
            asset.connectTimeout = 15_000
            asset.readTimeout = 15_000
            asset.instanceFollowRedirects = false
            asset.requestMethod = "HEAD"
            if (asset.responseCode != 200 && asset.responseCode != 302) {
                throw IOException("无法确认新版本安装包，请稍后重试或访问项目地址")
            }
        } finally {
            asset.disconnect()
        }
        return AppRelease(tag.removePrefix("v"), "", "$PROJECT_URL/releases/tag/$tag")
    }
}
