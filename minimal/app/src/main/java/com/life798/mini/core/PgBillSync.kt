package com.life798.mini.core

import com.life798.mini.net.PangguaiOrderApi
import com.life798.mini.net.PgOrder

/**
 * 胖乖账本同步流水线：**增量翻页 → 首屏落地 → 详情并发补齐**。
 *
 * 改之前的行为（2026-11-26 真机 logcat 实测）：每次刷新都把 6 页列表（170 条，
 * 一条没新增）和 60 条串行详情整套重跑，一轮 19.4 秒 —— 其中列表 3.3 秒、
 * 详情 16.1 秒。**慢的不是网络，是无条件全量重跑**。
 *
 * 所以这里的三处改动都对着日志里的耗时：
 *
 * 1. **增量早停**：订单按时间倒序返回，某一页 40 条**一条都没新增**时，更老的页
 *    不可能再有新单，直接收手。日常 003 从 5 次请求降到 1 次。
 *    只有首次同步（本地账本为空）才老老实实翻到底。
 * 2. **首屏不等详情**：列表落库后立刻 `onListed()`，调用方马上放掉转圈、刷一次
 *    revision —— 金额和时间本来就来自列表，界面不需要等详情。详情在后台静默补。
 * 3. **详情只补新增 + 历史缺口限量**：新增账单必补（日常 0~2 条），历史缺口每轮
 *    最多 [HISTORY_BUDGET] 条，剩下的下次接着补，不占首屏。
 *
 * 详情接口与 798 一样一笔一个请求，并发与攒批落盘交给 [DetailFiller]，
 * 这里只负责「怎么取」。`pageSize` 服务端上限 40（实测），没法像 798 那样
 * 一次 `size=1000` 拉全，这是两个平台唯一的结构差异。
 *
 * 与 UI 的契约同 [BillSync]：中途改 revision 会让汇总卡的升数一路跳变，
 * 所以只在 `onListed` 与整轮结束时各刷一次。
 */
class PgBillSync(private val api: PangguaiOrderApi) {

    data class Outcome(
        val listed: Int = 0,
        val added: Int = 0,
        val filled: Int = 0,
        /** 服务端查不到用量、本轮被标记退出的条数。 */
        val dead: Int = 0,
        /** 这轮结束后本地仍缺升数的条数。 */
        val pending: Int = 0,
        /** 实际发起的列表请求数，用于核对早停是否生效。 */
        val pages: Int = 0,
        /** 鉴权失效等服务端明确拒绝的原因；null 表示这一轮没出错。 */
        val error: String? = null,
        val errorCode: Int = 0,
    ) {
        val failed: Boolean get() = error != null
    }

