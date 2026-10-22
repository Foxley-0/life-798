package com.water.widget.ui

import com.water.widget.WaterBillParser
import com.water.widget.WaterConsumption
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WaterBillLogUiState(
    val id: String,
    val timeText: String,
    val title: String,
    val payment: WaterBillPaymentUiState? = null,
    val loading: Boolean = false,
    val error: String? = null
)

data class PaymentPartUiState(val label: String, val value: String)

data class WaterBillPaymentUiState(val totalText: String, val parts: List<PaymentPartUiState>)

object WaterBillUiStateFactory {
    fun records(response: JSONObject?): List<WaterBillLogUiState> {
        require(response != null && response.optInt("code", -1) == 0) { "接水记录获取失败，请下拉重试" }
        val records = requireNotNull(response.optJSONArray("data")) { "接水记录格式异常，请稍后重试" }
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return (0 until records.length()).mapNotNull { index -> records.optJSONObject(index) }
            .filter { it.optInt("type") == 91 && it.optInt("cata") == 6 && it.optInt("status") == 3 }
            .filter { it.optString("id").isNotBlank() }
            .distinctBy { it.optString("id") }
            .sortedByDescending { it.optLong("utime", it.optLong("ctime")) }
            .map { bill ->
                WaterBillLogUiState(
                    id = bill.optString("id"),
                    timeText = format.format(Date(bill.optLong("utime", bill.optLong("ctime")))),
                    title = bill.optJSONObject("dev")?.optString("name")?.takeIf { it.isNotBlank() } ?: "接水消费"
                )
            }
    }

    fun payment(response: JSONObject?, billId: String): WaterBillPaymentUiState {
        require(response != null && response.optInt("code", -1) == 0) { "支付明细获取失败，请重试" }
        val bill = requireNotNull(response.optJSONObject("data")?.optJSONObject("bill")) { "支付明细暂不可用，请重试" }
        require(bill.optString("id") == billId && bill.optInt("type") == 91 &&
            bill.optInt("cata") == 6 && bill.optInt("status") == 3) { "支付明细暂不可用，请重试" }
        val payment = bill.optDouble("payment", 0.0)
        val gift = bill.optJSONObject("mk")?.optDouble("olGift", 0.0) ?: 0.0
        val discount = if (bill.optJSONObject("promo")?.optInt("type") == 4) bill.optDouble("discount", 0.0) else 0.0
        require(payment.isFinite() && payment >= 0 && gift.isFinite() && gift >= 0 &&
            discount.isFinite() && discount >= 0 && gift <= payment + 0.000001) { "支付明细金额异常，请稍后重试" }
        val parts = buildList {
            if (gift > 0) add(PaymentPartUiState("钱包礼品金", money(gift)))
            val remaining = (payment * 1000).roundToInt() - (gift * 1000).roundToInt()
            if (remaining > 0) add(PaymentPartUiState(if (gift > 0) "其他金额支付" else "金额支付（渠道未标明）", money(remaining / 1000.0)))
            if (discount > 0) add(PaymentPartUiState("积分抵扣", "${(discount * 1000).roundToInt()} 积分 · ${money(discount)}"))
        }
        return WaterBillPaymentUiState(money(payment + discount), parts)
    }

    fun consumption(response: JSONObject?, billId: String): WaterConsumption? {
        // 沿用明细的金额及订单校验；通知解析可接受零元完成单；历史消费同步仍忽略零金额。
        payment(response, billId)
        val bill = response!!.getJSONObject("data").getJSONObject("bill")
        require(bill.optLong("utime", 0L) > 0L || bill.optLong("ctime", 0L) > 0L) { "账单时间暂不可用" }
        return WaterBillParser.fromDetail(response, billId, "")?.takeIf { it.scoreEquivalent > 0 }
    }

    private fun money(value: Double) = "¥" + String.format(Locale.CHINA, "%.2f", value)
}
