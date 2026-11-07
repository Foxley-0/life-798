package com.life798.mini.net

import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.laundry.LaundryReading
import kotlinx.coroutines.delay
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject

/** `/goods/scan/v2` 的结果。NQT 换出来的不是设备编号，是 goodsId。 */
data class PgScanInfo(
    val goodsId: String,
    val shopId: String,
    val categoryName: String,
    val type: String,
)

/** `/goods/normal/details` 里本功能需要的字段。 */
data class PgGoodsInfo(
    val goodsId: String,
    val name: String,
    val orgName: String,
    val deviceId: String,
    val imei: String,
    val machineId: String,
    /** 扫码返回的数字 shopId，付费渠道接口要用；与 details 的字符串 shopId 不是一个东西。 */
    val shopId: String = "",
)

/** `/goods/normal/skus` 的一个洗衣模式。 */
data class PgSku(
    val skuId: String,
    val name: String,
    val price: String,
    val scanAvailable: Boolean,
    /** skus 的 `unit`，抓包与参考仓库都确认是分钟。 */
    val unitMinutes: Int = 0,
    /** 程序说明，例如「适用不太脏的衣物」。 */
    val feature: String = "",
)

/** 扫码预检完成：设备 + 全部可用程序。[sku] 只是 0 元链路的内部兜底，界面不默认选中。 */
data class PgWasherReady(
    val goods: PgGoodsInfo,
    val sku: PgSku,
    val skus: List<PgSku> = emptyList(),
    val categoryName: String,
)

/** `trade/preview/v4` 的应付价与时长；价格以服务端 realPrice 为准，不是 SKU 标价。 */
data class PgQuote(val price: String, val minutes: Int?)

/** `payChannelRoute/prePay` 返回的支付方式。本期只接 13 支付宝。 */
data class PgPayChannel(
    val method: String,
    val name: String,
    val available: Boolean,
    val needsOpen: Boolean,
    val remark: String?,
) {
    val alipayReady: Boolean get() = method == "13" && available && !needsOpen
}

/** 一次性唤起支付宝的事件；payload 是服务端签好的订单串，原样传递。 */
data class PgAlipayLaunch(val id: Long, val orderNo: String, val payload: String)

/** 启动成功的结果。字段够拼出一条运行态会话，不再只留订单号。 */
data class PgWasherLaunch(
    val orderNo: String,
    val deviceName: String,
    val goodsId: String,
    val skuId: String,
    val skuName: String,
    val shopName: String,
    val minutes: Int,
    val price: String,
)

/**
 * 胖乖生活「扫码启动洗衣机 · 桶自洁」的业务接口编排。
 *
 * 链路全部来自 2026-11-07 手机抓包（ProxyPin，官方 App v1.145.0 的一次完整
 * 0 元桶自洁启动），顺序不能随便改：设备检查要在下单前，支付确认要在启动查询前。
 *
 * 本期**只做 0 元的桶自洁**：
 * - 不实现余额体系，也不接支付宝 / 微信；付费 SKU 一律不发起下单。
 * - [ZERO_AMOUNT_PAY_TYPE] 只是 0 元订单在服务端 `payChannelRoute/prePay` 里
 *   唯一的渠道标识（抓包实测 0 元时只返回「余额支付」这一项），金额为 0，
 *   不产生任何扣款。**禁止把它用于付费订单**，支付路径抓包完成后另起实现。
 *
 * 请求头、token 位置、信封解析全部复用 [PangguaiApi]，不另起一套 OkHttp。
 */
class PangguaiWasherApi(private val api: PangguaiApi) {

    private companion object {
        /** 0 元订单的唯一渠道标识；抓包里 payType=2 / payTypeName=余额 / payPrice=0.00。 */
        const val ZERO_AMOUNT_PAY_TYPE = 2

        /** `machine/device_is_start` 可能要等设备回包，最多轮询 8 次、间隔 1 秒。 */
        const val START_ATTEMPTS = 8
        const val START_INTERVAL_MS = 1000L
    }

