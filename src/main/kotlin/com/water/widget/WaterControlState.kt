package com.water.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WaterControlSession(val reservationId: Long, val accountKey: String, val deviceId: String, val starting: Boolean = true)

/** 仅保存当前进程的真实接水会话，不从历史记录恢复运行状态。 */
object WaterControlState {
    private val mutableState = MutableStateFlow<WaterControlSession?>(null)
    val state = mutableState.asStateFlow()

    @JvmStatic fun begin(id: Long, accountKey: String, deviceId: String) {
        mutableState.value = WaterControlSession(id, accountKey, deviceId)
    }
    @JvmStatic fun started(id: Long) {
        mutableState.update { if (it?.reservationId == id) it.copy(starting = false) else it }
    }
    @JvmStatic fun clear(id: Long) {
        mutableState.update { if (it?.reservationId == id) null else it }
    }
}
