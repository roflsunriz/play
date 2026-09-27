package io.github.playmusic

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.playback.LicenseRequestMetrics
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.StreamingApiClient
import io.github.playmusic.data.playback.TransitionPlayer
import io.github.playmusic.testing.TransitionAudioProbeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import kotlin.random.Random

/** Six different recordings, selected every ~15 media seconds with the user's own settings. */
class LivePlaybackSequenceTest {
    @Test fun sixDifferentTracksRemainAudibleAcrossFifteenSecondSwitches(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSequence") == "true")
        runSequence(endurance = false)
    }

    @Test fun tenDifferentTracksWithRandomMiddleAndLateSeeksRemainAudible(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEndurance") == "true")
        runSequence(endurance = true)
    }

    private suspend fun runSequence(endurance: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val playback = LocalPlayback(context, TransitionAudioProbeService::class.java)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(120_000) { app.playbackAuthorization.prepare(URI(StreamingApiClient.LICENSE_URL)) }
                val firstUri = "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
                val tracks = mutableListOf(app.repository.detail(MusicContent(firstUri.substringAfterLast(':'),
                    firstUri, "", "", null, ContentKind.TRACK)).content)
                val queries = listOf("Queen Another One Bites The Dust", "Radiohead Karma Police",
                    "Daft Punk Get Lucky", "ABBA Dancing Queen", "The Beatles Come Together") +
                    if (endurance) listOf("Nirvana Smells Like Teen Spirit", "Michael Jackson Billie Jean",
                        "Adele Rolling in the Deep", "Coldplay Viva La Vida") else emptyList()
                for (query in queries) {
                    tracks += app.repository.search(query, SearchFilter.TRACKS).first {
                        it.uri !in tracks.map(MusicContent::uri) && it.isPlayable != false && it.durationMs > 120_000
                    }
                }
                val count = if (endurance) 10 else 6
                assertEquals(count, tracks.map(MusicContent::uri).distinct().size)
                val random = Random(RANDOM_SEED)
                if (endurance) Log.i(TAG, "endurance start tracks=$count seed=$RANDOM_SEED seeksPerTrack=4")
                val initial = LicenseRequestMetrics.snapshot()
                for ((index, track) in tracks.withIndex()) {
                    val before = LicenseRequestMetrics.snapshot()
                    val requestedAt = SystemClock.elapsedRealtime()
                    if (index == 0) playback.play(tracks) else playback.next()
                    var firstAudioMs: Long? = null
                    var position = 0L
                    val lateWindows = mutableSetOf<Pair<Int, Long>>()
                    withTimeout(60_000) {
                        while (position < 15_000) {
                            withContext(Dispatchers.Main) {
                                val player = checkNotNull(TransitionAudioProbeService.activeSession).player as TransitionPlayer
                                check(player.playerError == null) {
                                    "Track ${index + 1} error=${player.playerError?.errorCode} metrics=${LicenseRequestMetrics.snapshot()}"
                                }
                                if (player.currentMediaItem?.mediaId == track.uri) {
                                    position = player.currentPosition
                                    player.audioEngines().forEachIndexed { engineIndex, engine ->
                                        if (engine.currentMediaItem?.mediaId == track.uri && engine.isPlaying && engine.volume > .1f &&
                                            engine.currentPeriodIndex != C.INDEX_UNSET &&
                                            engine.currentPeriodIndex in 0 until engine.currentTimeline.periodCount) {
                                            val uid = engine.currentTimeline.getUidOfPeriod(engine.currentPeriodIndex)
                                            val level = TransitionAudioProbeService.readingAt(engineIndex, uid, engine.currentPosition * 1_000)
                                            if (level != null && level.rms > .001 && level.peak > .002) {
                                                if (firstAudioMs == null) firstAudioMs = SystemClock.elapsedRealtime() - requestedAt
                                                if (engine.currentPosition in 10_000..15_500) lateWindows += engineIndex to level.windows
                                            }
                                        }
                                    }
                                }
                            }
                            delay(40)
                        }
                    }
                    assertNotNull("Track ${index + 1} must produce actual sound", firstAudioMs)
                    assertTrue("Track ${index + 1} must retain sound after the encrypted boundary: ${lateWindows.size} windows",
                        lateWindows.size >= 20)
                    val after = LicenseRequestMetrics.snapshot()
                    if (!endurance) {
                        assertEquals("Each new track should obtain its license once", 1L, after.successes - before.successes)
                        assertEquals("Buffered future tracks must not create extra requests", 1L, after.attempts - before.attempts)
                    }
                    assertEquals("No rate-limited responses during ordinary switching", before.rateLimited, after.rateLimited)
                    Log.i(TAG, "track=${index + 1} mediaMs=$position firstAudioMs=$firstAudioMs " +
                        "wallMs=${SystemClock.elapsedRealtime() - requestedAt} lateWindows=${lateWindows.size} " +
                        "requests=${after.attempts - before.attempts} limited=${after.rateLimited - before.rateLimited}")
                    if (endurance) {
                        val duration = playback.state.value.durationMs
                        require(duration > 120_000)
                        val latest = duration - maxOf(18_000L, app.playbackTransitions.state.value.settings.crossfadeMs + 12_000)
                        repeat(4) { seekIndex ->
                            val middle = seekIndex % 2 == 0
                            val low = (duration * if (middle) .35 else .78).toLong()
                            val high = (duration * if (middle) .60 else .94).toLong().coerceAtMost(latest)
                            val target = random.nextLong(low, high)
                            playback.seek(target)
                            val seekStarted = SystemClock.elapsedRealtime()
                            val windows = awaitSeekAudio(track.uri, target)
                            Log.i(TAG, "track=${index + 1} seek=${seekIndex + 1} zone=${if (middle) "middle" else "late"} " +
                                "targetMs=$target audibleWindows=$windows wallMs=${SystemClock.elapsedRealtime() - seekStarted}")
                        }
                        val sought = LicenseRequestMetrics.snapshot()
                        assertEquals("Seeks must not provoke rate-limited responses", initial.rateLimited, sought.rateLimited)
                        // The explicitly prepared successor may already hold its one license.
                        assertTrue("Seeks must not rebuild current/future licenses repeatedly",
                            sought.attempts - initial.attempts <= minOf(index + 2, count))
                    }
                }
                val completed = LicenseRequestMetrics.snapshot()
                assertEquals(count.toLong(), completed.attempts - initial.attempts)
                Log.i(TAG, "sequence complete tracks=$count requests=${completed.attempts - initial.attempts} " +
                    "seeks=${if (endurance) 40 else 0} limited=${completed.rateLimited - initial.rateLimited}")
            }
        } finally {
            try { playback.clear() } finally {
                playback.release()
                context.stopService(Intent(context, TransitionAudioProbeService::class.java))
            }
        }
    }

    private suspend fun awaitSeekAudio(uri: String, targetMs: Long): Int {
        val windows = mutableSetOf<Pair<Int, Long>>()
        withTimeout(30_000) {
            while (windows.size < 10) {
                withContext(Dispatchers.Main) {
                    val player = checkNotNull(TransitionAudioProbeService.activeSession).player as TransitionPlayer
                    check(player.playerError == null) { "Seek error=${player.playerError?.errorCode}" }
                    check(player.currentMediaItem?.mediaId == uri) { "Seeking must not change the selected track" }
                    player.audioEngines().forEachIndexed { index, engine ->
                        if (engine.currentMediaItem?.mediaId == uri && engine.isPlaying && engine.volume > .9f &&
                            engine.currentPosition in targetMs..targetMs + 15_000 &&
                            engine.currentPeriodIndex in 0 until engine.currentTimeline.periodCount) {
                            val uid = engine.currentTimeline.getUidOfPeriod(engine.currentPeriodIndex)
                            val level = TransitionAudioProbeService.readingAt(index, uid, engine.currentPosition * 1_000)
                            if (level != null && level.rms > .001 && level.peak > .002) windows += index to level.windows
                        }
                    }
                }
                delay(40)
            }
        }
        return windows.size
    }

    companion object {
        private const val TAG = "PlayLiveSequence"
        private const val RANDOM_SEED = 20260927
    }
}
