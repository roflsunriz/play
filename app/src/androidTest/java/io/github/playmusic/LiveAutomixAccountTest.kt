package io.github.playmusic

import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.playback.AutomixApiClient
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only, opt-in metadata checks; does not change account settings, playlists or playback. */
class LiveAutomixAccountTest {
    @Test fun eligibleContextResolvesServiceCuesForTheExactPairAndUnsupportedContextDoesNot(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAutomix") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        var settings = PlaybackTransitionSettings(crossfadeSeconds = 1)
        val client = AutomixApiClient(app.sessionManager, { settings })
        for (seconds in listOf(1, 12)) {
            settings = settings.copy(crossfadeSeconds = seconds)
            val transition = client.resolve(CONTEXT, FROM, TO)
            assertNotNull("The service-declared Automix playlist must supply usable cuepoints", transition)
            val mix = checkNotNull(transition)
            assertEquals(FROM, mix.fromUri)
            assertEquals(TO, mix.toUri)
            assertTrue(mix.durationMs in 1_000..seconds * 1_000L)
            assertTrue(mix.outgoingStartMs > 0 && mix.incomingStartMs >= 0)
            assertTrue(mix.incomingSpeed in 0.9f..1.1f)
        }
        assertNull(client.resolve(UNSUPPORTED_CONTEXT, UNSUPPORTED_FROM, UNSUPPORTED_TO))
        assertNull(client.resolve(CONTEXT, FROM, UNSUPPORTED_FROM))
    }
    companion object {
        // Public catalog search and native playlist/extension replies verified 2026-09-15.
        // This context declared AutomixStyle.DEFAULT=1; the other context returned extension 27=404.
        const val CONTEXT = "spotify:playlist:37i9dQZF1DXaXB8fQg7xif"
        const val FROM = "spotify:track:0b18g3G5spr4ZCkz7Y6Q0Q"
        const val TO = "spotify:track:1x5sYLZiu9r5E43kMlt9f8"
        const val UNSUPPORTED_CONTEXT = "spotify:playlist:37i9dQZF1EIgM5iO4YYPuD"
        const val UNSUPPORTED_FROM = "spotify:track:0HPD5WQqrq7wPWR7P7Dw1i"
        const val UNSUPPORTED_TO = "spotify:track:32OlwWuMpZ6b0aN2RZOeMS"
    }
}
