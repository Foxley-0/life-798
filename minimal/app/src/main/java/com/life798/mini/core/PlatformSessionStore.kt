package com.life798.mini.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 两个平台的登录态存取。
 *
 * 所有 key 都由 `平台前缀 + 基础名` 生成，全工程只在这里拼字符串：
 * - 798：`token` / `uid` / `phone`
 * - 胖乖：`pangguai-token` / `pangguai-uid` / `pangguai-phone`
 *
 * 三个 key 在**同一个 `edit` 事务**里写入。DataStore 的 edit 是原子的，
 * 不会出现「有 token 没 phone」的中间态 —— phone 是账本的 owner，
 * 它为空会让账单和设备仓库整体短路。
 */
class PlatformSessionStore(context: Context) {

    private val appContext = context.applicationContext

    fun flow(platform: Platform): Flow<PlatformSession> =
        appContext.store.data.map { it.read(platform) }

    /** 冷启动时一次读完两个平台，避免同一个 Flow 被反复 first()。 */
    suspend fun loadAll(): Map<Platform, PlatformSession> {
        val prefs = appContext.store.data.first()
        return Platform.entries.associateWith { prefs.read(it) }
    }

    suspend fun save(platform: Platform, token: String, uid: String, phone: String) {
        appContext.store.edit { prefs ->
            prefs[key(platform, "token")] = token
            prefs[key(platform, "uid")] = uid
            prefs[key(platform, "phone")] = phone
        }
    }

    /** 只清这一个平台。另一个平台的数据一个字节都不碰。 */
    suspend fun clear(platform: Platform) {
        appContext.store.edit { prefs ->
            prefs.remove(key(platform, "token"))
            prefs.remove(key(platform, "uid"))
            prefs.remove(key(platform, "phone"))
        }
    }

    private fun key(platform: Platform, base: String) =
        stringPreferencesKey("${platform.prefix}$base")

    private fun Preferences.read(platform: Platform): PlatformSession {
        val token = this[key(platform, "token")].orEmpty()
        val phone = this[key(platform, "phone")].orEmpty()
        val uid = this[key(platform, "uid")].orEmpty()
        return PlatformSession(
            platform = platform,
            token = token,
            uid = uid,
            phone = phone,
            // 磁盘上有 token 就先当 ACTIVE；冷启动校验失败会把它改成 EXPIRED。
            state = if (token.isBlank()) SessionState.EMPTY else SessionState.ACTIVE,
        )
    }
}
