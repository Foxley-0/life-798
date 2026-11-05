package com.life798.mini.core

import android.content.Context
import android.util.Log
import com.life798.mini.BuildConfig

/**
 * 日志。
 *
 * 只打 logcat，不写文件、没有应用内日志页 —— 那些会带来存储和隐私成本。
 * release 包完全静默（[BuildConfig.DEBUG] 为 false 时直接返回），
 * debug 包全量输出，排查相机释放、接口失败这些全靠它。
 *
 * tag 统一加 `798/` 前缀，`adb logcat -s 798/Scan` 就能只看扫码这一路。
 */
object AppLog {

    private const val PREFIX = "798/"

    fun init(context: Context) = Unit

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(PREFIX + tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(PREFIX + tag, msg, t)
    }

    /** token 只保留首 6 位与尾 4 位。 */
    fun mask(token: String?): String {
        if (token.isNullOrBlank()) return "(空)"
        return if (token.length <= 12) "***" else "${token.take(6)}…${token.takeLast(4)}"
    }

    /** 手机号中间四位打码。 */
    fun maskPhone(phone: String?): String {
        if (phone.isNullOrBlank()) return "(空)"
        return if (phone.length >= 11) phone.replaceRange(3, 7, "****") else "***"
    }

    /** 响应体只留前若干字符。 */
    fun preview(text: String?, limit: Int = 200): String {
        if (text.isNullOrBlank()) return "(空)"
        return if (text.length <= limit) text else text.take(limit) + "…(${text.length} chars)"
    }
}
