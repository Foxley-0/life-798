package com.life798.mini.net

import android.content.Context
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.water.PgWaterReading
import okhttp3.FormBody
import org.json.JSONObject

/**
 * 胖乖用水设备（饮水机 / 淋浴）接口。
 *
 * 全部走 `PangguaiApi.callBusiness()` —— 真实 SHA-256 sign + `Authorization=token`
 * 已经在那里实现，这里不复制任何签名代码。
 *
 * **线上协议字段名保持服务端原样**（SN / goodsId / skuId / token / orderNo / promotions），
 * `pangguai-` 前缀只用于本地存储与内部状态，绝不加到请求字段上。
 *
 * 用水是「先用水、后结算」：不引支付宝 SDK、不传 prepayParam、不创建 trade 订单。
 * 扣款由服务端凭已签约的免密代扣完成，客户端只做渠道选定与订单同步。
 */
class PangguaiWaterApi(context: Context) {

    private val api = PangguaiApi(context)

    /** 抓包实测的固定三项，与参考仓库 LifeDeviceProtocol.Promotions 一致。 */
    private val promotions =
        """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},""" +
            """{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},""" +
            """{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"-8"}]"""

    private fun form(vararg pairs: Pair<String, String>) = api.form(*pairs)

    /** 只取 `data` 对象。 */
    private suspend fun data(token: String, path: String, body: FormBody): ApiResult<JSONObject> =
        when (val r = api.callBusiness(path, body, token)) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.optJSONObject("data") ?: JSONObject())
            is ApiResult.Err -> r
        }

    /** 返回完整信封：`data` 是数组或布尔时用。 */
    private suspend fun envelope(token: String, path: String, body: FormBody) =
        api.callBusiness(path, body, token)

    /** 扫码：参数是 `SN=<15 位 IMEI>`，返回 `id`(goodsId) + 数字 shopId + categoryCode。 */
    suspend fun scan(token: String, sn: String): ApiResult<PgWaterScan> =
        when (val r = data(token, "/goods/scan/v2", form("SN" to sn, "token" to token))) {
            is ApiResult.Ok -> {
                val id = r.data.optString("id")
                if (id.isBlank()) ApiResult.Err("扫码结果缺少设备编号")
                else ApiResult.Ok(
                    PgWaterScan(
                        goodsId = id,
                        routeShopId = r.data.optString("shopId"),
                        categoryCode = r.data.optString("categoryCode"),
                        categoryName = r.data.optString("categoryName"),
                    )
                )
            }
            is ApiResult.Err -> r
        }

    suspend fun details(token: String, goodsId: String): ApiResult<PgWaterDetail> =
        when (val r = data(token, "/goods/normal/details", form("goodsId" to goodsId, "token" to token))) {
            is ApiResult.Ok -> ApiResult.Ok(
                PgWaterDetail(
                    name = r.data.optString("name").ifBlank { goodsId },
                    orgName = r.data.optString("orgName"),
                    deviceId = r.data.optString("deviceId"),
                    imei = r.data.optString("imei"),
                    supportAppUserFinish = r.data.optBoolean("supportAppUserFinish", true),
                    payment = r.data.optString("payment"),
                )
            )
            is ApiResult.Err -> r
        }

    /** `data` 是数组，必须走完整信封。 */
    suspend fun skus(token: String, goodsId: String): ApiResult<PgWaterSku> =
        when (val r = envelope(token, "/goods/normal/skus", form("goodsId" to goodsId, "token" to token))) {
            is ApiResult.Ok -> {
                val arr = r.data.optJSONArray("data")
                    ?: return ApiResult.Err("这台设备没有可用的用水模式")
                val sku = (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optBoolean("scanAvailable", true) }
                    ?: return ApiResult.Err("这台设备没有可用的用水模式")
                ApiResult.Ok(
                    PgWaterSku(
                        skuId = sku.optString("skuId"),
                        name = sku.optString("name"),
                    )
                )
            }
            is ApiResult.Err -> r
        }

    /** 选定后付渠道 method=15（支付宝免密代扣），不做任何支付 SDK / 签约。 */
    suspend fun selectAfterPay(token: String): ApiResult<Unit> =
        when (val r = envelope(
            token, "/payChannelRoute/addUserAfterPayChannel",
            form("method" to "15", "token" to token)
        )) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    /** 启动：`04` 饮水机走 water/unlock，`08` 淋浴走 shower/unlock。 */
    suspend fun unlock(token: String, skuId: String, categoryCode: String): ApiResult<PgWaterUnlock> {
        val path = if (categoryCode == "04") "/goods/water/unlock" else "/goods/shower/unlock"
        return when (val r = data(
            token, path,
            form("skuId" to skuId, "promotions" to promotions, "token" to token)
        )) {
            is ApiResult.Ok -> {
                val no = r.data.optString("orderNo")
                if (no.isBlank()) ApiResult.Err("启动未返回订单号")
                else ApiResult.Ok(PgWaterUnlock(r.data.optString("msgId"), no))
            }
            is ApiResult.Err -> r
        }
    }

    suspend fun message(token: String, msgId: String): ApiResult<Unit> =
        when (val r = envelope(token, "/machine/msg/sync", form("msgId" to msgId, "token" to token))) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    /** 运行态：淋浴与饮水机都轮询这个接口（抓包证实）。 */
    suspend fun sync(token: String, skuId: String): ApiResult<PgWaterReading> =
        when (val r = data(token, "/goods/water/sync", form("skuId" to skuId, "token" to token))) {
            is ApiResult.Ok -> ApiResult.Ok(
                PgWaterReading(
                    status = r.data.optString("status").toIntOrNull(),
                    workStatus = r.data.optString("workStatus").toIntOrNull(),
                    orderNo = r.data.optString("identify"),
                    amount = r.data.optString("amount").takeIf { it.isNotBlank() },
                )
            )
            is ApiResult.Err -> r
        }

    /**
     * 主动结束。`supportAppUserFinish=false` 的设备也照发（用户要求先试后端能不能用）。
     * 返回 true **不等于结束**，必须等 sync 上报 status=5 / workStatus=1。
     */
    suspend fun end(token: String, goodsId: String, categoryCode: String): ApiResult<Boolean> {
        val path = if (categoryCode == "04") "/goods/endWater" else "/shower/goods/remoteEnd"
        return when (val r = envelope(token, path, form("goodsId" to goodsId, "token" to token))) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.optBoolean("data", false))
            is ApiResult.Err -> r
        }
    }

    /** 非零用量后的订单结算同步（不是支付）。 */
    suspend fun createAfterPay(token: String, orderNo: String): ApiResult<Unit> =
        when (val r = data(token, "/order/afterPay/creating", form("orderNo" to orderNo, "token" to token))) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    suspend fun syncOrder(token: String, orderNo: String): ApiResult<Unit> =
        when (val r = data(
            token, "/order/sync",
            form("orderNo" to orderNo, "payType" to "0", "token" to token)
        )) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }
}

data class PgWaterScan(
    val goodsId: String,
    val routeShopId: String,
    val categoryCode: String,
    val categoryName: String,
)

data class PgWaterDetail(
    val name: String,
    val orgName: String,
    val deviceId: String,
    val imei: String,
    val supportAppUserFinish: Boolean,
    val payment: String,
)

data class PgWaterSku(val skuId: String, val name: String)

data class PgWaterUnlock(val msgId: String, val orderNo: String)
