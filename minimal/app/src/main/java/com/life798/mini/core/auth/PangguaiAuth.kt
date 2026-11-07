package com.life798.mini.core.auth

import com.life798.mini.core.ApiResult
import com.life798.mini.core.Platform
import com.life798.mini.core.PlatformAuth
import com.life798.mini.core.RawSession
import com.life798.mini.net.PangguaiApi

/**
 * 胖乖生活的登录能力。
 *
 * 两条链路和 798 完全不同：没有图形验证码、不需要签名、域名与字段名都不一样，
 * 所以这里是完整实现，而不是去继承 798 的适配器。
 */
class PangguaiAuth(
    private val api: PangguaiApi,
) : PlatformAuth {

    override val platform: Platform = Platform.PANGGUAI

    override suspend fun captcha(key: String): ApiResult<ByteArray> =
        ApiResult.Err("胖乖登录不需要图形验证码")

    override suspend fun sendSms(
        phone: String,
        graphCode: String,
        captchaKey: String,
    ): ApiResult<Unit> = api.sendCode(phone)

    /**
     * 登录后立刻追加一次 `/user/info`：
     * 「登录成功」这个结论由第二次独立请求的手机号佐证，而不是靠 `code == 0` 猜。
     */
    override suspend fun login(phone: String, smsCode: String): ApiResult<RawSession> =
        when (val login = api.login(phone, smsCode)) {
            is ApiResult.Ok -> when (val info = api.userInfo(login.data)) {
                is ApiResult.Ok -> ApiResult.Ok(
                    RawSession(
                        token = login.data,
                        phone = info.data.phone,
                        uid = info.data.uid,
                    )
                )

                is ApiResult.Err -> info
            }

            is ApiResult.Err -> login
        }

    override suspend fun verify(token: String): ApiResult<String> =
        when (val r = api.userInfo(token)) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.phone)
            is ApiResult.Err -> r
        }
}
