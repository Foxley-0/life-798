package com.life798.mini.core.water

/**
 * 胖乖用水（饮水机 / 淋浴）的运行态模型。
 *
 * 与 798 的 `DeviceSession` 完全独立 —— 798 的 `UiState.session` 是全局单份，
 * 被 `startDevice()` 当互斥闸门、被 6 处导航逻辑假定只属于 798，绝不能塞进来。
 */

enum class PgWaterPhase {
    IDLE, LOADING, READY, STARTING, RUNNING, STOPPING, ENDED
}

/** 一台胖乖用水设备。`sn` / `goodsId` 只用于接口，界面显示名字与 `deviceId`。 */
data class PgWaterDevice(
    val goodsId: String,
    val sn: String,
    val deviceId: String,
    val imei: String,
    val name: String,
    val orgName: String,
    val categoryCode: String,
    val categoryName: String,
    val routeShopId: String,
    val skuId: String,
    val skuName: String,
    val supportAppUserFinish: Boolean,
)

/** 一次用水会话。落盘用，进程被杀后能恢复。 */
data class PgWaterSession(
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val goodsId: String,
    val skuId: String,
    val name: String,
    val deviceId: String,
    val categoryCode: String,
    val orderNo: String,
    val msgId: String,
    val startedAt: Long = System.currentTimeMillis(),
)

/** `/goods/water/sync` 的运行态。 */
data class PgWaterReading(
    val status: Int?,
    val workStatus: Int?,
    val orderNo: String,
    /** 单位未知，只用来判零用量分支，绝不展示成金额。 */
    val amount: String?,
)
