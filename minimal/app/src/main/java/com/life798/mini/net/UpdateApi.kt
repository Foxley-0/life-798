package com.life798.mini.net

import com.life798.mini.BuildConfig
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 查 GitHub Releases 有没有新版本。
 *
 * 独立于 [WaterApi]：那边每个请求都要签名、带设备控制端的固定头，
 * 打 GitHub 不能带那些东西。
 *
 * 用 `/releases/latest` 而不是 `/releases`：前者由 GitHub 自己跳过
 * draft 和 prerelease，不用我们筛。
 */
class UpdateApi(private val repo: String = DEFAULT_REPO) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun latest(): ApiResult<UpdateInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // GitHub API 对没有 User-Agent 的请求直接 403
            .header("User-Agent", "798-mini/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext ApiResult.Err("仓库还没有发布过 release")
                }
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Err("GitHub 返回 HTTP ${resp.code}")
                }
                val text = resp.body?.string()
                if (text.isNullOrBlank()) {
                    return@withContext ApiResult.Err("响应为空")
                }

                val json = JSONObject(text)

                // /latest 理论上已经过滤掉了，这里再兜一次
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                    return@withContext ApiResult.Err("最新 release 是预发布版本")
                }

                val tag = json.optString("tag_name")
                if (tag.isBlank()) {
                    return@withContext ApiResult.Err("响应缺少 tag_name")
                }

                // 优先拿 release 包的直链（CI 同时传了 debug 包，要认名字）
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk") && name.contains("release")) {
                        apkUrl = a.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }

                ApiResult.Ok(
                    UpdateInfo(
                        version = tag.removePrefix("v"),
                        title = json.optString("name").ifBlank { tag },
                        pageUrl = json.optString("html_url"),
                        apkUrl = apkUrl,
                        publishedAt = json.optString("published_at").take(10),
                    )
                )
            }
        } catch (e: Exception) {
            AppLog.w("Update", "检查更新失败: ${e.javaClass.simpleName} ${e.message}")
            ApiResult.Err("无法连接 GitHub，请检查网络")
        }
    }

    private companion object {
        const val DEFAULT_REPO = "Foxley-0/life-798"
    }
}