    /**
     * 扫码后的只读预检：识别设备 → 读详情 → 找 0 元桶自洁 SKU。
     * 不创建订单、不启动设备，可以放心在扫码成功后立刻调用。
     */
    suspend fun prepare(
        token: String,
        nqt: String,
        onStage: (String) -> Unit,
    ): ApiResult<PgWasherReady> {
        onStage("正在识别设备…")
        val scan = when (
            val r = callData(token, "/goods/scan/v2", api.form("NQT" to nqt, "token" to token))
        ) {
            is ApiResult.Ok -> {
                val id = r.data.optString("id")
                if (id.isBlank()) return ApiResult.Err("扫码结果缺少设备编号")
                PgScanInfo(
                    goodsId = id,
                    shopId = r.data.optString("shopId"),
                    categoryName = r.data.optString("categoryName"),
                    type = r.data.optString("type"),
                )
            }

            is ApiResult.Err -> return r
        }

        if (scan.type != "goods" || !scan.categoryName.contains("洗衣")) {
            AppLog.w("Pangguai", "非洗衣机设备 type=${scan.type} category=${scan.categoryName}")
            return ApiResult.Err("这不是洗衣机设备（${scan.categoryName}），本期只支持洗衣机")
        }

        onStage("读取设备信息…")
        val goods = when (
            val r = callData(token, 
                "/goods/normal/details",
                api.form("goodsId" to scan.goodsId, "token" to token),
            )
        ) {
            is ApiResult.Ok -> PgGoodsInfo(
                goodsId = scan.goodsId,
                name = r.data.optString("name").ifBlank { scan.goodsId },
                orgName = r.data.optString("orgName"),
                deviceId = r.data.optString("deviceId"),
                imei = r.data.optString("imei"),
                machineId = r.data.optString("machineId"),
                shopId = scan.shopId,
            )

            is ApiResult.Err -> return r
        }

        onStage("查询洗衣模式…")
        // 这个接口的 data 是数组，必须走 callRaw，走 call 会被当成对象丢掉。
        val skus = when (
            val r = callEnvelope(token, 
                "/goods/normal/skus",
                api.form("goodsId" to scan.goodsId, "token" to token),
            )
        ) {
            is ApiResult.Ok -> {
                val arr = r.data.optJSONArray("data")
                    ?: return ApiResult.Err("设备没有可用的洗衣模式")
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        PgSku(
                            skuId = o.optString("skuId"),
                            name = o.optString("name"),
                            price = o.optString("price"),
                            scanAvailable = o.optBoolean("scanAvailable", true),
                            unitMinutes = o.optString("unit").toIntOrNull() ?: 0,
                            feature = o.optString("feature"),
                        )
                    }
                }
            }

