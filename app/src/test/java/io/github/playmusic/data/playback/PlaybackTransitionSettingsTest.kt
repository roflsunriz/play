package io.github.playmusic.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionSettingsTest {
    @Test fun settingsRoundTripPreservesEachIndependentDurationAndToggle() {
        val settings = PlaybackTransitionSettings(false, 1, true, 12, false, 7, true, false)
        assertEquals(settings, PlaybackTransitionStore.decode(PlaybackTransitionStore.encode(settings)))
        assertEquals(0L, settings.fadeInMs)
        assertEquals(12_000L, settings.fadeOutMs)
        assertEquals(0L, settings.crossfadeMs)
    }
    @Test fun invalidDurationAndFutureSchemaAreRejected() {
        for (seconds in listOf(-1, 0, 13, Int.MAX_VALUE)) {
            assertTrue(runCatching { PlaybackTransitionSettings(fadeInSeconds = seconds) }.isFailure)
            assertTrue(runCatching { PlaybackTransitionSettings(fadeOutSeconds = seconds) }.isFailure)
            assertTrue(runCatching { PlaybackTransitionSettings(crossfadeSeconds = seconds) }.isFailure)
        }
        assertTrue(runCatching { PlaybackTransitionStore.decode("{\"schemaVersion\":2}") }.isFailure)
    }
    @Test fun envelopesPreserveContinuityAtInterruptionAndCannotAmplifyOverlap() {
        for (duration in listOf(1_000L, 5_000L, 12_000L)) {
            for (elapsed in 0..duration step 10) {
                val fadeIn = TransitionEnvelope.gain(0f, 1f, elapsed, duration)
                val fadeOut = TransitionEnvelope.gain(1f, 0f, elapsed, duration)
                assertEquals(1f, fadeIn + fadeOut, .000001f)
                assertTrue(fadeIn in 0f..1f && fadeOut in 0f..1f)
            }
        }
        val interrupted = TransitionEnvelope.gain(1f, 0f, 300, 1_000)
        assertEquals(interrupted, TransitionEnvelope.gain(interrupted, 1f, 0, 12_000), 0f)
        assertEquals(1f, TransitionEnvelope.gain(interrupted, 1f, 12_000, 12_000), 0f)
        assertEquals(1f, TransitionEnvelope.gain(0f, 1f, 10, 0), 0f)
    }
    @Test fun shortTracksKeepAnUnmixedPortionAndUnknownDurationDoesNotStartOverlap() {
        assertEquals(500L, TransitionEnvelope.overlapMs(12_000, 1_000, 15_000))
        assertEquals(0L, TransitionEnvelope.overlapMs(5_000, -1, 15_000))
        assertEquals(5_000L, TransitionEnvelope.overlapMs(5_000, 200_000, 150_000))
    }
}
