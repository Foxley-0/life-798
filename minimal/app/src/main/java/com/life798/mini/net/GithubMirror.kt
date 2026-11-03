package com.life798.mini.net

import com.life798.mini.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 给 GitHub Release 资产找一个真正能下载的地址。
 *
 * 2026-11-03 实测（v1.4.0 资产，Range 只取前 2KB）：
 *   github.com/…/releases/download/…  → 302 → objects.githubusercontent.com，206，1.24s  ✅
 *   ghfast.top/<原链>                  → 206，但 11.7s / 17.8s / 一次 20s 超时  ⚠️
 *   gh-proxy.com / ghproxy.net / 其余  → 000 或 403                        ❌
 *
 * 结论：**原链优先**。之前记的「github.com 直连不通」测的是主站网页，
 * release 下载走的是 objects.githubusercontent.com，那个域名是通的。
 *
 * 镜像只留 ghfast.top 一个 —— 它是唯一测出过 206 的，其余全是 000/403，
 * 留着只会让失败路径多等 2 个超时。
 *
 * 顺序试而不是并行：并行要处理竞态和取消传播，是这个功能里最容易出 bug 的一段，
 * 而原链成功的典型耗时只有 1 秒出头，顺序试的代价可以忽略。
 */
object GithubMirror {

    /**
     * 加速前缀，格式是「前缀 + 完整原始 URL」。
     * 以后有站挂了/复活了，只改这个数组，别的地方不用动。
     */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
    )

    /**
     * 超时给到 6 秒而不是 2.5 秒：
     * 手机在移动网络下握手的 RTT 比本机高得多，2.5 秒会把「能用但稍慢」的
     * 线路误判成不可用，进而让整个检查更新流程拿到 null。
     * 再长就会让「准备中…」卡太久，6 秒是两者的折中。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 原链 → 镜像1 → 镜像2，第一个能读到字节的就用。
     *
     * @return 可用的下载地址；全部失败返回 null（调用方退回 release 页面）。
     */
    suspend fun resolve(originalUrl: String): String? = withContext(Dispatchers.IO) {
        if (originalUrl.isBlank()) return@withContext null
        val candidates = listOf(originalUrl) + MIRRORS.map { it + originalUrl }
        candidates.firstOrNull { probe(it) }
    }

    /** 只读前 2KB —— 够判断「这个地址能不能拿到内容」，又不用真下完。 */
    private fun probe(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-2047")
            .header("User-Agent", "798-mini")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val ok = resp.isSuccessful
            if (ok) AppLog.i("Mirror", "可用线路: ${url.take(70)}…")
            ok
        }
    } catch (e: Exception) {
        false
    }
}
