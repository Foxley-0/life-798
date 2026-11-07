package com.life798.mini.core.laundry

/**
 * 胖乖洗衣的运行态模型。
 *
 * 移植自 Mercury000/HUEL-yktwx 的 `LaundryModels`（GPL-3.0，作者已授权直接使用），
 * 按本工程范围精简：只保留启动后的运行 / 完成判定，待支付、再次支付等阶段留待后续。
 *
 * 状态语义来自该仓库的 HAR 核验结论，非常重要：
 * - `orderStatus=2` 是**运行中**，`completeTime` 是**预计**结束时间（启动阶段就返回）；
 * - `orderStatus=3` + `finishTime` 才是**真正完成**；
 * - `orderStatus=1` 是已关闭，`0` 是待支付；
 * - 倒计时归零**不能**代替服务端完成确认；`supportAppUserFinish=false`，不做「结束洗衣」。
 */
enum class LaundryPhase { Starting, Running, Ended, Closed }

/** `/order/detail` 里本功能需要的字段。时间一律按 Asia/Shanghai 解析成毫秒。 */
data class LaundryReading(
    val orderNo: String,
    val orderId: String,
    val deviceId: String,
    val skuId: String,
    val status: Int?,
    val price: String,
    val program: String,
    val minutes: Int?,
    val paidAtMillis: Long?,
    val expectedEndMillis: Long?,
    val finishedAtMillis: Long?,
    val createdAtMillis: Long?,
    val deviceName: String,
    val shopName: String,
    val payTypeName: String,
    val feature: String,
)

/**
 * 一次洗衣会话。按 `sessionId` 区分，允许同时洗多台。
 * 落盘在 `PangguaiLaundryStore`，与 798 的 `DeviceSession` 完全分开。
 */
data class LaundrySession(
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val orderNo: String,
    val deviceId: String,
    val deviceName: String,
    val skuId: String = "",
    val skuName: String = "",
    val shopName: String = "",
    val price: String = "0.00",
    val minutes: Int = 0,
    val phase: LaundryPhase = LaundryPhase.Starting,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val expectedEndMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
) {
    /** 仍需在卡片上显示、且需要继续轮询的状态。已完成/已关闭保留展示但不轮询。 */
    val running: Boolean get() = phase == LaundryPhase.Starting || phase == LaundryPhase.Running

    val statusLabel: String
        get() = when (phase) {
            LaundryPhase.Starting -> "正在启动"
            LaundryPhase.Running -> "洗衣中"
            LaundryPhase.Ended -> "已完成"
            LaundryPhase.Closed -> "已关闭"
        }

    val displayName: String
        get() = listOf(deviceName, skuName).filter { it.isNotBlank() }
            .joinToString(" · ").ifBlank { "胖乖洗衣机" }
}
