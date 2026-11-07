package com.life798.mini.core.water

import android.content.Context
import com.life798.mini.core.Platform
import org.json.JSONArray
import org.json.JSONObject

/**
 * 胖乖用水本地存储。
 *
 * 沿用全仓 `pangguai-` 前缀约定：所有 key 由 [Platform.PANGGUAI] 的 prefix 生成，
 * 不硬编码字符串；798 的空前缀数据一个字节都不碰。
 * 用 SharedPreferences + `commit()` 同步落盘 —— 启动成功后进程立刻被杀也不能丢。
 */
class PangguaiWaterStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mini_pangguai_water", Context.MODE_PRIVATE)

    private val keyDevices = Platform.PANGGUAI.prefix + "water-devices"
    private val keySessions = Platform.PANGGUAI.prefix + "water-sessions"

    /** 常用设备（按名字管理，P2 列表页用）。 */
    fun devices(): List<PgWaterDevice> = runCatching {
        val arr = JSONArray(sp.getString(keyDevices, "[]").orEmpty())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PgWaterDevice(
                goodsId = o.optString("goodsId"),
                sn = o.optString("sn"),
                deviceId = o.optString("deviceId"),
                imei = o.optString("imei"),
                name = o.optString("name"),
                orgName = o.optString("orgName"),
                categoryCode = o.optString("categoryCode"),
                categoryName = o.optString("categoryName"),
                routeShopId = o.optString("routeShopId"),
                skuId = o.optString("skuId"),
                skuName = o.optString("skuName"),
                supportAppUserFinish = o.optBoolean("supportAppUserFinish", true),
            )
        }
    }.getOrDefault(emptyList())

    fun saveDevice(device: PgWaterDevice) {
        val list = devices().filterNot { it.goodsId == device.goodsId } + device
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("goodsId", d.goodsId).put("sn", d.sn)
                    .put("deviceId", d.deviceId).put("imei", d.imei)
                    .put("name", d.name).put("orgName", d.orgName)
                    .put("categoryCode", d.categoryCode).put("categoryName", d.categoryName)
                    .put("routeShopId", d.routeShopId)
                    .put("skuId", d.skuId).put("skuName", d.skuName)
                    .put("supportAppUserFinish", d.supportAppUserFinish)
            )
        }
        sp.edit().putString(keyDevices, arr.toString()).commit()
    }

    fun sessions(): List<PgWaterSession> = runCatching {
        val arr = JSONArray(sp.getString(keySessions, "[]").orEmpty())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PgWaterSession(
                sessionId = o.optString("sessionId"),
                goodsId = o.optString("goodsId"),
                skuId = o.optString("skuId"),
                name = o.optString("name"),
                deviceId = o.optString("deviceId"),
                categoryCode = o.optString("categoryCode"),
                orderNo = o.optString("orderNo"),
                msgId = o.optString("msgId"),
                startedAt = o.optLong("startedAt", System.currentTimeMillis()),
            )
        }
    }.getOrDefault(emptyList())

    fun saveSessions(list: List<PgWaterSession>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("sessionId", s.sessionId).put("goodsId", s.goodsId)
                    .put("skuId", s.skuId).put("name", s.name)
                    .put("deviceId", s.deviceId).put("categoryCode", s.categoryCode)
                    .put("orderNo", s.orderNo).put("msgId", s.msgId)
                    .put("startedAt", s.startedAt)
            )
        }
        sp.edit().putString(keySessions, arr.toString()).commit()
    }
}
