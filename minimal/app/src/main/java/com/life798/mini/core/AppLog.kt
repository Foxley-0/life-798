package com.life798.mini.core

import android.content.Context
import android.util.Log
import com.life798.mini.BuildConfig

/**
 * 日志。
 *
 * 只打 logcat，不写文件、没有应用内日志页 —— 那些会带来存储和隐私成本。
 * debug 包全量输出；release 包只保留 warn / error —— 真机上唯一能用的排查手段
 * 就是 root 抓 logcat，之前 release 完全静默导致「设备工作中」这类服务端原因
 * 在日志里一点痕迹都没有。token / 手机号在调用点已经 mask，不额外落盘。
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

    /** 警告：release 也输出，供 root logcat 定位接口业务失败。 */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(PREFIX + tag, msg, t)
    }

    /** 错误：release 也输出。 */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(PREFIX + tag, msg, t)
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
