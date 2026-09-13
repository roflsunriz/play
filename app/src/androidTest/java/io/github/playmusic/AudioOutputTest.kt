package io.github.playmusic

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.testing.AudioProbeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioOutputTest {
    @Test fun decodedAudioRemainsAudibleBeyondTheOpening(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAudio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val exerciseEqualizer = InstrumentationRegistry.getArguments().getString("thirtyBands") == "true"
        val originalEffects = if (exerciseEqualizer) withTimeout(5_000) { app.audioEffects.state.first { it.isReady } }.settings else null
        val uri = InstrumentationRegistry.getArguments().getString("trackUri")
            ?: "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
        val track = app.repository.detail(SpotifyContent(uri.substringAfterLast(':'), uri,
            "", "", null, ContentKind.TRACK)).content
        val playback = LocalPlayback(context, AudioProbeService::class.java)
        var failure: String? = null
        val errors = launch { playback.errors.collect { failure = it } }
        AudioProbeService.resetMeasurements()
        try {
            if (exerciseEqualizer) app.audioEffects.setSettings(EqualizerSettings(true, -6f,
                List(30) { if (it % 2 == 0) 3f else -3f }))
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
                if (InstrumentationRegistry.getArguments().getString("fullTrack") == "true") {
                    val duration = playback.state.value.durationMs
                    check(duration in 60_000..900_000) { "Full-audio verification requires a known track of 1..15 minutes" }
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                    withTimeout(duration + 90_000) {
                        while (playback.state.value.let { it.isPlaying || it.progressMs < duration - 1000 }) {
                            check(failure == null) { "Playback failed: $failure" }
                            delay(500)
                        }
                    }
                    val seconds = (duration / 1000).toInt()
                    val middle = AudioProbeService.audibleSecondsIn(seconds / 2 - 9, seconds / 2 + 9)
                    val ending = AudioProbeService.audibleSecondsIn(seconds - 25, seconds - 6)
                    assertTrue("The middle of the track lost audio ($middle of 19 seconds)", middle >= 14)
                    assertTrue("The ending of the track lost audio ($ending of 20 seconds)", ending >= 15)
                    android.util.Log.i(AudioProbeService.TAG, "full track ended; opening=$audibleSeconds middle=$middle ending=$ending")
                }
            }
        } finally {
            errors.cancel()
            try { playback.clear() } finally {
                try { playback.release() } finally {
                    context.stopService(Intent(context, AudioProbeService::class.java))
                    if (originalEffects != null) {
                        app.audioEffects.setSettings(originalEffects)
                        assertTrue("Restore the original sound settings", app.audioEffects.persistNow())
                    }
                }
            }
        }
    }
}
