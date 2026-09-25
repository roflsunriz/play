package io.github.playmusic.ui

import org.junit.Assert.*
import org.junit.Test

class WebLoginCaptureTest {
    @Test fun allowedHostsCoverTheLoginFlowOnly() {
        for (host in listOf("open.spotify.com", "accounts.spotify.com", "xpui.app.spotify.com",
            "open.spotifycdn.com", "OPEN.SPOTIFY.COM")) {
            assertTrue("allow $host", WebLoginCapture.isAllowedHost(host))
        }
        for (host in listOf(null, "", "spotify.com", "open.spotify.com.attacker.invalid",
            "attacker-open.spotify.com.attacker.invalid", "example.test")) {
            assertFalse("block $host", WebLoginCapture.isAllowedHost(host))
        }
    }

    @Test fun spDcIsExtractedAndValidatedWithoutLogging() {
        assertEquals("valid-cookie-value_1",
            WebLoginCapture.extractSpDc("a=b; sp_dc=valid-cookie-value_1; c=d"))
        assertEquals("valid-cookie-value_1", WebLoginCapture.extractSpDc("sp_dc=valid-cookie-value_1"))
        assertNull(WebLoginCapture.extractSpDc("a=b; c=d"))
        assertNull(WebLoginCapture.extractSpDc(null))
        assertNull(WebLoginCapture.extractSpDc(""))
        assertNull(WebLoginCapture.extractSpDc("sp_dc=has space"))
        assertNull(WebLoginCapture.extractSpDc("sp_dc="))
        assertNull(WebLoginCapture.extractSpDc("sp_dc=" + "x".repeat(4097)))
        assertNull(WebLoginCapture.extractSpDc("sp_dc=" + "x".repeat(16384)))
    }
}
