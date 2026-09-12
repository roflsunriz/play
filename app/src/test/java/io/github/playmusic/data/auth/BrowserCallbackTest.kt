package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserCallbackTest {
    @Test fun acceptsOnlyTheExpectedStateAndPath() {
        val callback = BrowserAuthorizationClient.callback("GET /login?code=synthetic%2Bcode&state=expected HTTP/1.1", "expected")
        assertEquals("synthetic+code", callback?.code)
        assertNull(callback?.error)
        for (request in listOf(
            "GET /login?code=synthetic&state=wrong HTTP/1.1",
            "GET /other?code=synthetic&state=expected HTTP/1.1",
            "GET /login?code=synthetic HTTP/1.1",
            "GET /login?code=synthetic&state=expected&state=expected HTTP/1.1",
            "GET https://other.example/login?code=synthetic&state=expected HTTP/1.1",
            "POST /login?code=synthetic&state=expected HTTP/1.1",
            "GET /login?code=&state=expected HTTP/1.1",
        )) assertNull(request, BrowserAuthorizationClient.callback(request, "expected"))
    }

    @Test fun denialCannotBecomeASuccessfulAuthorization() {
        val response = BrowserAuthorizationClient.callback("GET /login?error=access_denied&state=expected HTTP/1.1", "expected")
        assertNull(response?.code)
        assertEquals("access_denied", response?.error)
        assertNull(BrowserAuthorizationClient.callback(
            "GET /login?code=synthetic&error=access_denied&state=expected HTTP/1.1", "expected"))
    }
}
