package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginStatusStoreTest {
    @Test fun statusesAreSharedByAccountAndPlatformButNotByReplacedCredentials() {
        val account = Account("login-status-a").apply { token = "main-a"; appToken = "app-a" }
        val other = Account("login-status-b").apply { token = "main-a"; appToken = "app-a" }
        LoginStatusStore.record(account, true, TokenValidity.EXPIRED)
        LoginStatusStore.record(account, false, TokenValidity.VALID)

        val reloaded = Account("login-status-a").apply { token = "main-a"; appToken = "app-a" }
        assertEquals(TokenValidity.EXPIRED, LoginStatusStore.status(reloaded, true))
        assertEquals(TokenValidity.VALID, LoginStatusStore.status(reloaded, false))
        assertEquals(TokenValidity.UNCHECKED, LoginStatusStore.status(other, true))

        reloaded.appToken = "app-new"
        assertEquals(TokenValidity.UNCHECKED, LoginStatusStore.status(reloaded, true))
        assertEquals(TokenValidity.VALID, LoginStatusStore.status(reloaded, false))
        LoginStatusStore.record(reloaded, true, TokenValidity.VALID)
        assertEquals(TokenValidity.VALID, LoginStatusStore.status(reloaded, true))
    }
}
