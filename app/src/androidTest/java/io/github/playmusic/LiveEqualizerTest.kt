package io.github.playmusic

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.testing.AudioProbeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.log10

/** Measures only amplitudes after the production processor; restores the user's curve and does not save audio. */
class LiveEqualizerTest {
    @Test fun preampChangesRealDecodedOutputAndThirtyBandsKeepPlaying(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEqualizer") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val original = withTimeout(5_000) { app.audioEffects.state.first { it.isReady } }.settings
        val uri = "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
        val track = app.repository.detail(MusicContent(uri.substringAfterLast(':'), uri, "", "", null, ContentKind.TRACK)).content
        suspend fun measure(settings: EqualizerSettings): List<Double> {
            app.audioEffects.setSettings(settings)
            val playback = LocalPlayback(context, AudioProbeService::class.java)
            AudioProbeService.resetMeasurements()
            try {
                playback.play(listOf(track))
                withTimeout(60_000) { while (AudioProbeService.rmsAt(22) == null) delay(100) }
                assertTrue(AudioProbeService.audibleSecondsIn(16, 21) >= 5)
                return (16..20).map { checkNotNull(AudioProbeService.rmsAt(it)) }
            } finally {
                try { playback.clear() } finally {
                    playback.release()
                    context.stopService(Intent(context, AudioProbeService::class.java))
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val flat = measure(EqualizerSettings(enabled = true))
                val quieter = measure(EqualizerSettings(enabled = true, preampDb = -6f))
                for (index in flat.indices) {
                    val gain = 20 * log10(quieter[index] / flat[index])
                    assertEquals("Actual output gain at second ${index + 16}", -6.0, gain, .15)
                    android.util.Log.i("PlayEqualizerProbe", "second=${index + 16} gainDb=$gain")
                }
                measure(EqualizerSettings(true, -6f, List(30) { if (it % 2 == 0) 3f else -3f }))
            }
        } finally {
            app.audioEffects.setSettings(original)
            assertTrue("The original sound settings must be restored", app.audioEffects.persistNow())
        }
    }
}
