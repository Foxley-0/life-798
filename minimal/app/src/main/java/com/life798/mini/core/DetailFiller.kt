package com.life798.mini.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 详情补齐流水线：**并发拉取 → 攒批落盘**。798 与胖乖共用同一份实现。
 *
 * 两个平台的详情接口都是一笔一个请求，且列表接口都不带用量，所以这段逻辑
 * 本来要在 [BillSync] 和 [PgBillSync] 里各写一遍。抽出来的只有「怎么跑」，
 * 「怎么取」由调用方用 [fetch] 传进来。
 *
 * 三条硬约束（改之前先想清楚）：
 * - **同时只有 [concurrency] 条在飞**：串行 267ms/条太慢，全量并发会被服务端限流
 * - **攒够 [flushSize] 条才落一次盘**：`BillStore.fillDetails` 是一次全表
 *   反序列化 + 全量 `commit()`，一条一写比网络本身还慢
 * - **`liters = null` 视为服务端已无明细**，打 `dead = true` 退出队列。
 *   不打标的账单会永远排在 `missingDetail()` 队头，每轮同步都白跑一次
 *
 * 连续 [MAX_CONSECUTIVE_FAIL] 条失败就认为网络断了，收手返回 —— 已拿到的照常落盘。
 */
class DetailFiller(
    /** 日志 tag，`798/Bill` 或 `798/PgBill`，方便 `adb logcat -s` 分开看。 */
    private val tag: String,
    private val concurrency: Int = CONCURRENCY,
    private val flushSize: Int = FLUSH_SIZE,
    /** 取一笔详情。返回的 `liters` 为 null 表示服务端没有这笔的用量。 */
    private val fetch: suspend (id: String) -> ApiResult<BillRecord>,
) {

    data class Stats(
        /** 真正补到升数的条数。 */
        val filled: Int = 0,
        /** 服务端查不到用量、被标记退出的条数。 */
        val dead: Int = 0,
        /** 这轮结束后本地仍缺升数的条数。 */
        val remaining: Int = 0,
        /** 中途连续失败的原因；null 表示这轮详情阶段没出错。 */
        val error: String? = null,
        val errorCode: Int = 0,
    )

    suspend fun run(ids: List<String>, store: BillStore): Stats {
        if (ids.isEmpty()) return Stats(remaining = store.missingDetail().size)

        var filled = 0
        var dead = 0
        var consecutiveFail = 0
        var lastError: String? = null
        var lastCode = 0
        val buffer = ArrayList<BillRecord>(flushSize)

        for (group in ids.chunked(concurrency)) {
            val results = coroutineScope {
                group.map { id -> async { id to fetch(id) } }.awaitAll()
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
                            buffer += bill.copy(dead = true)
                            dead++
                        }
                    }

                    is ApiResult.Err -> {
                        consecutiveFail++
                        lastError = result.message
                        lastCode = result.code
                        AppLog.w(tag, "详情失败 id=$id：${result.message} code=${result.code}")
                        if (consecutiveFail >= MAX_CONSECUTIVE_FAIL) {
                            AppLog.w(tag, "连续失败 $consecutiveFail 条，本轮提前收手")
                            flush(store, buffer)
                            return Stats(
                                filled = filled,
                                dead = dead,
                                remaining = store.missingDetail().size,
                                error = lastError,
                                errorCode = lastCode,
                            )
                        }
                    }
                }
            }

            if (buffer.size >= flushSize) flush(store, buffer)
        }

        flush(store, buffer)
        return Stats(filled = filled, dead = dead, remaining = store.missingDetail().size)
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
