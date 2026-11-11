package com.life798.mini.net

import android.content.Context
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一笔胖乖历史订单，已经换算成账本能直接用的形状。
 *
 * [liters] 可为空：列表接口里 `tradeOrderItem[].skuInfo` 是 null，**没有水量**，
 * 只有详情接口 `/order/detail` 才返回。所以和 798 一样分两步 ——
 * 列表先把时间/金额/设备名落库，详情再逐条补升数与时长。
 */
data class PgOrder(
    /** 订单号，去重主键。 */
    val id: String,
    /** 下单时刻，毫秒。服务端给的是北京时间字符串。 */
    val at: Long,
    /** 实付金额（元）。 */
    val yuan: Double,
    /** 设备名，如「静4-4热」。 */
    val deviceName: String?,
    /** 本次用水量（升）。详情未补到之前是 null。 */
    val liters: Double?,
    /** 本次用水时长（秒），由开始/结束时间算出来。 */
    val secs: Double?,
)

/**
 * 胖乖历史订单接口。
 *
 * 走 [PangguaiApi.callBusiness] —— SHA-256 sign 与 `Authorization` 都在那里，
 * 这里不重复实现签名。字段名保持服务端原样（orderNo / waterUseValue ...），
 * `pangguai-` 前缀只用于本地存储。
 *
 * 水量单位是**毫升**（抓包实证：330ml/0.09 元、1518ml/0.38 元），
 * 换算成升除以 1000，与 798 账本统一。
 */
class PangguaiOrderApi(context: Context) {

    private val api = PangguaiApi(context)

    private fun form(vararg pairs: Pair<String, String>) = api.form(*pairs)

    /**
     * 拉一页订单（[size] 服务端上限 40）。
     *
     * `orderStatus` 传空串表示不限，只保留已完成的订单 —— 退款/进行中的单
     * 水量和金额都不该记进账本。
     */
    suspend fun page(token: String, page: Int, size: Int = PAGE_SIZE): ApiResult<List<PgOrder>> =
        when (
            val r = api.callBusiness(
                PATH_LIST,
                form(
                    "page" to page.toString(),
                    "pageSize" to size.toString(),
                    "orderStatus" to "",
                    "token" to token
                ),
                token
            )
        ) {
            is ApiResult.Ok -> {
                val items = r.data.optJSONObject("data")?.optJSONArray("items")
                val orders = buildList {
                    for (i in 0 until (items?.length() ?: 0)) {
                        val item = items?.optJSONObject(i) ?: continue
                        toOrder(item)?.let { add(it) }
                    }
                }
                AppLog.i("PgOrder", "第 $page 页 ${orders.size} 条")
                ApiResult.Ok(orders)
            }

            is ApiResult.Err -> r
        }

    /**
     * 订单详情：补水量与时长。
     *
     * 水量在 `tradeOrderItem[0].skuInfo.waterUseValue`（毫升），
     * 不是顶层字段，也不是 `lineItems` 里 —— 那两个位置的用量是空。
     */
    suspend fun detail(token: String, orderNo: String): ApiResult<PgOrder> =
        when (
            val r = api.callBusiness(
                PATH_DETAIL,
                form("orderId" to orderNo, "token" to token),
                token
            )
        ) {
            is ApiResult.Ok -> {
                val data = r.data.optJSONObject("data")
                if (data == null) {
                    ApiResult.Err("订单详情为空")
                } else {
                    val items = data.optJSONArray("tradeOrderItem")
                    val sku = (0 until (items?.length() ?: 0))
                        .mapNotNull { items.optJSONObject(it)?.optJSONObject("skuInfo") }
                        .firstOrNull { it.has("waterUseValue") }
                    val ml = sku?.optDouble("waterUseValue", Double.NaN)
                    ApiResult.Ok(
                        PgOrder(
                            id = data.optString("orderNo").ifBlank { orderNo },
                            at = parseTime(data.optString("createTime")) ?: 0L,
                            yuan = data.optString("payPrice").toDoubleOrNull() ?: 0.0,
                            deviceName = data.optString("machineName").takeIf { it.isNotBlank() },
                            liters = ml?.takeIf { !it.isNaN() && it > 0.0 }?.div(1000.0),
                            secs = secondsBetween(
                                data.optString("beginTime"),
                                data.optString("finishTime")
                            )
                        )
                    )
                }
            }

            is ApiResult.Err -> r
        }

    private fun toOrder(item: JSONObject): PgOrder? {
        val id = item.optString("orderNo").ifBlank { item.optString("id") }
        if (id.isBlank()) return null
        // 只记已完成的单：2 是「已完成」，其余（进行中 / 已退款）不记账本
        if (item.has("orderStatus") && item.optInt("orderStatus") != STATUS_DONE) return null
        val at = parseTime(item.optString("createTime")) ?: return null
        val yuan = item.optString("payPrice").toDoubleOrNull() ?: 0.0
        if (yuan <= 0.0) return null
        return PgOrder(
            id = id,
            at = at,
            yuan = yuan,
            deviceName = item.optString("machineName").takeIf { it.isNotBlank() },
            liters = null,
            secs = null
        )
    }

    /** `2026-11-11 10:47:39` 是北京时间，按东八区换算，不用设备时区。 */
    private fun parseTime(text: String): Long? {
        if (text.isBlank()) return null
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return runCatching {
            LocalDateTime.parse(text, fmt).atZone(ZONE).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun secondsBetween(begin: String, finish: String): Double? {
        val a = parseTime(begin) ?: return null
        val b = parseTime(finish) ?: return null
        val secs = (b - a) / 1000.0
        return if (secs > 0) secs else null
    }

    companion object {
        const val PAGE_SIZE = 40
        private const val PATH_LIST = "/order/list"
        private const val PATH_DETAIL = "/order/detail"
        private const val STATUS_DONE = 2
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
