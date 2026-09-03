package io.github.playmusic.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModelTest {
    @Test
    fun `repeat mode cycles through context and track`() {
        assertEquals(RepeatMode.CONTEXT, RepeatMode.OFF.next())
        assertEquals(RepeatMode.TRACK, RepeatMode.CONTEXT.next())
        assertEquals(RepeatMode.OFF, RepeatMode.TRACK.next())
    }

    @Test
    fun `session refreshes only inside safety window`() {
        val session = AuthSession(
            username = "user",
            accessToken = "access",
            storedCredential = ByteArray(4),
            expiresAtEpochMs = 100_000L,
        )

        assertFalse(session.expiresSoon(nowEpochMs = 39_999L))
        assertTrue(session.expiresSoon(nowEpochMs = 40_000L))
    }
}
