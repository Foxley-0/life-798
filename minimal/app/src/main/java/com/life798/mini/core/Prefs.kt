package com.life798.mini.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "mini_prefs")

/**
 * 只存四样东西：token、uid、手机号、最近使用的设备。
 * 没有数据库、没有多账户、没有历史记录。
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val keyToken = stringPreferencesKey("token")
    private val keyUid = stringPreferencesKey("uid")
    private val keyPhone = stringPreferencesKey("phone")
    private val keyDevice = stringPreferencesKey("device_id")

    val token: Flow<String> = appContext.store.data.map { it[keyToken].orEmpty() }
    val phone: Flow<String> = appContext.store.data.map { it[keyPhone].orEmpty() }
    val deviceId: Flow<String> = appContext.store.data.map { it[keyDevice].orEmpty() }

    suspend fun saveSession(token: String, uid: String, phone: String) {
        appContext.store.edit {
            it[keyToken] = token
            it[keyUid] = uid
            it[keyPhone] = phone
        }
    }

    suspend fun saveDevice(id: String) {
        appContext.store.edit { it[keyDevice] = id }
    }

    suspend fun clearSession() {
        appContext.store.edit {
            it.remove(keyToken)
            it.remove(keyUid)
            it.remove(keyPhone)
        }
    }
}
