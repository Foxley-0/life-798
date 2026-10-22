package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTaskSignerTest {
    @Test
    fun appUsesIndependentSaltAndThirtySecondServerTimeBucket() {
        val expected = "55674a19c3545779b5f0035cd63a1ca9"
        assertEquals(expected, Signer.signApp("task-a", "prefix-tokenEND", "prefix-user-END", "test-salt", 629_999L))
        assertEquals("69487cc66f678bf736b6ad508a44b067",
            Signer.signApp("task-a", "prefix-tokenEND", "prefix-user-END", "test-salt", 630_000L))
    }

    @Test(expected = IllegalStateException::class)
    fun missingAppConfigurationDoesNotSilentlyUseMiniSalt() {
        Signer.signApp("task-a", "tokenEND", "user-END", "", 600_000L)
    }

    @Test
    fun appSmsLoginSignsSortedFieldsWithoutTimeOrAccountSuffixes() {
        assertEquals("956d69c479128205212f60b68dcedda1",
            Signer.signAppLogin("13800000000", "123456", "test-salt"))
    }

    @Test(expected = IllegalStateException::class)
    fun appSmsLoginRequiresItsOwnConfiguration() {
        Signer.signAppLogin("13800000000", "123456", "")
    }
}
