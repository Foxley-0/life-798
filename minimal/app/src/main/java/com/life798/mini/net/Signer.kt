package com.life798.mini.net

import java.security.MessageDigest

/**
 * 登录签名。
 *
 * 服务端约定的原文顺序为：authCode=<短信码>&un=<手机号><盐值>
 * 结果为 32 位小写十六进制 MD5。
 */
object Signer {

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun loginSign(phone: String, smsCode: String, salt: String): String =
        md5("authCode=$smsCode&un=$phone$salt")

    private val HEX = "0123456789abcdef".toCharArray()
}
