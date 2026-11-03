package com.life798.mini.core

/** 网络返回的统一封装。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>

    /**
     * @param code 服务端业务码；0 表示成功，-99 表示登录态失效
     * @param message 可直接展示给用户的文案
     */
    data class Err(val message: String, val code: Int = 0) : ApiResult<Nothing>
}

/** 登录成功后拿到的东西。 */
data class Session(
    val token: String,
    val uid: String,
    val phone: String
)

/**
 * 设备运行状态，来自 `/ui/app/dev/status`。
 *
 * 服务端用 `data.device.gene.status` 表示出水状态：
 * 1 = 运行中，99 = 已停止/结算中。注意 `data.device.status` 是设备在线状态，
 * 和出不出水无关，不能拿来判断。
 */
data class DeviceStatus(
    /** true 表示设备正在出水。 */
    val running: Boolean,
    /** 服务端原始状态码，仅用于日志排查。 */
    val rawStatus: Int,
    /**
     * 设备累计出水总量，单位升。
     *
     * 注意：这不是实时值，接水过程中不会变，只有结算完成后才跳。
     * 抓包实测关闭后 3 秒仍是旧值，6 秒才更新。
     */
    val outLiters: Double,
    /**
     * 本次会话的起始时刻（毫秒），来自 `gene.time`。
     *
     * 只在 `status=1` 时下发，停水后整个字段消失。它是**会话的身份证**：
     * 冷启动对账时拿它和本地存的记录比，一致才说明还是同一次用水，
     * 不一致说明上一次早结束了、这是别人新开的。
     */
    val sessionTime: Long? = null
)
