package com.water.widget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

data class AppUpdateUiState(
    val policy: AutoUpdateCheck = AutoUpdateCheck.DAILY,
    val checking: Boolean = false,
    val status: String = "当前版本 v${BuildConfig.VERSION_NAME}",
    val available: AppRelease? = null,
    val message: String? = null
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("app_updates", 0)
    private val mutableState = MutableStateFlow(AppUpdateUiState(
        policy = AutoUpdateCheck.entries.firstOrNull { it.name == preferences.getString("policy", null) }
            ?: AutoUpdateCheck.DAILY
    ))
    val state = mutableState.asStateFlow()

    fun setPolicy(policy: AutoUpdateCheck) {
        preferences.edit().putString("policy", policy.name).apply()
        mutableState.value = mutableState.value.copy(policy = policy)
    }

    fun checkAutomatically() {
        if (state.value.policy.isDue(System.currentTimeMillis(), preferences.getLong("last_attempt", 0L))) check(false)
    }

    fun check(manual: Boolean = true) {
        if (state.value.checking) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        mutableState.value = state.value.copy(checking = true, status = "正在检查更新…", message = null)
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { AppUpdateClient.latest() }
                val newer = release.isNewerThan(BuildConfig.VERSION_NAME)
                mutableState.value = state.value.copy(
                    checking = false,
                    status = if (newer) "发现新版本 v${release.version}" else "已是最新正式版",
                    available = release.takeIf { newer },
                    message = if (manual && !newer) "当前已是最新正式版（v${BuildConfig.VERSION_NAME}）" else null
                )
            } catch (error: IOException) {
                showFailure(error.message ?: "网络连接失败，请稍后重试", manual)
            } catch (error: JSONException) {
                showFailure("更新信息格式异常，请稍后重试", manual)
            }
        }
    }

    private fun showFailure(message: String, manual: Boolean) {
        mutableState.value = state.value.copy(checking = false, status = "检查失败，可点击重试", message = message.takeIf { manual })
    }

    fun dismissDialog() {
        mutableState.value = state.value.copy(available = null, message = null)
    }
}
