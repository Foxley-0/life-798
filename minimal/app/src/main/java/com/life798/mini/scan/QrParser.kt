package com.life798.mini.scan

/**
 * 从二维码文本里提取设备编号。
 *
 * 实际投放的二维码有几种形态，这里做多形态容错，遇到没见过的格式也不至于直接失败：
 *   1. 纯编号                 1234567890
 *   2. 带 id 参数的链接        https://.../q/1/b?id=123456
 *   3. 带 q 参数的链接         https://.../nfc.html?q=1D06FD47131080
 *   4. 其它链接               兜底遍历全部 query 参数，取第一个形似编号的值
 */
object QrParser {

    /** 编号允许的字符集：字母、数字、下划线、连字符。 */
    private val PLAIN = Regex("^[A-Za-z0-9_-]{4,64}$")

    /** 优先尝试的参数名，顺序即优先级。 */
    private val PREFERRED_KEYS = listOf("id", "q", "did", "deviceId", "device_id", "device", "code", "sn")

    fun extract(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1. 纯编号
        if (PLAIN.matches(text)) return text

        // 2~4. 链接
        return extractFromUrl(text)
    }

    private fun extractFromUrl(text: String): String? {
        val query = runCatching {
            // 兼容没有 scheme 的裸域名写法
            val normalized = if (text.contains("://")) text else "https://$text"
            java.net.URI(normalized).rawQuery
        }.getOrNull() ?: return null

        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1)
            if (value.isNotBlank()) params.putIfAbsent(key, value)
        }
        if (params.isEmpty()) return null

        // 优先按约定字段名取
        for (key in PREFERRED_KEYS) {
            val value = params[key]
            if (value != null && PLAIN.matches(value)) return value
        }

        // 兜底：任何形似编号的参数值
        for (value in params.values) {
            if (PLAIN.matches(value)) return value
        }
        return null
    }
}
