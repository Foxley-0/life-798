package com.life798.mini.core

import com.life798.mini.net.WaterApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 798 账单同步流水线：**列表一次拉全 → 详情并发补齐 → 批量落盘**。
 *
 * 抓包实证（2026-11-11，官方 App 3.1.10 token 直连 i.ilife798.com）决定了这里每一步：
 *
 * ```
 * page=0 & size=20        → 20 条，响应 size 字段却是 28
 * page=1 & size=20        → 8 条
 * page=2 & size=20        → 0 条
 * size=100/200/500/1000   → 全部 28 条原样返回，不截断
 * size=0 / size=abc       → code=-100
 * ```
 *
 * 所以**不存在「翻页」这回事**：一页开到 1000 条就把历史拿全了。换成 `size=20`
 * 才是旧代码漏掉 3.25 元（实际 5.11 元，漏 64%）的直接原因。
 *
 * 真正的瓶颈在第二步。列表接口不带用量，只有 `/bill/view-full` 有，
 * 一笔一个请求；186 笔串行跑要几十秒，而且每补一条都全量写一次盘。
 * 这里改成「4 路并发 + 攒 20 条写一次」，请求数不变，写盘次数从 186 降到 10 上下。
 *
 * 与 UI 的契约（改这里之前先看 [com.life798.mini.ui.UsageScreen]）：
 * - 只在整轮结束时由调用方 `billsRevision + 1`，中途不回写 UI 状态，页面不会闪
 * - 升数缺失的记录原样留着 `liters = null`，界面显示「—」，不会被填成 0
 * - 任何一步失败都**不清本地账本**，返回 [Outcome.Failed] 由调用方决定提示什么
 */
class BillSync(private val api: WaterApi) {

    /**
     * 一轮同步的结果。
     *
     * [pending] 是这轮结束后本地仍缺升数的条数 —— 不为 0 说明历史还没补完，
     * 下次下拉会接着补，不影响金额（金额从列表就有）。
     */
    data class Outcome(
        val listed: Int = 0,
        val added: Int = 0,
        val filled: Int = 0,
        val pending: Int = 0,
        /** 鉴权失效等服务端明确拒绝的原因；null 表示这一轮没出错。 */
        val error: String? = null,
        val errorCode: Int = 0,
    ) {
        val failed: Boolean get() = error != null
    }

    /**
     * 跑一轮完整同步。
     *
     * @param onProgress 列表落库后、每批详情落盘后回调 `(已补, 剩余)`。只用于日志，
     *   不参与 UI 状态 —— 中途改 revision 会让汇总卡的升数一路跳变。
     */
    suspend fun run(
        token: String,
        store: BillStore,
        onProgress: (filled: Int, remaining: Int) -> Unit = { _, _ -> },
    ): Outcome {
        // ---------- 第一步：列表 ----------
        val first = when (val r = api.billList(token, page = 0)) {
            is ApiResult.Err -> {
                AppLog.w("Bill", "账单列表失败：${r.message} code=${r.code}")
                return Outcome(error = r.message, errorCode = r.code)
            }
            is ApiResult.Ok -> r.data
        }

        var listed = first
        // 满 1000 条才有可能真的还有下一页。实测正常账号 28 条，这里不会触发；
        // 只是防止「历史特别长」的账号被静默截断。补一发就停，不做循环。
        if (first.size >= WaterApi.BILL_PAGE_SIZE) {
            when (val r = api.billList(token, page = 1)) {
                is ApiResult.Ok -> {
                    listed = first + r.data
                    AppLog.i("Bill", "列表满页，补拉第 2 页 ${r.data.size} 条")
                }
                is ApiResult.Err -> AppLog.w("Bill", "第 2 页失败，按已拿到的继续：${r.message}")
            }
        }

        val added = store.merge(listed)
        AppLog.i("Bill", "列表 ${listed.size} 条（有费用），新增入库 $added 条")

        // ---------- 第二步：详情 ----------
        val pending = store.missingDetail()
        if (pending.isEmpty()) {
            return Outcome(listed = listed.size, added = added)
        }

        var filled = 0
        var consecutiveFail = 0
        var lastError: String? = null
        var lastCode = 0
        val buffer = ArrayList<BillRecord>(FLUSH_SIZE)

        // 分批并发。每批 4 条同时在飞，比串行快 3~4 倍且不会把服务端打出限流。
        for (group in pending.chunked(CONCURRENCY)) {
            val results = coroutineScope {
                group.map { id -> async { id to api.billDetail(token, id) } }.awaitAll()
            }

            for ((id, result) in results) {
                when (result) {
                    is ApiResult.Ok -> {
                        consecutiveFail = 0
                        val bill = result.data
                        if (bill.liters != null) {
                            buffer += bill
                            filled++
                        } else {
                            // 服务端已经查不到这笔的用量（历史被清）。打上标记退出队列，
                            // 否则它会永远排在队头，每轮同步都白跑一次。
                            buffer += bill.copy(dead = true)
                        }
                    }

                    is ApiResult.Err -> {
                        consecutiveFail++
                        lastError = result.message
                        lastCode = result.code
                        AppLog.w("Bill", "详情失败 id=$id：${result.message} code=${result.code}")
                        // 连续几条都挂就是网络断了或 token 失效，收手把已拿到的存下。
                        // 是不是鉴权问题不归这里判断，code 原样带回给调用方。
                        if (consecutiveFail >= MAX_CONSECUTIVE_FAIL) {
                            AppLog.w("Bill", "连续失败 $consecutiveFail 条，本轮提前收手")
                            flush(store, buffer)
                            val remaining = store.missingDetail().size
                            onProgress(filled, remaining)
                            return Outcome(
                                listed = listed.size,
                                added = added,
                                filled = filled,
                                pending = remaining,
                                error = lastError,
                                errorCode = lastCode,
                            )
                        }
                    }
                }
            }

            if (buffer.size >= FLUSH_SIZE) {
                flush(store, buffer)
                onProgress(filled, (pending.size - filled).coerceAtLeast(0))
            }
        }

        flush(store, buffer)
        val remaining = store.missingDetail().size
        onProgress(filled, remaining)
        AppLog.i("Bill", "补详情 $filled 条，未补 $remaining 条")
        return Outcome(listed = listed.size, added = added, filled = filled, pending = remaining)
    }

    /** 攒够一批就落一次盘，然后清空缓冲区。 */
    private fun flush(store: BillStore, buffer: MutableList<BillRecord>) {
        if (buffer.isEmpty()) return
        store.fillDetails(buffer)
        buffer.clear()
    }

    private companion object {
        /** 同时在飞的详情请求数。 */
        const val CONCURRENCY = 4

        /** 攒够这么多条才写一次盘。 */
        const val FLUSH_SIZE = 20

        /** 连续这么多条详情失败就认为网络断了，本轮收手。 */
        const val MAX_CONSECUTIVE_FAIL = 3
    }
}
