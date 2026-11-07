package com.life798.mini.core

import android.content.Context
import org.json.JSONObject

/**
 * 当前这一次用水会话。
 *
 * 只用来回答一个问题：**我现在是不是还在用水**。
 *
 * 它不参与任何记账 —— 账全部来自服务端账单（见 [BillStore]）。
 * 之前那套「记基线、算差值、轮询结算」的逻辑已经全部删掉了。
 *
 * @param deviceId 设备编号
 * @param deviceName 设备名，来自 `/ui/app/dev/home`，拿不到时退化成编号
 * @param startedAt 手机时间，只用于界面显示「22:24 开始」
 * @param sessionTime 服务端 `gene.time`，会话的身份证，用来核对还是不是这一次
 */
data class DeviceSession(
    val deviceId: String,
    val deviceName: String?,
    val startedAt: Long,
    val sessionTime: Long?
)

/**
 * 会话记录，全局只保留一条。
 *
 * 存在 SharedPreferences 里，`commit()` 同步落盘 —— 启动成功后进程立刻被杀，
 * 这条记录也不能丢，否则重开 App 就不知道还有设备在流水。
 */
class DeviceSessionStore(
    context: Context,
    platform: Platform = Platform.HUI798,
) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 798 是 `current`（历史 key 不变），胖乖是 `pangguai-current`。
     * 本期胖乖不产生用水会话，但 key 先按平台分开，避免将来接入时串到 798 的会话上。
     */
    private val keySession = "${platform.prefix}$KEY_SESSION"

    fun load(): DeviceSession? {
        val raw = sp.getString(keySession, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val id = obj.optString("id")
            if (id.isBlank()) return null
            DeviceSession(
                deviceId = id,
                deviceName = obj.optString("name").takeIf { it.isNotBlank() },
                startedAt = obj.optLong("at", 0L),
                sessionTime = obj.optLong("st", 0L).takeIf { it > 0L }
            )
        }.getOrNull()
    }

    fun save(session: DeviceSession) {
        val obj = JSONObject()
            .put("id", session.deviceId)
            .put("at", session.startedAt)
        session.deviceName?.let { obj.put("name", it) }
        session.sessionTime?.let { obj.put("st", it) }
        sp.edit().putString(keySession, obj.toString()).commit()
    }

    fun clear() {
        sp.edit().remove(keySession).commit()
    }

    private companion object {
        const val PREFS = "device_session"
        const val KEY_SESSION = "current"
    }
}
