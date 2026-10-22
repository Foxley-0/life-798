package com.water.widget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TokenValidityTest {
    @Test fun onlyLoginRejectionIsSeparatedFromOtherFailures() {
        assertEquals(TokenValidity.VALID, TokenValidity.from(JSONObject("""{"code":0}""")))
        assertEquals(TokenValidity.EXPIRED, TokenValidity.from(JSONObject("""{"code":-99}""")))
        assertEquals(TokenValidity.FAILED, TokenValidity.from(JSONObject("""{"code":-98}""")))
        assertEquals(TokenValidity.FAILED, TokenValidity.from(null))
        assertEquals(TokenValidity.FAILED, TokenValidity.from(JSONObject()))
    }

    @Test fun rejectedLoginDoesNotClaimConfirmedExpiry() {
        val expired = JSONObject("""{"code":-99}""")
        assertEquals("需重新验证", TokenValidity.EXPIRED.label)
        assertEquals("用水登录（App）未通过验证，请先检查登录状态，仍无法使用时再重新登录",
            TokenValidity.expiredMessage(expired, true))
        assertTrue(TokenValidity.expiredMessage(expired, false)!!.contains("积分登录（支付宝）"))
        assertNull(TokenValidity.expiredMessage(JSONObject("""{"code":-98}"""), true))
        assertNull(TokenValidity.expiredMessage(null, true))
    }
}
