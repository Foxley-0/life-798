package com.life798.mini.scan

/**
 * 二维码解析结果。
 *
 * 之所以区分几种，是因为「解析失败」和「平台不对」对用户来说是两件完全不同的事：
 * 前者可能再扫一次就好，后者扫一百次也没用。
 *
 * 现在有两个平台，解析结果必须带平台信息 —— 胖乖设备码是 `NQT`（一个 UUID），
 * 和 798 的纯数字设备编号长得完全不一样，不能混进同一个 [Ok] 里。
 */
sealed interface QrResult {

    /**
     * 慧生活798 的设备。
     * @param host 二维码来源域名；纯编号码为空串
     */
    data class Ok(val deviceId: String, val host: String) : QrResult

    /**
     * 胖乖生活的设备码。
     * @param nqt 二维码里的 `NQT` 参数（UUID），不是设备编号，不能直接拿去启动设备
     * @param host 二维码来源域名；裸 UUID 时为空串
     */
    data class Pangguai(val nqt: String, val host: String) : QrResult

    /**
     * 胖乖用水设备（饮水机 / 淋浴）的码：`SN=<15 位 IMEI>`。
     * 与洗衣机的 NQT 是两种码，接口链路也完全不同，必须分开。
     */
    data class PangguaiWater(val sn: String, val host: String) : QrResult

    /** 识别出了编号，但来源域名不属于这两个平台。 */
    data class WrongPlatform(val host: String) : QrResult

    /** 二维码内容里没有可用的设备标识。 */
    data class Unrecognized(val raw: String) : QrResult
}

/**
 * 二维码解析器。
 *
 * 支持的形态：
 *   1. 纯编号             864165069240345                          → 798
 *   2. 路径携带           https://i.hnkzy.com/q/1/864165069240345  → 798
 *   3. query 参数携带     https://...?id=123456 / ?q=abc / ?SN=...  → 798
 *   4. 胖乖设备码         https://h5.qiekj.com/...?NQT=<uuid>       → 胖乖
 *   5. 裸 NQT             c94ec8a4-acf1-46a1-a3de-f67bccb01326      → 胖乖
 *
 * 平台按域名白名单分流：先判胖乖，再判 798，都不是就拒绝。
 * 白名单用「完全相等」或「以 .域名 结尾」，防止 `evil-qiekj.com` 之类的伪装。
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

    /** 胖乖的 NQT 是标准 UUID；裸文本只有这一种形状才敢认。 */
    private val NQT_UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /** URL 里 NQT 参数的取值范围放宽一点，兼容非标准但可用的码。 */
    private val NQT_VALUE = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** 用水设备 SN 是 15 位数字 IMEI。裸数字码不走这里，保持 798 行为。 */
    private val SN_VALUE = Regex("^[0-9]{15}$")

    /** 优先尝试的参数名，顺序即优先级。 */
    private val PREFERRED_KEYS =
        listOf("id", "q", "did", "deviceId", "device_id", "device", "code", "sn")

    /** 慧生活798 的官方域名（湖南康之源，hnkzy.com）。 */
    private val ALLOWED_HOSTS = listOf("hnkzy.com", "ilife798.com")

    /**
     * 胖乖生活的二维码域名白名单。
     *
     * 抓包实测业务域是 `userapi.qiekj.com`，二维码落地页在 `h5.qiekj.com`。
     * 用 `qiekj.com` 作后缀白名单，两者都覆盖，也覆盖将来新增的子域。
     */
    private val PANGGUAI_HOSTS = listOf("qiekj.com")

    fun parse(raw: String?): QrResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return QrResult.Unrecognized(text)

        // 1. 裸 NQT：UUID 形状，必须先于纯编号判断。
        //    UUID 也能被 PLAIN 匹配，但它是胖乖码，绝不能当 798 设备号送去启动。
        if (NQT_UUID.matches(text)) return QrResult.Pangguai(text, host = "")

        // 2. 纯编号：没有域名可判断来源，必须满足更严的格式，否则当普通文本拒掉
        if (PLAIN.matches(text)) {
            return if (PLAIN_STRICT.matches(text)) {
                QrResult.Ok(text, host = "")
            } else {
                QrResult.Unrecognized(text)
            }
        }

        // 3. URL 形态
        val uri = runCatching {
            java.net.URI(if (text.contains("://")) text else "https://$text")
        }.getOrNull() ?: return QrResult.Unrecognized(text)

        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return QrResult.Unrecognized(text)

        // 3.1 先判胖乖，再判 798 —— 平台不对时编号毫无意义
        if (isPangguaiHost(host)) {
            val nqt = extractNqt(uri)
            if (nqt != null) return QrResult.Pangguai(nqt, host)
            val sn = extractSn(uri)
            if (sn != null) return QrResult.PangguaiWater(sn, host)
            return QrResult.Unrecognized(text)
        }

        if (!isHuiLifeHost(host)) return QrResult.WrongPlatform(host)

        // 3.2 取编号
        val id = extractId(uri)
        return if (id != null) QrResult.Ok(id, host) else QrResult.Unrecognized(text)
    }

    /** 胖乖域名白名单判断：完全相等或以 `.qiekj.com` 结尾。 */
    private fun isPangguaiHost(host: String): Boolean =
        PANGGUAI_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }

    /**
     * 慧生活798 域名白名单判断。
     *
     * 必须用「完全相等」或「.域名 结尾」，不能用 contains：
     *   evil-hnkzy.com      含子串，但结尾是 -hnkzy.com，拒绝
     *   hnkzy.com.evil.cn   前缀匹配，但结尾不是 .hnkzy.com，拒绝
     */
    private fun isHuiLifeHost(host: String): Boolean =
        ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }

    /** 从胖乖 URL 里取 `NQT`。参数名大小写不敏感，值做过 URL 解码。 */
    private fun extractNqt(uri: java.net.URI): String? {
        val query = uri.rawQuery
        if (!query.isNullOrBlank()) {
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val key = pair.substring(0, idx)
                val value = runCatching {
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }.getOrNull().orEmpty()
                if (key.equals("NQT", ignoreCase = true) && NQT_VALUE.matches(value)) {
                    return value
                }
            }
            // 兼容参数名不同但值是 UUID 的码
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val value = runCatching {
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }.getOrNull().orEmpty()
                if (NQT_UUID.matches(value)) return value
            }
        }

        // 路径形态：/NQT/<uuid>
        val lastSegment = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
        if (NQT_UUID.matches(lastSegment)) return lastSegment

        return null
    }

    /** 从胖乖用水二维码里取 `SN`。参数名大小写不敏感，值必须是 15 位数字。 */
    private fun extractSn(uri: java.net.URI): String? {
        val query = uri.rawQuery ?: return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = runCatching {
                java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull().orEmpty()
            if (key.equals("SN", ignoreCase = true) && SN_VALUE.matches(value)) return value
        }
        return null
    }

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
