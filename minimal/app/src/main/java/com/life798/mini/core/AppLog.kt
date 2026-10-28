package com.life798.mini.core

import android.content.Context

/**
 * 日志占位。
 *
 * 日志功能已整体移除：不写文件、不打 logcat、没有应用内日志页。
 *
 * 保留这些空函数是为了不改动全项目几百处 `AppLog.i("Scan", ...)` 调用 ——
 * 那些调用点以后如果要恢复日志，把这里换回实现即可。
 *
 * `mask` / `maskPhone` / `preview` 仍有实现：它们也可能被用于非日志的展示场景。
 */
object AppLog {

    fun init(context: Context) = Unit

    fun d(tag: String, msg: String) = Unit
    fun i(tag: String, msg: String) = Unit
    fun w(tag: String, msg: String, t: Throwable? = null) = Unit
    fun e(tag: String, msg: String, t: Throwable? = null) = Unit

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
