package com.life798.mini.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全工程唯一的 DataStore 实例。
 *
 * **不要再为别的模块新建 `preferencesDataStore`** —— 同一个文件被两个委托
 * 打开会直接抛 `IllegalStateException`。胖乖的数据靠 key 前缀分区，复用这一个。
 */
internal val Context.store by preferencesDataStore(name = "mini_prefs")

/**
 * 惠生活798 的登录态门面。
 *
 * 内部已经委托给 [PlatformSessionStore]，key 由 `Platform.HUI798.prefix`（空串）
 * 生成，所以磁盘上仍然是历史的那三个 key：`token` / `uid` / `phone`。
 * 保留这个类只是为了不动既有调用方；新代码请直接用 [PlatformSessionStore]。
 */
class Prefs(context: Context) {

    private val store = PlatformSessionStore(context)

    val token: Flow<String> = store.flow(Platform.HUI798).map { it.token }
    val phone: Flow<String> = store.flow(Platform.HUI798).map { it.phone }

    suspend fun saveSession(token: String, uid: String, phone: String) {
        store.save(Platform.HUI798, token, uid, phone)
    }

    suspend fun clearSession() {
        store.clear(Platform.HUI798)
    }
}