    /**
     * 跑一轮完整同步。
     *
     * @param onListed 列表落库后立刻回调（早于任何详情请求）。调用方在这里
     *   放掉 loading 并 `revision + 1`，首屏就只等一次列表。
     * @param onProgress 详情每批落盘后回调 `(已补, 剩余)`。只用于日志。
     */
    suspend fun run(
        token: String,
        store: BillStore,
        onListed: () -> Unit = {},
        onProgress: (filled: Int, remaining: Int) -> Unit = { _, _ -> },
    ): Outcome {
        // 本地已有的 id 快照。翻页过程中不断把新 id 加进来，同一单不会被算两次新增。
        val known = store.existingIds().toMutableSet()
        // 账本为空 = 首次同步，必须翻到底；有账本才允许「本页无新增」早停
        val firstRun = known.isEmpty()

        var listed = 0
        var added = 0
        var pages = 0
        val freshIds = mutableListOf<String>()

        for (tag in TAGS) {
            var page = 1
            while (page <= MAX_PAGES) {
                val orders = when (val r = api.page(token, page, tag)) {
                    is ApiResult.Err -> {
                        AppLog.w("PgBill", "订单列表失败 tag=$tag 第 $page 页：${r.message} code=${r.code}")
                        return Outcome(
                            listed = listed,
                            added = added,
                            pending = store.missingDetail().size,
                            pages = pages,
                            error = r.message,
                            errorCode = r.code,
                        )
                    }

                    is ApiResult.Ok -> r.data
                }
                pages++

                val records = orders.map { it.toBillRecord() }
                // 0 元单（启动了没出水）不进账本，也不值得为它补详情
                val newIds = records
                    .filter { it.yuan > 0.0 && it.id !in known }
                    .map { it.id }

                added += store.merge(records)
                listed += records.size
                known += records.map { it.id }
                freshIds += newIds

                AppLog.i(
                    "PgBill",
                    "tag=$tag 第 $page 页 ${records.size} 条，新增 ${newIds.size}"
                )

                // 不满一页就是最后一页
                if (records.size < PangguaiOrderApi.PAGE_SIZE) break
                // 满页但一条新的都没有：后面的页只会更老，肯定也全是已存在的
                if (newIds.isEmpty() && !firstRun) {
                    AppLog.i("PgBill", "tag=$tag 第 $page 页无新增，停止翻页")
                    break
                }
                page++
            }
        }

        AppLog.i("PgBill", "列表 ${listed} 条，$pages 次请求，新增 $added 条")
        // 首屏到此为止：金额、时间、设备名都已落库，界面不需要等详情
        onListed()

        val freshSet = freshIds.toSet()
        val backlog = store.missingDetail()
            .filterNot { it in freshSet }
            .take(HISTORY_BUDGET)
        val ids = freshIds + backlog
        if (ids.isEmpty()) {
            return Outcome(listed = listed, added = added, pages = pages)
        }

        val stats = DetailFiller(tag = "PgBill") { id ->
            when (val d = api.detail(token, id)) {
                is ApiResult.Ok -> ApiResult.Ok(d.data.toBillRecord())
                is ApiResult.Err -> d
            }
        }.run(ids, store)

        onProgress(stats.filled, stats.remaining)
        AppLog.i(
            "PgBill",
            "补水量 ${stats.filled} 条（无明细 ${stats.dead} 条），仍缺 ${stats.remaining} 条"
        )
        return Outcome(
            listed = listed,
            added = added,
            filled = stats.filled,
            dead = stats.dead,
            pending = stats.remaining,
            pages = pages,
            error = stats.error,
            errorCode = stats.errorCode,
        )
    }

    private companion object {
        /**
         * 账本只要饮水和淋浴两类，洗衣不记账（[PangguaiOrderApi.TAG_DRINK] 等
         * 是服务端 `displayTag`：`003` 饮水、`004` 淋浴）。
         *
         * 实测该账号 004 恒为 0 条，但仍保留一次请求 —— 一旦开始有淋浴单，
         * 靠猜是发现不了的，一次请求换一个确定性。
         */
        val TAGS = listOf(PangguaiOrderApi.TAG_DRINK, PangguaiOrderApi.TAG_SHOWER)

        /** 翻页上限，防止服务端异常时死循环（每页 40，10 页 = 400 条）。 */
        const val MAX_PAGES = 10

        /**
         * 历史缺口每轮最多补这么多条。
         *
         * 只限「老账缺升数」这部分 —— 新增账单不设上限，当场补完。
         * 换机或首次登录时 170 笔会一次性作为新增进来（并发补完），
         * 能进到这里的只有「上一轮被打断」的零头，所以 20 条足够几天内清干净，
         * 又不会让后台静默跑太久。
         */
        const val HISTORY_BUDGET = 20
    }
}

/**
 * 胖乖订单 → 账本条目。
 *
 * 只是换了个数据源，账本形状不变：`waterUseValue`（毫升）在 API 层已经除过 1000，
 * 所以这里 [PgOrder.liters] 直接就是升，和 798 的 `dev.subs[0].out` 同量纲。
 */
private fun PgOrder.toBillRecord(): BillRecord = BillRecord(
    id = id,
    at = at,
    yuan = yuan,
    deviceName = deviceName,
    liters = liters,
    secs = secs,
    category = category
)
