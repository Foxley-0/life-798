package com.life798.mini.core

import com.life798.mini.net.WaterApi

/**
 * 798 账单同步流水线：**列表一次拉全 → 首屏落地 → 详情并发补齐**。
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
 * 才是旧代码漏掉 3.25 元（实际 5.11 元，漏 64%）的直接原因。也正因为一次就能
 * 拉全，「增量早停」在这里没有可省的请求 —— 与胖乖 [PgBillSync] 按页早停不同，
 * 这里省下的是**详情**：只有新增账单必补，历史缺口每轮限量。
 *
 * 和胖乖对齐的两处改动（2026-11-26）：
 * - 列表落库后立刻 `onListed()`，调用方马上放掉转圈、刷一次 revision。
 *   金额和时间本来就来自列表，界面不用等详情；详情后台静默补完再刷一次。
 * - 详情走 [DetailFiller]（4 路并发 + 攒 20 条落盘 + 无明细标 `dead`），
 *   与胖乖完全同一份实现，只有「怎么取」不同。
 *
 * 与 UI 的契约（改这里之前先看 [com.life798.mini.ui.UsageScreen]）：
 * - 只在 `onListed` 与整轮结束各刷一次 revision，中途不回写 UI 状态，页面不会闪
 * - 升数缺失的记录原样留着 `liters = null`，界面显示「—」，不会被填成 0
 * - 任何一步失败都**不清本地账本**，返回 [Outcome] 的 error 由调用方决定提示什么
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
        /** 服务端查不到用量、本轮被标记退出的条数。 */
        val dead: Int = 0,
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
     * @param onListed 列表落库后立刻回调（早于任何详情请求）。
     * @param onProgress 详情每批落盘后回调 `(已补, 剩余)`。只用于日志，
     *   不参与 UI 状态 —— 中途改 revision 会让汇总卡的升数一路跳变。
     */
    suspend fun run(
        token: String,
        store: BillStore,
        onListed: () -> Unit = {},
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

        // 新增的是哪几笔，用来区分「必补」和「历史缺口」
        val freshIds = store.mergeReport(listed)
        AppLog.i("Bill", "列表 ${listed.size} 条（有费用），新增 ${freshIds.size} 条")
        // 首屏到此为止：金额、时间、设备名都已落库，界面不需要等详情
        onListed()

        // ---------- 第二步：详情 ----------
        val freshSet = freshIds.toSet()
        val backlog = store.missingDetail()
            .filterNot { it in freshSet }
            .take(HISTORY_BUDGET)
        val ids = freshIds + backlog
        if (ids.isEmpty()) {
            return Outcome(listed = listed.size, added = freshIds.size)
        }

        val stats = DetailFiller(tag = "Bill") { id -> api.billDetail(token, id) }
            .run(ids, store)

        onProgress(stats.filled, stats.remaining)
        AppLog.i(
            "Bill",
            "补详情 ${stats.filled} 条（无明细 ${stats.dead} 条），未补 ${stats.remaining} 条"
        )
        return Outcome(
            listed = listed.size,
            added = freshIds.size,
            filled = stats.filled,
            dead = stats.dead,
            pending = stats.remaining,
            error = stats.error,
            errorCode = stats.errorCode,
        )
    }

    private companion object {
        /**
         * 历史缺口每轮最多补这么多条。
         *
         * 新增账单不设上限（日常 0~2 条，本来就该当场补上）；限的是「老账缺升数」
         * 那部分 —— 换机或首次登录时可能一次进来上百条，全补完要十几秒，
         * 不该让首屏陪着等。剩下的下次进页面接着补，金额始终是对的。
         */
        const val HISTORY_BUDGET = 20
    }
}
