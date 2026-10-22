package com.water.widget

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceSyncTest {
    @Before
    fun clearTestAccounts() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("accounts_cfg", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("migrated_v3", true).commit()
    }

    @Test
    fun delayedSyncUpdatesItsOwnAccountWithoutSwitchingBack() = withHome { activity ->
        AccountStore.addOrUpdate(activity, Account("account-a"))
        val request = generation(activity)
        AccountStore.addOrUpdate(activity, Account("account-b"))

        deliver(activity, request, "account-a", "device-a" to "饮水机", "device-extra" to "另一台设备")

        assertEquals("account-b", AccountStore.getCurrent(activity).phone)
        assertEquals("device-a", AccountStore.get(activity, "account-a").selectedDeviceId())
        assertTrue(AccountStore.get(activity, "account-b").rememberedDevices().isEmpty())
    }

    @Test
    fun delayedSyncPreservesNewAliasDefaultAndLocallyAddedDevice() = withHome { activity ->
        AccountStore.addOrUpdate(activity, Account("account-a").apply {
            selectDevice("device-b")
            selectDevice("device-a")
        })
        val request = generation(activity)
        ConfigActivity::class.java.getDeclaredMethod("editDevice", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, "device-a", "新名称")
        ConfigActivity::class.java.getDeclaredMethod("selectControlCenterDevice", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "device-b")
        val latest = AccountStore.getCurrent(activity).apply { rememberDevice("device-local") }
        AccountStore.updateCurrent(activity, latest)

        deliver(activity, request, "account-a", "device-a" to "旧名称")

        val saved = AccountStore.get(activity, "account-a")
        assertEquals("新名称", saved.deviceDisplayName("device-a"))
        assertEquals("device-b", saved.selectedDeviceId())
        assertTrue(saved.rememberedDevices().contains("device-local"))
    }

    @Test
    fun removalInvalidatesAnAlreadyPendingSync() = withHome { activity ->
        AccountStore.addOrUpdate(activity, Account("account-a").apply {
            selectDevice("device-valid")
            selectDevice("wrong-device")
        })
        val request = generation(activity)
        // 测试账户不含凭据，移除的服务器同步会在发 HTTP 请求前返回“未登录”。
        ConfigActivity::class.java.getDeclaredMethod("removeDevice", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "wrong-device")

        deliver(activity, request, "account-a", "wrong-device" to "旧设备")

        val saved = AccountStore.get(activity, "account-a")
        assertFalse(saved.rememberedDevices().contains("wrong-device"))
        assertEquals("device-valid", saved.selectedDeviceId())
    }

    @Test
    fun olderResponseCannotOverwriteANewerSync() = withHome { activity ->
        AccountStore.addOrUpdate(activity, Account("account-a"))
        val oldRequest = generation(activity)
        ConfigActivity::class.java.getDeclaredMethod("invalidateDeviceSync")
            .apply { isAccessible = true }.invoke(activity)
        deliver(activity, generation(activity), "account-a", "device-a" to "最新名称")

        deliver(activity, oldRequest, "account-a", "device-a" to "过时名称")

        assertEquals("最新名称", AccountStore.get(activity, "account-a").deviceDisplayName("device-a"))
    }

    // 启动时无账号，后续仅注入无凭据账户与模拟响应，不访问业务接口。
    private fun withHome(check: (ConfigActivity) -> Unit) {
        ActivityScenario.launch(ConfigActivity::class.java).use { scenario ->
            scenario.onActivity { check(it) }
        }
    }

    private fun generation(activity: ConfigActivity): Int = ConfigActivity::class.java
        .getDeclaredField("deviceSyncGeneration").apply { isAccessible = true }.getInt(activity)

    private fun deliver(activity: ConfigActivity, generation: Int, phone: String, vararg devices: Pair<String, String>) {
        val favorites = JSONArray()
        devices.forEach { (id, name) -> favorites.put(JSONObject().put("id", id).put("name", name)) }
        val response = JSONObject().put("code", 0).put("data", JSONObject().put("favos", favorites))
        ConfigActivity::class.java.getDeclaredMethod(
            "applyDeviceSyncResult", Int::class.javaPrimitiveType, String::class.java,
            JSONObject::class.java, String::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(activity, generation, phone, response, null, false)
    }
}
