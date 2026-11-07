package com.life798.mini.core

/**
 * 两个登录平台。
 *
 * [prefix] 是这个平台所有落盘 key 的命名空间：
 * - 798 用空串 —— 历史数据（`token` / `bills_<phone>` / `favorites_<phone>`）
 *   原封不动，升级后老用户直接就是已登录状态，零迁移。
 * - 胖乖用 `pangguai-` —— 全新数据，加前缀零成本，且与 798 物理隔离。
 *
 * 两个平台可以用同一个手机号登录，隔离靠前缀，不靠手机号。
 */
enum class Platform(
    val id: String,
    val prefix: String,
    val label: String,
    /** 登录时要不要图形验证码。UI 靠这个字段决定渲染，不写 if (platform == ...)。 */
    val needsGraphCaptcha: Boolean,
) {
    HUI798("hui798", "", "惠生活798", true),
    PANGGUAI("pangguai", "pangguai-", "胖乖生活", false);

    companion object {
        fun fromId(id: String?): Platform = entries.firstOrNull { it.id == id } ?: HUI798
    }
}

/** 一个平台的登录态。 */
enum class SessionState {
    /** 本地没有任何凭证。 */
    EMPTY,

    /** 有 token，且最近一次校验通过（或还没被证伪）。 */
    ACTIVE,

    /** 服务端明确说凭证失效（798: -99/-21，胖乖: 2）。 */
    EXPIRED,
}

/**
 * 一个平台的会话。
 *
 * [ownerKey] 是数据层的隔离键：798 是纯手机号，胖乖是 `pangguai-手机号`。
 * 账单、常用设备、用水会话都用它做 key 后缀。
 */
data class PlatformSession(
    val platform: Platform,
    val token: String = "",
    val uid: String = "",
    val phone: String = "",
    val state: SessionState = SessionState.EMPTY,
) {
    val isActive: Boolean get() = state == SessionState.ACTIVE && token.isNotBlank()
    val ownerKey: String get() = platform.prefix + phone
}

/**
 * 登录表单。按平台各存一份，切 Tab 不会把另一个平台填到一半的内容冲掉。
 *
 * 全部放在 ViewModel 里而不是 Composable 的 `rememberSaveable`：
 * 这样切换平台、旋转屏幕、进程重建后都能原样恢复。
 */
data class LoginForm(
    val platform: Platform,
    val phone: String = "",
    val graphCode: String = "",
    val smsCode: String = "",
    val captchaKey: String = "",
    val captchaImage: ByteArray? = null,
    val captchaLoading: Boolean = false,
    val captchaError: String? = null,
    /** 短信倒计时秒数，两个平台各自独立。 */
    val countdown: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
