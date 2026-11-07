package com.life798.mini.net

import android.content.Context
import android.os.Build
import com.life798.mini.BuildConfig
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 胖乖 `/user/info` 里本应用需要的两个字段。 */
data class PangguaiUser(val phone: String, val uid: String)

/**
 * 胖乖生活登录接口。只有三个：发码、登录、读用户信息。
 *
 * 实测结论（见 `pangguai/docs/03-登录流程完整技术复盘.md`）：
 * - 服务端**不校验** sign / timestamp / 重放，`sign` 填 64 个 0 即可；
 * - `Authorization` 头非必需，token 放在请求体里；
 * - 登录接口是 `POST /user/reg`（名字叫注册，实际是登录），字段名是 `verify`。
 *
 * 业务接口（扫码启动洗衣机）在 [PangguaiWasherApi]，复用这里的请求头与信封解析。
 */
class PangguaiApi(
    context: Context,
    private val base: String = BuildConfig.PANGGUAI_BASE,
) {

    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 设备指纹。随机 64 位 hex 并持久化：
     * 不依赖系统权限，也不会因为 Android ID 在部分机型上变化而触发风控。
     */
    private val deviceId: String by lazy {
        val sp = appContext.getSharedPreferences("mini_pangguai", Context.MODE_PRIVATE)
        val saved = sp.getString("pangguai-deviceId", null)
        if (!saved.isNullOrBlank()) {
            saved
        } else {
            val generated = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("pangguai-deviceId", generated).apply()
            generated
        }
    }

    val isConfigured: Boolean get() = base.isNotBlank()

    /** 发短信验证码。登录场景模板固定 `login`，不是 `reg`。 */
    suspend fun sendCode(phone: String): ApiResult<Unit> =
        when (val r = call("/common/sms/sendCode", form(
            "phone" to phone,
            "template" to "login",
        ))) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    /** 登录换 token。`channel` 必填，`verify` 是短信码的字段名。 */
    suspend fun login(phone: String, verify: String): ApiResult<String> =
        when (val r = call("/user/reg", form(
            "channel" to CHANNEL,
            "phone" to phone,
            "verify" to verify,
        ))) {
            is ApiResult.Ok -> {
                val token = r.data.optString("token")
                if (token.isBlank()) {
                    ApiResult.Err("登录返回缺少 token")
                } else {
                    ApiResult.Ok(token)
                }
            }
            is ApiResult.Err -> r
        }

    /** 校验 token 并读回手机号。 */
    suspend fun userInfo(token: String): ApiResult<PangguaiUser> =
        when (val r = call("/user/info", form("token" to token))) {
            is ApiResult.Ok -> {
                val phone = r.data.optString("phone")
                val uid = r.data.optString("id")
                if (phone.isBlank()) {
                    ApiResult.Err("用户信息缺少手机号")
                } else {
                    ApiResult.Ok(PangguaiUser(phone = phone, uid = uid))
                }
            }
            is ApiResult.Err -> r
        }

    // ---------------- 内部 ----------------

    /** 业务接口复用同一套请求头；[PangguaiWasherApi] 也走这里。 */
    internal fun form(vararg pairs: Pair<String, String>): FormBody =
        FormBody.Builder().apply {
            pairs.forEach { (k, v) -> add(k, v) }
        }.build()

    /**
     * 返回**完整响应信封** `{code,msg,data,t}`。
     *
     * 登录接口用 [call] 拿 `data` 对象就够；但 `/goods/normal/skus` 的 `data` 是数组，
     * 走 [call] 会被丢掉，所以业务接口需要这个原始版本。
     */
    internal suspend fun callRaw(path: String, body: FormBody): ApiResult<JSONObject> =
        execute(path, body, token = null)

    /** 只取 `data` 对象，登录接口用的便捷版本。 */
    internal suspend fun call(path: String, body: FormBody): ApiResult<JSONObject> =
        when (val r = callRaw(path, body)) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.optJSONObject("data") ?: JSONObject())
            is ApiResult.Err -> r
        }

    /**
     * 业务接口专用：按官方 `HeadInterceptor` 的算法生成 sign，并带 `Authorization`。
     *
     * 登录接口实测全 0 sign 就能过，但 `/goods/scan/v2` 这类业务接口会校验，
     * 失败时返回的却是「版本过低，请升级APP后再使用」这种误导性文案。
     * 所以登录走 [call]、业务走这里，两条链路互不干扰。
     */
    internal suspend fun callBusiness(
        path: String,
        body: FormBody,
        token: String,
    ): ApiResult<JSONObject> = execute(path, body, token)

    private suspend fun execute(
        path: String,
        body: FormBody,
        token: String?,
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        if (base.isBlank()) {
            return@withContext ApiResult.Err("未配置胖乖接口地址")
        }
        try {
            val ts = System.currentTimeMillis().toString()
            val builder = Request.Builder()
                .url(base.trim().trimEnd('/') + path)
                .header("Version", VERSION)
                .header("channel", CHANNEL)
                .header("phoneBrand", Build.MANUFACTURER.ifBlank { "Android" })
                .header("deviceId", deviceId)
                .header("timestamp", ts)
                .header("User-Agent", userAgent())
                .post(body)

            if (token == null) {
                // 登录链路：服务端不校验 sign
                builder.header("sign", "0".repeat(64))
            } else {
                // 业务链路：sign 里含 timestamp / token / version / path，必须和头一致
                builder.header("Authorization", token)
                builder.header(
                    "sign",
                    sha256Hex(
                        "appSecret=$SIGN_SECRET&channel=$CHANNEL&timestamp=$ts" +
                            "&token=$token&version=$VERSION&$path"
                    )
                )
            }

            client.newCall(builder.build()).execute().use { resp ->
                parseEnvelope(resp)
            }
        } catch (e: Exception) {
            AppLog.w("Pangguai", "请求失败 $path: ${e.message}")
            ApiResult.Err("网络异常：${e.message ?: "无法连接服务器"}")
        }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun parseEnvelope(resp: Response): ApiResult<JSONObject> {
        if (!resp.isSuccessful) {
            return ApiResult.Err("HTTP ${resp.code}")
        }
        val text = resp.body?.string().orEmpty()
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ApiResult.Err("响应格式异常")
        val code = obj.optInt("code", -1)
        if (code != 0) {
            val msg = obj.optString("msg").ifBlank { "请求失败（$code）" }
            AppLog.w("Pangguai", "业务失败 code=$code msg=$msg")
            return ApiResult.Err(msg, code)
        }
        return ApiResult.Ok(obj)
    }

    /**
     * 必须和官方 App 的 UA 完全一致：服务端会从 `version:` / `build:` 字段读客户端版本。
     * 抓包原文：`QEUser/1.145.0 (com.qiekj.user; build:299; Android 13; `
     * `userChannel:android_app; version:1.145.0) OkHttp/4.12.0`
     * 之前写成 `... Android 13; <机型>)`，少了 userChannel 和 version，
     * 是 `/goods/scan/v2` 返回「版本过低」的嫌疑之一。
     */
    private fun userAgent(): String =
        "QEUser/$VERSION (com.qiekj.user; build:299; Android ${Build.VERSION.RELEASE}; " +
            "userChannel:$CHANNEL; version:$VERSION) OkHttp/4.12.0"

    private companion object {
        const val CHANNEL = "android_app"

        /** 客户端版本。服务端升级后可能要求更高的版本号，集中在这里改。 */
        const val VERSION = "1.145.0"

        /**
         * 官方 `HeadInterceptor` 里的 appSecret（代码里 Base64 解码后的常量）。
         * 只用于拼 sign 字符串，不是账号凭据。
         */
        const val SIGN_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
    }
}
