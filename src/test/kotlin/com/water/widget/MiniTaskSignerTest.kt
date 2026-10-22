package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniTaskSignerTest {
    @Test
    fun signatureUsesCredentialSuffixesAndThirtySecondBucket() {
        val expected = "55674a19c3545779b5f0035cd63a1ca9"
        for (now in listOf(600_000L, 610_000L, 629_999L)) {
            assertEquals(expected, Signer.signMini("task-a", "prefix-tokenEND", "prefix-user-END", "test-salt", now))
        }
        assertEquals(
            "69487cc66f678bf736b6ad508a44b067",
            Signer.signMini("task-a", "prefix-tokenEND", "prefix-user-END", "test-salt", 630_000L)
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingMiniSigningConfigFailsExplicitly() {
        Signer.signMini("task-a", "tokenEND", "user-END", "", 600_000L)
    }
}
