package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackAuthorizationDiagnosticsTest {
    @Test
    fun redactionDropsTokensAndKeepsShortErrorText() {
        val secret = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        val redacted = PlaybackAuthorizationDiagnostics.redact(
            """{"error":{"status":403,"message":"Missing client token","echo":"$secret"}}""",
        )
        assertEquals("""{"error":{"status":403,"message":"Missing client token","echo":"*"}}""", redacted)
        assertFalse(redacted.contains(secret))
        assertFalse(PlaybackAuthorizationDiagnostics.redact("see https://example.test/secret-path").contains("http"))
    }
}
