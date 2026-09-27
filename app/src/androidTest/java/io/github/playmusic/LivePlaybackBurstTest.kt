package io.github.playmusic

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.playback.LicenseRequestMetrics
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.PlaybackTransitionStore
import io.github.playmusic.data.playback.StreamingApiClient
import io.github.playmusic.data.playback.TransitionPlayer
import io.github.playmusic.testing.TransitionAudioProbeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

/** Explicitly enabled real audio. Stores only request counts and numeric audio levels. */
class LivePlaybackBurstTest {
    @Test fun repeatedSeeksAndSelectionsKeepAudiblePlayback(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBurst") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val saved = withTimeout(5_000) { app.playbackTransitions.state.first { it.isReady } }.settings
        val journal = java.io.File(context.filesDir, "live-burst-settings.json")
        val original = if (journal.exists()) PlaybackTransitionStore.decode(journal.readText()) else saved.also {
            java.io.FileOutputStream(journal).use { output ->
                output.write(PlaybackTransitionStore.encode(it).toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
        }
        val playback = LocalPlayback(context, TransitionAudioProbeService::class.java)
        try {
            app.playbackTransitions.setSettings(PlaybackTransitionSettings(fadeInSeconds = 1,
                fadeOutSeconds = 1, crossfadeSeconds = 1, automixEnabled = false))
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(120_000) { app.playbackAuthorization.prepare(URI(StreamingApiClient.LICENSE_URL)) }
                val firstUri = "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
                val first = app.repository.detail(MusicContent(firstUri.substringAfterLast(':'), firstUri,
                    "", "", null, ContentKind.TRACK)).content
                val second = app.repository.search("Queen Another One Bites The Dust", SearchFilter.TRACKS)
                    .first { it.uri != first.uri && it.isPlayable != false && it.durationMs > 120_000 }
                val third = app.repository.search("Queen Don't Stop Me Now", SearchFilter.TRACKS)
                    .first { it.uri !in listOf(first.uri, second.uri) && it.isPlayable != false && it.durationMs > 120_000 }
                val queue = listOf(first, second, third)
                Log.i(TAG, "phase=initial ${LicenseRequestMetrics.snapshot()}")
                playback.play(queue, startPositionMs = 20_000)
                awaitAudible(first.uri)
                val beforeSeek = LicenseRequestMetrics.snapshot()
                Log.i(TAG, "phase=seeks $beforeSeek")
                for (position in listOf(60_000L, 30_000L, 90_000L, 20_000L, 65_000L, 35_000L)) {
                    playback.seek(position); delay(150)
                }
                awaitAudible(first.uri)
                assertEquals("Same-song seeks must reuse the loaded license", beforeSeek.attempts,
                    LicenseRequestMetrics.snapshot().attempts)
                playback.play(queue, 1, 35_000)
                Log.i(TAG, "phase=second ${LicenseRequestMetrics.snapshot()}")
                awaitAudible(second.uri)
                val beforeReturn = LicenseRequestMetrics.snapshot()
                playback.play(queue, 0, 35_000)
                Log.i(TAG, "phase=return ${LicenseRequestMetrics.snapshot()}")
                awaitAudible(first.uri)
                assertEquals("A-B-A within retention must reuse A's license", beforeReturn.attempts,
                    LicenseRequestMetrics.snapshot().attempts)

                val beforeBurst = LicenseRequestMetrics.snapshot()
                Log.i(TAG, "phase=burst $beforeBurst")
                for (index in listOf(1, 2, 1, 0, 2)) {
                    playback.play(queue, index, 35_000); delay(200)
                }
                awaitAudible(third.uri)
                val settled = LicenseRequestMetrics.snapshot()
                delay(5_000)
                assertEquals("Discarded selections must not keep requesting licenses", settled.attempts,
                    LicenseRequestMetrics.snapshot().attempts)
                assertTrue("Burst should not request every discarded selection",
                    settled.attempts - beforeBurst.attempts <= 3)
                playback.play(queue, 0, 35_000)
                awaitAudible(first.uri)
                playback.next(); delay(150); playback.next()
                awaitAudible(third.uri)
                playback.pause()
                withTimeout(5_000) { while (playback.state.value.isPlaying) delay(50) }
                playback.resume()
                awaitAudible(third.uri)
                Log.i(TAG, "phase=user-settings ${LicenseRequestMetrics.snapshot()}")
                app.playbackTransitions.setSettings(original)
                playback.play(queue, 0, 35_000)
                awaitAudible(first.uri)
                for (position in listOf(70_000L, 30_000L, 85_000L, 40_000L)) {
                    playback.seek(position); delay(200)
                }
                awaitAudible(first.uri)
                playback.next(); delay(200); playback.next()
                awaitAudible(third.uri)
                val result = LicenseRequestMetrics.snapshot()
                Log.i(TAG, "burst complete attempts=${result.attempts} successes=${result.successes} " +
                    "limited=${result.rateLimited} cancelled=${result.cancelled}")
            }
        } finally {
            try { playback.clear() } finally {
                playback.release()
                context.stopService(Intent(context, TransitionAudioProbeService::class.java))
                app.playbackTransitions.setSettings(original)
                assertTrue("Restore playback settings", app.playbackTransitions.persistNow())
                assertTrue("Remove restored settings journal", journal.delete())
            }
        }
    }

    private suspend fun awaitAudible(uri: String) {
        val windows = mutableSetOf<Pair<Int, Long>>()
        withTimeout(90_000) {
            while (windows.size < 10) {
                withContext(Dispatchers.Main) {
                    val player = checkNotNull(TransitionAudioProbeService.activeSession).player as TransitionPlayer
                    check(player.playerError == null) { "Player error code=${player.playerError?.errorCode}; metrics=${LicenseRequestMetrics.snapshot()}" }
                    if (player.currentMediaItem?.mediaId == uri && player.playbackState == Player.STATE_READY) {
                        player.audioEngines().forEachIndexed { index, engine ->
                            if (engine.currentMediaItem?.mediaId == uri && engine.isPlaying && engine.volume > .9f &&
                                engine.currentPosition >= 16_000) {
                                val timeline = engine.currentTimeline
                                if (engine.currentPeriodIndex in 0 until timeline.periodCount) {
                                    val uid = timeline.getUidOfPeriod(engine.currentPeriodIndex)
                                    val level = TransitionAudioProbeService.readingAt(index, uid, engine.currentPosition * 1_000)
                                    if (level != null && level.rms > .001 && level.peak > .002 &&
                                        SystemClock.elapsedRealtime() - level.elapsedMs < 5_000) windows += index to level.windows
                                }
                            }
                        }
                    }
                }
                delay(50)
            }
        }
    }

    companion object { private const val TAG = "PlayLiveBurst" }
}
