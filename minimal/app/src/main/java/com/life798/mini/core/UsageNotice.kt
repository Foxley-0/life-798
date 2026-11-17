package com.life798.mini.core

import java.security.MessageDigest

/**
 * 《使用须知与数据说明》正文。
 *
 * 故意做成纯数据、不碰 Android —— [hash] 要在没有 Context 的地方也能算出来，
 * [UsageConsent] 靠它判断「这份文案用户到底同意过没有」。
 */
object UsageNotice {

    const val TITLE = "使用须知与数据说明"

    /**
     * 一条正文。
     *
     * @param heading 小标题
     * @param body 正文
     * @param emphasis 需要加粗的词。命不中就按普通文字渲染，不会报错；
     *   加粗只加字重不加颜色，暗色下也不会因为写死颜色而看不清
     */
    data class Clause(
        val heading: String,
        val body: String,
        val emphasis: List<String> = emptyList(),
    )

    val CLAUSES: List<Clause> = listOf(
        Clause(
            heading = "项目性质",
            body = "本应用是非官方开源项目，与「798 惠生活」「胖乖生活」的运营方不存在隶属、" +
                "合作或授权关系，出于个人学习与研究目的开发，源代码可自行审阅。",
            emphasis = listOf("非官方开源项目", "不存在隶属、合作或授权关系"),
        ),
        Clause(
            heading = "账号使用",
            body = "请仅使用本人有权使用的账号，并遵守相关服务方的规定与协议。" +
                "使用本应用不改变你与运营方之间的权利义务。",
            emphasis = listOf("本人有权使用"),
        ),
        Clause(
            heading = "数据存储",
            body = "登录凭证、账单记录、常用设备与全部设置都保存在本机应用私有目录，" +
                "不上传至任何自建服务器，开发者无法读取。卸载应用会一并删除，且不可恢复。",
            emphasis = listOf("保存在本机应用私有目录", "不上传至任何自建服务器", "开发者无法读取"),
        ),
        Clause(
            heading = "数据传输",
            body = "扫码、用水、洗衣等请求由你的设备直接发送至服务方官方接口，开发者不中转这些数据；" +
                "扣费与用水记录以服务方官方记录为准。",
            emphasis = listOf("直接发送至服务方官方接口", "以服务方官方记录为准"),
        ),
        Clause(
            heading = "更新检查",
            body = "应用更新检查会请求 GitHub 接口，只发送版本号与系统版本，" +
                "不携带账号、令牌或设备标识，且可手动关闭。",
            emphasis = listOf("不携带账号、令牌或设备标识"),
        ),
        Clause(
            heading = "可用性",
            body = "官方接口与规则可能随时变更，导致部分功能失效。本应用不保证持续可用，" +
                "使用风险由你自行承担。",
            emphasis = listOf("不保证持续可用"),
        ),
    )

    /** 结尾那条不编号的提醒。 */
    const val FOOTER = "若不同意以上内容，请不要登录使用。"

    /**
     * 全文指纹（SHA-256 前 8 字节）。
     *
     * 改一个字这里就变，之前存下的同意记录随之失效，用户会被要求重读一遍 ——
     * 这是这套机制唯一的意义：不让人在不知情的情况下被旧同意书覆盖。
     */
    val hash: String by lazy {
        val raw = buildString {
            append(TITLE).append('\u0001')
            CLAUSES.forEach {
                append(it.heading).append('\u0002').append(it.body).append('\u0001')
            }
            append(FOOTER)
        }
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
