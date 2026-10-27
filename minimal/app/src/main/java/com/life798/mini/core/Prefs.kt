package com.life798.mini.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "mini_prefs")

/**
 * 只存三样东西：token、uid、手机号。
 *
 * 设备编号故意不落盘 —— 每次启动 App 都必须重新扫码，避免误用上一台设备。
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val keyToken = stringPreferencesKey("token")
    private val keyUid = stringPreferencesKey("uid")
    private val keyPhone = stringPreferencesKey("phone")

    val token: Flow<String> = appContext.store.data.map { it[keyToken].orEmpty() }
    val phone: Flow<String> = appContext.store.data.map { it[keyPhone].orEmpty() }

    suspend fun saveSession(token: String, uid: String, phone: String) {
        appContext.store.edit {
            it[keyToken] = token
            it[keyUid] = uid
            it[keyPhone] = phone
        }
    }

    suspend fun clearSession() {
        appContext.store.edit {
            it.remove(keyToken)
            it.remove(keyUid)
            it.remove(keyPhone)
        }
    }
}
