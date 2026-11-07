package com.life798.mini.core

/**
 * 一个平台的登录能力。
 *
 * 刻意不做成抽象基类：两条链路除了「手机号 + 短信码」两个入参，
 * 没有任何共同步骤 —— 798 要图形验证码、要 salt 签名；胖乖不要验证码、
 * sign 填全 0、字段名是 verify。共同点抽出来只会得到一个空壳。
 */
interface PlatformAuth {

    val platform: Platform

    /** 图形验证码图片。不需要图形码的平台返回错误即可，UI 不会调用。 */
    suspend fun captcha(key: String): ApiResult<ByteArray>

    /**
     * 发短信验证码。
     *
     * @param graphCode 图形验证码，胖乖平台忽略
     * @param captchaKey 图形验证码的 key，胖乖平台忽略
     */
    suspend fun sendSms(phone: String, graphCode: String, captchaKey: String): ApiResult<Unit>

    suspend fun login(phone: String, smsCode: String): ApiResult<RawSession>

    /**
     * 校验 token。
     *
     * 成功时返回服务端的手机号：登录是否真的成功，以「第二次请求拿到的号码对得上」
     * 为准，而不是只看 `code == 0`。
     */
    suspend fun verify(token: String): ApiResult<String>
}

/** 登录成功后、落盘前的中转结构。 */
data class RawSession(
    val token: String,
    val phone: String,
    val uid: String,
)

/**
 * 各平台的「登录态失效」错误码。
 *
 * 798：-99 整体失效；-21 设备控制侧的凭证失效，同样只能重登。
 * 胖乖：2 未登录（token 缺失/无效/过期）。
 */
fun isAuthError(platform: Platform, code: Int): Boolean = when (platform) {
    Platform.HUI798 -> code == -99 || code == -21
    Platform.PANGGUAI -> code == 2
}
