package com.life798.mini.net

import com.life798.mini.BuildConfig
import com.life798.mini.core.ApiResult
import com.life798.mini.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 只封装本应用需要的六个接口。
 *
 * 设备控制端的请求头固定为 ApplicationType=1,1 / VersionCode=3.1.9，
 * 与官方客户端保持一致，否则服务端会拒绝。
 */
class WaterApi(
    gateway: String = BuildConfig.API_GATEWAY,
    private val salt: String = BuildConfig.APP_SIGN_SALT
) {

    private val base: String = gateway.trim().trimEnd('/')

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 未注入网关时视为未配置，UI 上应给出提示。 */
    val isConfigured: Boolean get() = base.isNotBlank()

    // ---------- 登录链路 ----------

    /** 图形验证码：返回图片字节，失败返回 null。 */
    suspend fun captcha(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = "$base/captcha/?s=$key&r=${System.currentTimeMillis()}"
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** 发送短信验证码。此接口不需要签名。 */
    suspend fun sendSms(phone: String, graphCode: String, key: String): ApiResult<Unit> = io {
        val body = JSONObject()
            .put("un", phone)
            .put("authCode", graphCode)
            .put("s", key)
            .toString()
        val req = builder("$base/acc/login/code")
            .post(body.toRequestBody(jsonMedia))
            .build()
        call(req) { Unit }
    }

    /** 登录。签名是唯一需要盐值的一步。 */
    suspend fun login(phone: String, smsCode: String): ApiResult<Session> = io {
        val sign = Signer.loginSign(phone, smsCode, salt)
        val body = JSONObject()
            .put("authCode", smsCode)
            .put("un", phone)
            .toString()
        val req = builder("$base/acc/login?sign=$sign")
            .post(body.toRequestBody(jsonMedia))
            .build()
        call(req) { json ->
            val token = json.optJSONObject("data")
                ?.optJSONObject("al")
                ?.optString("token")
                .orEmpty()
            check(token.isNotBlank()) { "登录未返回 token" }
            Session(token = token, uid = "", phone = phone)
        }
    }

    /** 取账户信息，用于补全 uid。 */
    suspend fun viewInfo(token: String): ApiResult<String> = io {
        val req = builder("$base/acc/view-info")
            .header("Authorization", token)
            .get()
            .build()
        call(req) { json ->
            json.optJSONObject("data")?.optString("id").orEmpty()
        }
    }

    // ---------- 设备控制 ----------

    suspend fun devStart(token: String, deviceId: String): ApiResult<Unit> = io {
        val url = "$base/dev/start?did=${encode(deviceId)}&upgrade=true&ptype=91&rcp=false"
        val req = builder(url).header("Authorization", token).get().build()
        call(req) { Unit }
    }

    suspend fun devEnd(token: String, deviceId: String): ApiResult<Unit> = io {
        val url = "$base/dev/end?did=${encode(deviceId)}&rcp=false"
        val req = builder(url).header("Authorization", token).get().build()
        call(req) { Unit }
    }

    // ---------- 内部工具 ----------

    private fun builder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("ApplicationType", "1,1")
        .header("VersionCode", "3.1.9")
        .header("User-Agent", "Android_ilife798_3.1.9")

    private suspend fun <T> io(block: () -> ApiResult<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }
                .getOrElse { ApiResult.Err(it.message ?: "网络请求失败") }
        }

    private fun <T> call(req: Request, transform: (JSONObject) -> T): ApiResult<T> {
        val response: Response = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            return ApiResult.Err("网络异常：${e.message ?: "无法连接服务器"}")
        }

        return response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) {
                return@use ApiResult.Err("服务端返回为空（HTTP ${resp.code}）")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return@use ApiResult.Err("响应解析失败")
            }
            val code = json.optInt("code", Int.MIN_VALUE)
            if (code != 0) {
                val raw = json.optString("msg")
                val msg = when {
                    code == -99 -> "登录已过期，请重新登录"
                    raw.isNotBlank() -> raw
                    else -> "请求失败（code=$code）"
                }
                return@use ApiResult.Err(msg, code)
            }
            try {
                ApiResult.Ok(transform(json))
            } catch (e: Exception) {
                ApiResult.Err(e.message ?: "数据异常")
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
