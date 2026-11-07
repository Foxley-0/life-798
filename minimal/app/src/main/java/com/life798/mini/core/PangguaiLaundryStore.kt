package com.life798.mini.core

import android.content.Context
import com.life798.mini.core.laundry.LaundryPhase
import com.life798.mini.core.laundry.LaundrySession
import org.json.JSONArray
import org.json.JSONObject

/**
 * 胖乖洗衣会话的本地记录。
 *
 * 沿用 `pangguai-` 前缀与双平台隔离：key 是 `pangguai-sessions`，
 * 独立 SP 文件，不写 798 的账本、常用设备或 `DeviceSession`。
 * 用 `commit()` 同步落盘 —— 启动成功后进程立刻被杀，这条记录不能丢。
 *
 * 按 `sessionId` 保存多条，对应参考仓库 `LifeDeviceAccountStore` 的多会话结构。
 */
class PangguaiLaundryStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mini_laundry", Context.MODE_PRIVATE)

    private val key = "pangguai-sessions"

    fun load(): List<LaundrySession> = runCatching {
        val raw = sp.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            runCatching { read(arr.getJSONObject(i)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun save(sessions: List<LaundrySession>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(write(it)) }
        sp.edit().putString(key, arr.toString()).commit()
    }

    fun clear() {
        sp.edit().remove(key).commit()
    }

    private fun write(s: LaundrySession): JSONObject = JSONObject()
        .put("sessionId", s.sessionId)
        .put("orderNo", s.orderNo)
        .put("deviceId", s.deviceId)
        .put("deviceName", s.deviceName)
        .put("skuId", s.skuId)
        .put("skuName", s.skuName)
        .put("shopName", s.shopName)
        .put("price", s.price)
        .put("minutes", s.minutes)
        .put("phase", s.phase.name)
        .put("startedAt", s.startedAtMillis)
        .put("createdAt", s.createdAtMillis ?: 0L)
        .put("expectedEnd", s.expectedEndMillis ?: 0L)
        .put("finishedAt", s.finishedAtMillis ?: 0L)

    private fun read(o: JSONObject): LaundrySession = LaundrySession(
        sessionId = o.optString("sessionId").ifBlank { java.util.UUID.randomUUID().toString() },
        orderNo = o.optString("orderNo"),
        deviceId = o.optString("deviceId"),
        deviceName = o.optString("deviceName"),
        skuId = o.optString("skuId"),
        skuName = o.optString("skuName"),
        shopName = o.optString("shopName"),
        price = o.optString("price").ifBlank { "0.00" },
        minutes = o.optInt("minutes", 0),
        phase = runCatching { LaundryPhase.valueOf(o.optString("phase")) }
            .getOrDefault(LaundryPhase.Starting),
        startedAtMillis = o.optLong("startedAt", System.currentTimeMillis()),
        createdAtMillis = o.optLong("createdAt", 0L).takeIf { it > 0 },
        expectedEndMillis = o.optLong("expectedEnd", 0L).takeIf { it > 0 },
        finishedAtMillis = o.optLong("finishedAt", 0L).takeIf { it > 0 },
    )
}
