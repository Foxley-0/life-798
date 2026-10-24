package com.life798.mini.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码解析与平台校验。
 *
 * 重点是那几条「欺骗域名」——如果哪天有人把 contains 换回来，这几个用例会立刻失败。
 */
class QrParserTest {

    private fun idOf(raw: String): String? =
        (QrParser.parse(raw) as? QrResult.Ok)?.deviceId

    private fun isWrongPlatform(raw: String): Boolean =
        QrParser.parse(raw) is QrResult.WrongPlatform

    private fun isUnrecognized(raw: String): Boolean =
        QrParser.parse(raw) is QrResult.Unrecognized

    // ---------- 慧生活798：正常放行 ----------

    @Test
    fun pathForm() {
        assertEquals("864165069240345", idOf("https://i.hnkzy.com/q/1/864165069240345"))
    }

    @Test
    fun httpScheme() {
        assertEquals("864165069240345", idOf("http://i.hnkzy.com/q/1/864165069240345"))
    }

    @Test
    fun subdomain() {
        assertEquals("123456", idOf("https://www.hnkzy.com/x?id=123456"))
    }

    @Test
    fun rootDomain() {
        assertEquals("123456", idOf("https://hnkzy.com/q/1/123456"))
    }

    @Test
    fun ilifeDomain() {
        assertEquals("123456", idOf("https://i.ilife798.com/q/1/123456"))
    }

    @Test
    fun uppercaseHost() {
        assertEquals("123456", idOf("HTTPS://I.HNKZY.COM/q/1/123456"))
    }

    @Test
    fun hostWithPort() {
        assertEquals("123456", idOf("https://i.hnkzy.com:8080/q/1/123456"))
    }

    @Test
    fun queryParam() {
        assertEquals("123456", idOf("https://i.hnkzy.com/x?q=123456"))
    }

    // ---------- 非慧生活798：必须拒绝 ----------

    @Test
    fun pangguaiRejected() {
        assertTrue(isWrongPlatform("https://h5.qiekj.com/skip?SN=867369079894938"))
    }

    @Test
    fun substringSpoofRejected() {
        // 含 hnkzy.com 子串，但不是其子域
        assertTrue(isWrongPlatform("https://evil-hnkzy.com/q/1/123456"))
    }

    @Test
    fun prefixSpoofRejected() {
        assertTrue(isWrongPlatform("https://hnkzy.com.evil.cn/q/1/123456"))
    }

    @Test
    fun ipRejected() {
        assertTrue(isWrongPlatform("https://192.168.1.10/q/1/123456"))
    }

    // ---------- 纯编号：无域名可判断，放行 ----------

    @Test
    fun plainIdAccepted() {
        val r = QrParser.parse("864165069240345")
        assertTrue(r is QrResult.Ok)
        r as QrResult.Ok
        assertEquals("864165069240345", r.deviceId)
        assertEquals("", r.host)
    }

    // ---------- 无法识别 ----------

    @Test
    fun randomText() {
        assertTrue(isUnrecognized("hello world"))
    }

    @Test
    fun emptyText() {
        assertTrue(isUnrecognized(""))
    }

    @Test
    fun huiLifeButNoId() {
        assertTrue(isUnrecognized("https://i.hnkzy.com/"))
    }
}
