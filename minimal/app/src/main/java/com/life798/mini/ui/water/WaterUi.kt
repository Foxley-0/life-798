package com.life798.mini.ui.water

import com.life798.mini.ui.UiState

/**
 * 「启动设备」页与「正在用水」页共用的视图数据（方案 B）。
 *
 * 798 和胖乖各构造一份，Compose 组件只读这个，不再依赖 [UiState]。
 * 这样版式只存在一份，改一处两边都变；两个平台的会话、存储、请求仍然各管各的。
 *
 * @param deviceCode 那行灰色 13sp 等宽小字：798 是设备号，胖乖是 `details.deviceId`。
 */
data class WaterUi(
    val deviceName: String = "",
    val deviceCode: String = "",
    val busy: Boolean = false,
    val stopping: Boolean = false,
    val running: Boolean = false,
    val closed: Boolean = false,
    val startedAt: Long? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** 798：把现有 UiState 逐字段投影过来，行为完全不变。 */
fun UiState.toWaterUi(): WaterUi = WaterUi(
    deviceName = session?.deviceName ?: deviceName,
    deviceCode = session?.deviceId ?: deviceId,
    busy = busy,
    stopping = stopping,
    running = session != null,
    closed = closed && session == null,
    startedAt = session?.startedAt,
    message = message,
    messageIsError = messageIsError,
)
