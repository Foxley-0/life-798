package com.life798.mini.net

import com.life798.mini.BuildConfig
import com.life798.mini.core.ApiResult
import com.life798.mini.core.AppLog
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

    /** 图形验证码：返回图片字节。失败时给出可读原因，便于界面上直接展示。 */
    suspend fun captcha(key: String): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        if (base.isBlank()) {
            return@withContext ApiResult.Err("未配置 API 网关")
        }
        val url = "$base/captcha/?s=$key&r=${System.currentTimeMillis()}"
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use ApiResult.Err("HTTP ${resp.code}")
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    return@use ApiResult.Err("响应为空")
                }
                if (!looksLikeImage(bytes)) {
                    // 服务端有时会返回 JSON 错误体，把前 120 字符带出来便于定位
                    val text = String(bytes, Charsets.UTF_8).take(120)
                    return@use ApiResult.Err("返回的不是图片：$text")
                }
                ApiResult.Ok(bytes)
            }
        } catch (e: Exception) {
            ApiResult.Err("网络异常：${e.message ?: "无法连接服务器"}")
        }
    }

    /** 粗略判断字节流是不是图片（JPEG / PNG / GIF / WEBP）。 */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val jpeg = b0 == 0xFF && b1 == 0xD8
        val png = b0 == 0x89 && b1 == 0x50
        val gif = b0 == 0x47 && b1 == 0x49
        val webp = b.size > 12 && b0 == 0x52 && b1 == 0x49
        return jpeg || png || gif || webp
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
        val query = req.url.query
        val target = req.url.encodedPath + if (query.isNullOrBlank()) "" else "?$query"
        AppLog.d("Api", "${req.method} $target")

        val response: Response = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            AppLog.e("Api", "请求失败 $target", e)
            return ApiResult.Err("网络异常：${e.message ?: "无法连接服务器"}")
        }

        return response.use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLog.d("Api", "← ${resp.code} ${AppLog.preview(text, 300)}")

            if (text.isBlank()) {
                AppLog.w("Api", "响应为空 HTTP ${resp.code}")
                return@use ApiResult.Err("服务端返回为空（HTTP ${resp.code}）")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                AppLog.e("Api", "JSON 解析失败", e)
                return@use ApiResult.Err("响应解析失败")
            }
            val code = json.optInt("code", Int.MIN_VALUE)
            if (code != 0) {
                val raw = json.optString("msg")
                // 业务码与上游客户端保持一致，直接给出可读原因，别把 code 甩给用户
                val msg = when (code) {
                    -99 -> "登录已过期，请重新登录"
                    -21 -> "需要设备控制登录信息，请重新登录"
                    -88 -> "未签约代扣协议，请先在服务端完成签约"
                    -87 -> "签约已过期，请重新签约"
                    -52 -> "账户欠费，请充值后使用"
                    -20 -> "未绑定一卡通账号，请先完成账户绑定"
                    -19 -> "设备准备中，请稍后"
                    -82 -> "需要支付"
                    else -> raw.ifBlank { "请求失败（code=$code）" }
                }
                AppLog.w("Api", "业务失败 code=$code msg=$raw")
                return@use ApiResult.Err(msg, code)
            }
            try {
                ApiResult.Ok(transform(json))
            } catch (e: Exception) {
                AppLog.e("Api", "数据解析异常", e)
                ApiResult.Err(e.message ?: "数据异常")
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
