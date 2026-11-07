package com.life798.mini.core.auth

import com.life798.mini.core.ApiResult
import com.life798.mini.core.Platform
import com.life798.mini.core.PlatformAuth
import com.life798.mini.core.RawSession
import com.life798.mini.net.WaterApi

/**
 * 惠生活798 的登录能力，只是对现有 [WaterApi] 的一层薄适配。
 *
 * 业务逻辑（验证码、短信、salt 签名、网关）一行不改，
 * 这样这次改造对 798 主流程的影响被压到最小。
 */
class Hui798Auth(
    private val api: WaterApi = WaterApi(),
) : PlatformAuth {

    override val platform: Platform = Platform.HUI798

    override suspend fun captcha(key: String): ApiResult<ByteArray> = api.captcha(key)

    override suspend fun sendSms(
        phone: String,
        graphCode: String,
        captchaKey: String,
    ): ApiResult<Unit> = api.sendSms(phone, graphCode, captchaKey)

    override suspend fun login(phone: String, smsCode: String): ApiResult<RawSession> =
        when (val r = api.login(phone, smsCode)) {
            is ApiResult.Ok -> ApiResult.Ok(
                RawSession(
                    token = r.data.token,
                    phone = r.data.phone,
                    uid = r.data.uid,
                )
            )

            is ApiResult.Err -> r
        }

    override suspend fun verify(token: String): ApiResult<String> = api.viewInfo(token)
}
