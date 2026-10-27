package com.life798.mini.scan

/**
 * 二维码解析结果。
 *
 * 之所以区分三种，是因为「解析失败」和「平台不对」对用户来说是两件完全不同的事：
 * 前者可能再扫一次就好，后者扫一百次也没用。
 */
sealed interface QrResult {

    /**
     * 慧生活798 的设备。
     * @param host 二维码来源域名；纯编号码为空串
     */
    data class Ok(val deviceId: String, val host: String) : QrResult

    /** 识别出了编号，但来源域名不属于慧生活798（例如胖乖生活的 h5.qiekj.com）。 */
    data class WrongPlatform(val host: String) : QrResult

    /** 二维码内容里没有可用的设备编号。 */
    data class Unrecognized(val raw: String) : QrResult
}

/**
 * 二维码解析器。
 *
 * 支持的形态：
 *   1. 纯编号             864165069240345
 *   2. 路径携带           https://i.hnkzy.com/q/1/864165069240345
 *   3. query 参数携带     https://...?id=123456 / ?q=abc / ?SN=...
 *
 * 平台校验：只接受慧生活798 的域名（hnkzy.com / ilife798.com 及其子域），
 * 其它域名一律判定为「非慧生活798设备」，不把编号交给接口去试。
 */
object QrParser {

    /** 编号允许的字符集：字母、数字、下划线、连字符。 */
    private val PLAIN = Regex("^[A-Za-z0-9_-]{2,64}$")

    /**
     * 纯文本形态的编号要求更严：至少 6 位、且至少含 5 个数字。
     *
     * 因为没有任何域名可以佐证来源，`OK`、`v1`、`123` 这类普通文本会被 [PLAIN] 放行，
     * 直接当成设备号送进启动页 —— 用户会莫名其妙跳到一个空设备上。
     * URL 形态不受此限制，那里有域名和路径结构做依据。
     */
    private val PLAIN_STRICT = Regex("^(?=(?:.*[0-9]){5,})[A-Za-z0-9_-]{6,64}$")

    /** 优先尝试的参数名，顺序即优先级。 */
    private val PREFERRED_KEYS =
        listOf("id", "q", "did", "deviceId", "device_id", "device", "code", "sn")

    /**
     * 慧生活798 的官方域名（湖南康之源，hnkzy.com）。
     * 比较时只认「完全相等」或「以 .域名 结尾」，防止 evil-hnkzy.com 之类的伪装。
     */
    private val ALLOWED_HOSTS = listOf("hnkzy.com", "ilife798.com")

    fun parse(raw: String?): QrResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return QrResult.Unrecognized(text)

        // 1. 纯编号：没有域名可判断来源，必须满足更严的格式，否则当普通文本拒掉
        if (PLAIN.matches(text)) {
            return if (PLAIN_STRICT.matches(text)) {
                QrResult.Ok(text, host = "")
            } else {
                QrResult.Unrecognized(text)
            }
        }

        // 2. URL 形态
        val uri = runCatching {
            java.net.URI(if (text.contains("://")) text else "https://$text")
        }.getOrNull() ?: return QrResult.Unrecognized(text)

        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return QrResult.Unrecognized(text)

        // 2.1 先判平台，再取编号 —— 平台不对时编号毫无意义
        if (!isHuiLifeHost(host)) return QrResult.WrongPlatform(host)

        // 2.2 取编号
        val id = extractId(uri)
        return if (id != null) QrResult.Ok(id, host) else QrResult.Unrecognized(text)
    }

    /**
     * 域名白名单判断。
     *
     * 必须用「完全相等」或「.域名 结尾」，不能用 contains：
     *   evil-hnkzy.com      含子串，但结尾是 -hnkzy.com，拒绝
     *   hnkzy.com.evil.cn   前缀匹配，但结尾不是 .hnkzy.com，拒绝
     */
    private fun isHuiLifeHost(host: String): Boolean =
        ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }

    private fun extractId(uri: java.net.URI): String? {
        // query 参数形态：?id=123 / ?q=abc / ?SN=...
        val query = uri.rawQuery
        if (!query.isNullOrBlank()) {
            val params = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                if (value.isNotBlank()) params.putIfAbsent(key, value)
            }

            for (key in PREFERRED_KEYS) {
                val value = params[key]
                if (value != null && PLAIN.matches(value)) return value
            }
            for (value in params.values) {
                if (PLAIN.matches(value)) return value
            }
        }

        // 路径形态：/q/1/864165069240345 —— 取最后一段
        val lastSegment = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
        if (PLAIN.matches(lastSegment)) return lastSegment

        return null
    }
}
