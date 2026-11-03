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
 * 为什么不写死一个加速站：这类公益镜像没有「长期稳定」这回事。
 * 2026-11-03 实测 10 个候选只有 ghfast.top 能通，而且 3 次探测里
 * 1 次 15 秒超时、整包 12MB 下载 180 秒没下完。
 *
 * 顺序试而不是并行：并行要处理 CompletableDeferred 竞态和取消传播，
 * 是这个功能里最容易出 bug 的一段，收益却不大 —— 实测直连失败只要
 * 0.01 秒（连接立刻被拒），顺序试的典型耗时就是 1~2 秒。
 */
object GithubMirror {

    /**
     * 加速前缀，格式是「前缀 + 完整原始 URL」。
     * 以后有站挂了/复活了，只改这个数组，别的地方不用动。
     */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * 直连 → 镜像1 → 镜像2，第一个能读到字节的就用。
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
