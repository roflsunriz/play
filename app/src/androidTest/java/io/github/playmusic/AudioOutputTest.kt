package io.github.playmusic

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.testing.AudioProbeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioOutputTest {
    @Test fun decodedAudioRemainsAudibleBeyondTheOpening(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAudio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val uri = InstrumentationRegistry.getArguments().getString("trackUri")
            ?: "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
        val track = app.repository.detail(SpotifyContent(uri.substringAfterLast(':'), uri,
            "", "", null, ContentKind.TRACK)).content
        val playback = LocalPlayback(context, AudioProbeService::class.java)
        var failure: String? = null
        val errors = launch { playback.errors.collect { failure = it } }
        AudioProbeService.audibleLateSeconds = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                playback.play(listOf(track))
                withTimeout(60_000) {
                    while (playback.state.value.progressMs < 32_000) {
                        check(failure == null) { "Playback failed: $failure" }
                        delay(200)
                    }
                }
                val audibleSeconds = AudioProbeService.audibleLateSeconds
                assertTrue("Decoded sound disappeared after the opening: $audibleSeconds of seconds 16..30 contained sound",
                    audibleSeconds >= 12)
            }
        } finally {
            errors.cancel()
            try { playback.clear() } finally {
                try { playback.release() } finally {
                    context.stopService(Intent(context, AudioProbeService::class.java))
                }
            }
        }
    }
}
