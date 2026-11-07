package com.life798.mini.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.alipay.sdk.app.PayTask
import com.life798.mini.core.AppLog
import com.life798.mini.net.PgAlipayLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次性唤起支付宝 App 支付。
 *
 * 移植自 Mercury000/HUEL-yktwx 的 `LifeAlipayEffect`（GPL-3.0，作者已授权）：
 * `prepayParam` 是服务端签好的订单串，**原样传给 PayTask.payV2**，客户端不二次签名。
 *
 * SDK 返回结果**不作为付款成功依据**，只通知 ViewModel 去查
 * `order/pay_status` 与 `machine/device_is_start`。
 */
@Composable
internal fun PangguaiAlipayEffect(
    request: PgAlipayLaunch?,
    onResult: (Long) -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(request?.id) {
        val launch = request ?: return@LaunchedEffect
        if (activity == null) {
            AppLog.w("Pangguai", "取不到 Activity，跳过支付宝唤起")
            onResult(launch.id)
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching { PayTask(activity).payV2(launch.payload, true) }
                .onFailure { AppLog.w("Pangguai", "支付宝唤起失败: ${it.message}") }
        }
        onResult(launch.id)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
