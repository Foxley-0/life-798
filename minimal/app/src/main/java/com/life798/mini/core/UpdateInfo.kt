package com.life798.mini.core

/**
 * 一次「检查更新」的结果。
 *
 * 只留界面用得到的字段 —— GitHub 的 release 响应有几十个键，
 * 没必要整包塞进来。
 */
data class UpdateInfo(
    /** 去掉前缀 v 的版本号，例如 "1.3.10"。 */
    val version: String,
    /** release 标题，例如「798 小助手 v1.3.10」。 */
    val title: String,
    /** release 页面，浏览器打开用。 */
    val pageUrl: String,
    /** 直链 APK；CI 没上传资产时为 null。 */
    val apkUrl: String?,
    /** 发布日期，形如 "2026-11-03"。 */
    val publishedAt: String,
)

/**
 * 按数字段比较版本号，[latest] 比 [current] 新返回 true。
 *
 * 必须逐段转 Int 比较。字符串比较会把 "1.3.10" 判成小于 "1.3.7"
 * （'1' < '7'）—— 这是这段逻辑唯一容易踩的坑。
 *
 * 段数不一致时缺的段按 0 算，所以 "1.4" == "1.4.0"。
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = latest.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
