package com.life798.mini.core

import android.content.Context

/**
 * 《使用须知》的同意状态。
 *
 * 只存一份指纹。文案没变就一直有效；[UsageNotice.hash] 一变，[accepted] 自动为假，
 * 老用户下次登录会被要求重读 —— 不区分「老用户豁免」这种事。
 *
 * 放 `mini_consent`，跟项目里 `mini_*` 的命名保持一致。
 */
class UsageConsent(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前这份文案同意过没有。 */
    val accepted: Boolean
        get() = sp.getString(KEY_HASH, null) == UsageNotice.hash

    /**
     * 同意过，但同意的是旧文案。
     *
     * 用来决定要不要在登录页顶一行「已更新」的提示 —— 首次使用不该看到这句，
     * 只有真的读过旧版的人才需要被告知。
     */
    val outdated: Boolean
        get() {
            val recorded = sp.getString(KEY_HASH, null) ?: return false
            return recorded != UsageNotice.hash
        }

    /** 同意时间，仅供排查用；没同意过返回 0。 */
    val acceptedAt: Long
        get() = sp.getLong(KEY_AT, 0L)

    /**
     * 记录同意。
     *
     * 用 `commit()` 而不是 `apply()`：返回值要能反映是否真的落盘。
     * 同意与否决定能不能登录，写失败却当作成功，用户下次又被拦一遍。
     */
    fun accept(): Boolean = sp.edit()
        .putString(KEY_HASH, UsageNotice.hash)
        .putLong(KEY_AT, System.currentTimeMillis())
        .commit()

    /** 撤销同意（用户取消勾选）。 */
    fun revoke() {
        sp.edit().remove(KEY_HASH).remove(KEY_AT).apply()
    }

    companion object {
        private const val PREFS = "mini_consent"
        private const val KEY_HASH = "accepted_hash"
        private const val KEY_AT = "accepted_at"
    }
}