            is ApiResult.Err -> return r
        }

        // 不默认选中：sku 只作为 0 元链路的内部兜底，界面选中态由 selectedSkuId 决定
        val fallback = skus.firstOrNull {
            it.scanAvailable && it.name.contains("桶自洁") && isZero(it.price)
        } ?: skus.firstOrNull { it.scanAvailable }
        ?: return ApiResult.Err("这台设备没有可用的洗衣模式")

        return ApiResult.Ok(
            PgWasherReady(
                goods = goods,
                sku = fallback,
                skus = skus.filter { it.scanAvailable },
                categoryName = scan.categoryName,
            )
        )
    }

    /**
     * 真正启动一次桶自洁。**会创建订单并启动设备**，只能由用户点按钮触发。
     *
     * 任何一步失败都立刻停下，不重试写操作 —— 重复 `trade/create` 会生成多笔订单。
     */
    suspend fun launch(
        token: String,
        ready: PgWasherReady,
        onStage: (String) -> Unit,
    ): ApiResult<PgWasherLaunch> {
        val goodsId = ready.goods.goodsId
        val skuId = ready.sku.skuId
        val items = itemsJson(goodsId, skuId)

        // 1. 试算：金额必须是 0，否则绝不往下走
        onStage("核对订单金额…")
        val realPrice = when (
            val r = callData(token, 
                "/trade/preview/v4",
                api.form(
                    "items" to items,
                    "promotions" to "[]",
                    "isAutoSelectPromotion" to "1",
                    "token" to token,
                ),
            )
        ) {
            is ApiResult.Ok -> r.data.optString("realPrice")
            is ApiResult.Err -> return r
        }
        if (!isZero(realPrice)) {
            return ApiResult.Err("该模式不是 0 元（¥$realPrice），本期只支持桶自洁")
        }

        // 2. 设备健康检查 → msgId，再同步一次指令通道
        onStage("检查设备状态…")
        val msgId = when (
            val r = callData(token, 
                "/trade/check_device_status",
                api.form("goodsId" to goodsId, "token" to token),
            )
        ) {
            is ApiResult.Ok -> {
                if (!r.data.optBoolean("health", false)) {
                    // health=false 不等于「离线」：设备工作中、故障、维护都会走这里，
                    // 服务端在 faultDesc 里给了准确原因（例如「设备工作中，请更换设备使用」）。
                    // 必须原样透传，否则会把「正在使用中」误报成「不在线」。
                    val reason = r.data.optString("faultDesc").takeIf { it.isNotBlank() }
                        ?: "设备暂不可用，请稍后再试"
                    AppLog.w("Pangguai", "设备检查未通过：$reason")
                    return ApiResult.Err(reason)
                }
                r.data.optString("msgId")
            }

            is ApiResult.Err -> return r
        }
        if (msgId.isNotBlank()) {
            // 抓包里 data=false 也继续下单，这里不把它的返回值当成功条件。
            callData(token, "/machine/sync", api.form("msgId" to msgId, "token" to token))
        }

        // 3. 创建订单
        onStage("创建订单…")
        val orderNo = when (
            val r = callData(token, 
                "/trade/create",
                api.form("items" to items, "promotions" to "[]", "token" to token),
            )
        ) {
            is ApiResult.Ok -> r.data.optString("tradeNo").ifBlank {
                r.data.optString("orderId")
            }

            is ApiResult.Err -> return r
        }
        if (orderNo.isBlank()) return ApiResult.Err("下单未返回订单号，请到胖乖 App 确认")

        // 4. 零元订单确认（不是余额支付，见类注释）
        onStage("确认零元订单…")
        when (
            val r = callData(token, 
                "/pay/unify/pay",
                api.form(
                    "orderNo" to orderNo,
                    "payType" to ZERO_AMOUNT_PAY_TYPE.toString(),
                    "token" to token,
                ),
            )
        ) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> Unit
        }
        when (
            val r = boolCall(
                "/order/pay_status",
                "orderNo" to orderNo,
                "payType" to ZERO_AMOUNT_PAY_TYPE.toString(),
                "token" to token,
            )
        ) {
            is ApiResult.Ok -> if (!r.data) {
                return ApiResult.Err("订单未确认（$orderNo），请到胖乖 App 查看")
            }

            is ApiResult.Err -> return r
        }

        // 5. 等设备回包。抓包里一次就 true，但设备侧有延迟，留几次轮询。
        onStage("等待设备启动…")
        for (i in 1..START_ATTEMPTS) {
            when (
                val r = boolCall(
                    "/machine/device_is_start",
                    "orderNo" to orderNo,
                    "token" to token,
                )
            ) {
                is ApiResult.Ok -> if (r.data) {
                    AppLog.i("Pangguai", "桶自洁已启动 orderNo=$orderNo")
                    return ApiResult.Ok(
                        PgWasherLaunch(
                            orderNo = orderNo,
                            deviceName = ready.goods.name,
                            goodsId = goodsId,
                            skuId = skuId,
                            skuName = ready.sku.name,
                            shopName = ready.goods.orgName,
                            minutes = ready.sku.unitMinutes,
                            price = realPrice,
                        )
                    )
                }

                is ApiResult.Err -> return r
            }
            onStage("等待设备启动…（$i/$START_ATTEMPTS）")
            delay(START_INTERVAL_MS)
        }
        return ApiResult.Err("已下单但未检测到设备启动，订单号 $orderNo")
    }

    /** 选中程序后读取服务端应付价；预览若含已使用优惠就停止，避免价格与确认不一致。 */
    suspend fun preview(token: String, goodsId: String, skuId: String): ApiResult<PgQuote> {
        val r = callData(
            token,
            "/trade/preview/v4",
            api.form(
                "items" to itemsJson(goodsId, skuId),
                "promotions" to "[]",
                "isAutoSelectPromotion" to "0",
                "token" to token,
            ),
        )
        if (r is ApiResult.Err) return r
        val d = (r as ApiResult.Ok).data
        val price = d.optString("realPrice")
        if (price.isBlank()) return ApiResult.Err("未返回应付金额")
        val promos = d.optJSONArray("promotions")
        if (promos != null && (0 until promos.length()).any {
                promos.optJSONObject(it)?.optBoolean("isUsed", false) == true
            }) return ApiResult.Err("该价格含优惠，请在胖乖生活确认")
        val item = d.optJSONArray("items")?.optJSONObject(0)
        val raw = item?.opt("skuInfo")
        val info = raw as? JSONObject
            ?: (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return ApiResult.Ok(PgQuote(price, info?.optString("markMinutes")?.toIntOrNull()))
    }

    /** 支付渠道列表，只展示服务端真实返回的项。 */
    suspend fun payChannels(
        token: String, imei: String, goodsId: String, price: String, shopId: String,
    ): ApiResult<List<PgPayChannel>> {
        val r = callEnvelope(
            token,
            "/payChannelRoute/prePay",
            api.form(
                "imei" to imei, "goodsId" to goodsId, "hitAd" to "0", "payPrice" to price,
                "useTicket" to "0", "shopId" to shopId,
                "combineOrderStatus" to "false", "confirmSelectedStatus" to "false",
                "token" to token,
            ),
        )
        if (r is ApiResult.Err) return r
        val arr = (r as ApiResult.Ok).data.optJSONArray("data")
            ?: return ApiResult.Err("未返回支付方式")
        return ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                PgPayChannel(
                    method = o.optString("method"),
                    name = o.optString("methodName"),
                    available = o.optBoolean("useStatus", false),
                    needsOpen = o.optBoolean("needOpenFlag", true),
                    remark = o.optString("notUseRemark").takeIf { it.isNotBlank() },
                )
            }
        })
    }

    /**
     * 付费订单的下单段：渠道校验 → 设备检查 → 指令同步 → 创建订单。
     * 返回 orderNo，**不在这里发起支付**；调用方必须保证 createSent，避免重复下单。
     */
    suspend fun createPaidOrder(
        token: String, goodsId: String, skuId: String, price: String,
        imei: String, shopId: String,
    ): ApiResult<String> {
        val channels = when (val r = payChannels(token, imei, goodsId, price, shopId)) {
            is ApiResult.Ok -> r.data
            is ApiResult.Err -> return r
        }
        if (channels.none { it.alipayReady }) {
            return ApiResult.Err("当前订单没有可用的支付宝渠道，请在胖乖生活完成支付")
        }
        val msgId = when (
            val r = callData(
                token, "/trade/check_device_status",
                api.form("goodsId" to goodsId, "token" to token),
            )
        ) {
            is ApiResult.Ok -> {
                if (!r.data.optBoolean("health", false)) {
                    // health=false 不等于「离线」：设备工作中、故障、维护都会走这里，
                    // 服务端在 faultDesc 里给了准确原因（例如「设备工作中，请更换设备使用」）。
                    // 必须原样透传，否则会把「正在使用中」误报成「不在线」。
                    val reason = r.data.optString("faultDesc").takeIf { it.isNotBlank() }
                        ?: "设备暂不可用，请稍后再试"
                    AppLog.w("Pangguai", "设备检查未通过：$reason")
                    return ApiResult.Err(reason)
                }
                r.data.optString("msgId")
            }
            is ApiResult.Err -> return r
        }
        if (msgId.isNotBlank()) {
            callData(token, "/machine/sync", api.form("msgId" to msgId, "token" to token))
        }
        return when (
            val r = callData(
                token, "/trade/create",
                api.form("items" to itemsJson(goodsId, skuId), "promotions" to "[]", "token" to token),
            )
        ) {
            is ApiResult.Ok -> {
                val no = r.data.optString("tradeNo").ifBlank { r.data.optString("orderId") }
                if (no.isBlank()) ApiResult.Err("下单未返回订单号") else ApiResult.Ok(no)
            }
            is ApiResult.Err -> r
        }
    }

    /** 取服务端签好的支付宝订单串，原样交给 PayTask.payV2，不二次签名。 */
    suspend fun prepareAlipay(token: String, orderNo: String): ApiResult<String> =
        when (
            val r = callData(
                token, "/pay/unify/prePay",
                api.form("orderNo" to orderNo, "payType" to "13", "token" to token),
            )
        ) {
            is ApiResult.Ok -> {
                val payload = r.data.optString("prepayParam")
                if (payload.isBlank()) ApiResult.Err("支付宝参数为空") else ApiResult.Ok(payload)
            }
            is ApiResult.Err -> r
        }

    /** 服务端确认支付：SDK 回调不算付款成功。 */
    suspend fun orderPaid(token: String, orderNo: String): ApiResult<Boolean> =
        boolCall("/order/pay_status", "orderNo" to orderNo, "payType" to "13", "token" to token)

    /** 服务端确认设备启动。 */
    suspend fun waitStarted(token: String, orderNo: String, onStage: (String) -> Unit): Boolean {
        for (i in 1..START_ATTEMPTS) {
            when (val r = boolCall("/machine/device_is_start", "orderNo" to orderNo, "token" to token)) {
                is ApiResult.Ok -> if (r.data) return true
                is ApiResult.Err -> return false
            }
            onStage("等待设备启动…（$i/$START_ATTEMPTS）")
            delay(START_INTERVAL_MS)
        }
        return false
    }

    /**
     * 订单详情：运行态判定与倒计时的**唯一数据源**。
     *
     * `completeTime` 是预计结束时间（启动阶段就可能返回），`finishTime` 才是实际完成；
     * `orderStatus` 0 待支付 / 1 已关闭 / 2 运行中 / 3 已完成。
     * 时间字符串按 Asia/Shanghai 解析（参考仓库 `lifeTimestamp()`）。
     */
    suspend fun orderDetail(token: String, orderNo: String): ApiResult<LaundryReading> =
        when (
            val r = callData(
                token,
                "/order/detail",
                api.form("orderNo" to orderNo, "token" to token),
            )
        ) {
            is ApiResult.Ok -> runCatching { parseOrder(r.data) }.fold(
                onSuccess = { ApiResult.Ok(it) },
                onFailure = { ApiResult.Err("订单详情解析失败") },
            )

            is ApiResult.Err -> r
        }

    private fun parseOrder(d: JSONObject): LaundryReading {
        val item = d.optJSONArray("tradeOrderItem")?.optJSONObject(0) ?: JSONObject()
        val rawInfo = item.opt("skuInfo")
        val info = rawInfo as? JSONObject
            ?: (rawInfo as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        return LaundryReading(
            orderNo = d.optString("orderNo"),
            orderId = d.optString("id"),
            deviceId = item.optString("goodsId"),
            skuId = item.optString("skuId"),
            status = d.optString("orderStatus").toIntOrNull(),
            price = d.optString("payPrice"),
            program = d.optString("machineFunctionName"),
            minutes = d.optString("markMinutes").toIntOrNull(),
            paidAtMillis = lifeTimestamp(d.optString("payTime")),
            expectedEndMillis = lifeTimestamp(d.optString("completeTime")),
            finishedAtMillis = lifeTimestamp(d.optString("finishTime")),
            createdAtMillis = lifeTimestamp(d.optString("createTime")),
            deviceName = d.optString("machineName"),
            shopName = d.optString("shopName"),
            payTypeName = d.optString("payTypeName"),
            feature = info.optString("feature"),
        )
    }

    private fun lifeTimestamp(value: String): Long? = runCatching {
        java.time.LocalDateTime.parse(
            value,
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        ).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    }.getOrNull()

    /** 业务接口统一走带签名的通道；返回 data 对象（登录用的 call 不带签名）。 */
    private suspend fun callData(
        token: String,
        path: String,
        body: FormBody,
    ): ApiResult<JSONObject> = when (val r = api.callBusiness(path, body, token)) {
        is ApiResult.Ok -> ApiResult.Ok(r.data.optJSONObject("data") ?: JSONObject())
        is ApiResult.Err -> r
    }

    /** 返回完整信封：`data` 是数组或布尔时用，不能走只取 JSONObject 的版本。 */
    private suspend fun callEnvelope(
        token: String,
        path: String,
        body: FormBody,
    ): ApiResult<JSONObject> = api.callBusiness(path, body, token)

    /** 布尔型 data 的接口（pay_status / device_is_start / …）统一用信封读取。 */
    private suspend fun boolCall(
        path: String,
        vararg pairs: Pair<String, String>,
    ): ApiResult<Boolean> {
        val token = pairs.firstOrNull { it.first == "token" }?.second.orEmpty()
        return when (val r = callEnvelope(token, path, api.form(*pairs))) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.optBoolean("data", false))
            is ApiResult.Err -> r
        }
    }

    /** 抓包里 items 的元素形态，字段名和字符串类型都要一致。 */
    private fun itemsJson(goodsId: String, skuId: String): String {
        val item = JSONObject()
            .put("amount", "1")
            .put("goodsId", goodsId)
            .put("num", "1")
            .put("price", "")
            .put("skuId", skuId)
            .put("soldType", "1")
        return JSONArray().put(item).toString()
    }

    private fun isZero(price: String): Boolean =
        (price.trim().toDoubleOrNull() ?: Double.NaN).let { it == 0.0 }
}
