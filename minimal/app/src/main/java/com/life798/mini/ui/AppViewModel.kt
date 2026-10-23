package com.life798.mini.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import com.life798.mini.core.Prefs
import com.life798.mini.net.WaterApi
import com.life798.mini.scan.QrParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

data class UiState(
    val ready: Boolean = false,
    val token: String = "",
    val phone: String = "",
    val deviceId: String = "",
    val captchaKey: String = "",
    val captchaImage: ByteArray? = null,
    val captchaLoading: Boolean = false,
    val captchaError: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val elapsedSec: Long = 0L,
    val smsCountdown: Int = 0,
    val configured: Boolean = true
) {
    val loggedIn: Boolean get() = token.isNotBlank()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val api = WaterApi()

    private val _ui = MutableStateFlow(UiState(configured = api.isConfigured))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var ticker: Job? = null
    private var smsTimer: Job? = null

    init {
        viewModelScope.launch {
            val token = prefs.token.first()
            val phone = prefs.phone.first()
            val device = prefs.deviceId.first()
            _ui.value = _ui.value.copy(
                ready = true,
                token = token,
                phone = phone,
                deviceId = device
            )
            // 未登录才需要图形验证码；已登录直接进首页，不必白跑一次请求。
            if (token.isBlank()) {
                refreshCaptcha()
            }
        }
    }

    // ---------------- 登录 ----------------

    fun refreshCaptcha() {
        if (!api.isConfigured) {
            _ui.value = _ui.value.copy(
                captchaLoading = false,
                captchaImage = null,
                captchaError = "未配置 API 网关"
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(captchaLoading = true, captchaError = null)
            val key = randomKey()
            when (val r = api.captcha(key)) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "验证码已加载 ${r.data.size}B")
                    _ui.value = _ui.value.copy(
                        captchaLoading = false,
                        captchaKey = key,
                        captchaImage = r.data,
                        captchaError = null
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "验证码加载失败: ${r.message}")
                    _ui.value = _ui.value.copy(
                        captchaLoading = false,
                        captchaImage = null,
                        captchaError = r.message
                    )
                }
            }
        }
    }

    fun sendSms(phone: String, graphCode: String) {
        if (phone.isBlank()) return note("请输入手机号")
        if (graphCode.isBlank()) return note("请输入图形验证码")
        if (_ui.value.smsCountdown > 0) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.sendSms(phone.trim(), graphCode.trim(), _ui.value.captchaKey)) {
                is ApiResult.Ok -> {
                    AppLog.i("Login", "短信已发送 → ${AppLog.maskPhone(phone)}")
                    _ui.value = _ui.value.copy(busy = false, message = "验证码已发送")
                    startSmsCountdown()
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "发送短信失败: ${r.message}")
                    _ui.value = _ui.value.copy(busy = false, message = r.message)
                    refreshCaptcha()
                }
            }
        }
    }

    fun login(phone: String, smsCode: String) {
        if (phone.isBlank()) return note("请输入手机号")
        if (smsCode.isBlank()) return note("请输入短信验证码")

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.login(phone.trim(), smsCode.trim())) {
                is ApiResult.Ok -> {
                    val session = r.data
                    val uid = when (val info = api.viewInfo(session.token)) {
                        is ApiResult.Ok -> info.data
                        is ApiResult.Err -> {
                            AppLog.w("Login", "取 uid 失败: ${info.message}")
                            ""
                        }
                    }
                    prefs.saveSession(session.token, uid, session.phone)
                    AppLog.i(
                        "Login",
                        "登录成功 uid=$uid token=${AppLog.mask(session.token)}"
                    )
                    _ui.value = _ui.value.copy(
                        busy = false,
                        token = session.token,
                        phone = session.phone,
                        message = null
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Login", "登录失败: ${r.message} (code=${r.code})")
                    _ui.value = _ui.value.copy(busy = false, message = r.message)
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            prefs.clearSession()
            stopTicker()
            _ui.value = _ui.value.copy(token = "", running = false, startedAt = 0L, elapsedSec = 0L)
            refreshCaptcha()
        }
    }

    // ---------------- 设备 ----------------

    /**
     * 扫码结果处理。
     * @return true 表示已解析出设备编号（调用方可以离开扫码页）
     */
    fun onQrDecoded(raw: String): Boolean {
        AppLog.i("Scan", "二维码原文: ${raw.take(120)}")
        val id = QrParser.extract(raw)
        if (id == null) {
            AppLog.w("Scan", "解析失败，原文长度=${raw.length}")
            note("无法识别为设备编号：${raw.take(40)}")
            return false
        }
        AppLog.i("Scan", "设备编号=$id")
        viewModelScope.launch {
            prefs.saveDevice(id)
            _ui.value = _ui.value.copy(deviceId = id, message = "已识别设备 $id")
        }
        return true
    }

    fun startDevice() {
        val s = _ui.value
        if (s.deviceId.isBlank()) return note("请先扫码添加设备")
        if (s.running) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.devStart(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    AppLog.i("Device", "启动成功 did=${s.deviceId}")
                    _ui.value = _ui.value.copy(
                        busy = false,
                        running = true,
                        startedAt = System.currentTimeMillis(),
                        elapsedSec = 0L,
                        message = "设备已启动"
                    )
                    startTicker()
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "启动失败 did=${s.deviceId}: ${r.message} code=${r.code}")
                    _ui.value = _ui.value.copy(busy = false, message = r.message)
                    if (r.code == -99) logout()
                }
            }
        }
    }

    fun stopDevice() {
        val s = _ui.value
        if (!s.running) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val r = api.devEnd(s.token, s.deviceId)) {
                is ApiResult.Ok -> {
                    AppLog.i("Device", "停止成功 did=${s.deviceId}")
                    stopTicker()
                    _ui.value = _ui.value.copy(
                        busy = false,
                        running = false,
                        startedAt = 0L,
                        elapsedSec = 0L,
                        message = "设备已停止"
                    )
                }

                is ApiResult.Err -> {
                    AppLog.w("Device", "停止失败 did=${s.deviceId}: ${r.message} code=${r.code}")
                    _ui.value = _ui.value.copy(busy = false, message = r.message)
                    if (r.code == -99) logout()
                }
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // ---------------- 内部 ----------------

    private fun startTicker() {
        stopTicker()
        ticker = viewModelScope.launch {
            while (true) {
                val started = _ui.value.startedAt
                if (started <= 0L) break
                val sec = (System.currentTimeMillis() - started) / 1000
                _ui.value = _ui.value.copy(elapsedSec = sec)
                delay(1000)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun startSmsCountdown() {
        smsTimer?.cancel()
        smsTimer = viewModelScope.launch {
            for (i in 60 downTo 1) {
                _ui.value = _ui.value.copy(smsCountdown = i)
                delay(1000)
            }
            _ui.value = _ui.value.copy(smsCountdown = 0)
        }
    }

    private fun note(msg: String) {
        _ui.value = _ui.value.copy(message = msg)
    }

    private fun randomKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(10) {
            repeat(10) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTicker()
        smsTimer?.cancel()
    }
}
