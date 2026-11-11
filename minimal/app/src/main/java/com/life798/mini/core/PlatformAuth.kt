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
     * 校验 token，并把服务端认定的账号身份带回来。
     *
     * 登录是否真的成功，以「第二次请求拿到的号码对得上」为准，而不是只看 `code == 0`。
     *
     * **必须区分 [AccountInfo.uid] 和 [AccountInfo.phone]**：
     * 798 的 `/acc/view-info` 里 `id` 是 16 位十六进制的账号 id（形如
     * `13a0702bb1409000`），`pn` 才是手机号。曾经把 `id` 当手机号回填，
     * 结果账本 owner 从手机号变成 uid，升级后老账本一夜之间「消失」——
     * 数据还在，只是 key 变了。所以这里返回结构体，两个字段各归各位。
     */
    suspend fun verify(token: String): ApiResult<AccountInfo>
}

/**
 * 服务端认定的账号身份。
 *
 * [uid] 只用于展示/排查，[phone] 才是账本、常用设备这些本地数据的 owner。
 */
data class AccountInfo(
    val uid: String = "",
    val phone: String = "",
)

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
