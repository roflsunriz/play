package io.github.playmusic

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.playback.AutomixApiClient
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.TransitionPlayer
import io.github.playmusic.testing.TransitionAudioProbeService
import io.github.playmusic.testing.MediaTimeLevelMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual audio: no system-volume changes, no audio/key/token/lyrics recordings. */
class LivePlaybackTransitionsTest {
    @Test fun realAudioFadesOverlapsAndStopsBothDecoders(): Unit = runBlocking {
        assumeTrue(arguments.getString("liveTransitions") == "true")
        withProbe(automix = false) {
            val first = track(arguments.getString("trackUri") ?: "spotify:track:4CeeEOM32jQcH3eN9Q2dGj")
            val second = arguments.getString("secondTrackUri")?.let { track(it) } ?: app.repository
                .search("Queen Another One Bites The Dust", SearchFilter.TRACKS)
                .first { it.uri != first.uri && it.isPlayable != false && it.durationMs > 60_000 }
            require(first.uri != second.uri && first.durationMs > 40_000 && second.durationMs > 40_000)
            playback.play(listOf(first, second, first))
            val early = await("initial fade-in") { state -> state.engines.any { it.playing && it.gain in .1f.. .6f } }
            val high = await("fade-in reaches full gain") { state -> state.engines.any { it.playing && it.gain > .99f } }
            assertTrue(high.engines.single { it.playing && it.uri == first.uri }.gain >
                early.engines.single { it.playing && it.uri == first.uri }.gain)

            val openingWindows = mutableSetOf<Long>()
            await("audible audio beyond 30 seconds", timeoutMs = 60_000) { state ->
                state.engines.filter { it.uri == first.uri && it.playing && it.position in 16_000..31_000 && it.audible() }
                    .forEach { openingWindows += checkNotNull(it.renderedLevel).windows }
                state.engines.any { it.uri == first.uri && it.position >= 32_000 }
            }
            assertTrue("Expected at least 6 seconds of distinct audible 100ms windows after the opening", openingWindows.size >= 60)
            playback.pause()
            await("pause fade-out midpoint") { state -> state.engines.any { it.playing } && state.gain in .1f.. .8f }
            assertStopped(stop = false)
            playback.resume()
            await("resume fade-in") { state -> state.engines.any { it.playing && it.gain in .1f.. .8f } }
            await("resume full gain") { state -> state.engines.any { it.playing && it.gain > .99f } }

            val before = snapshot().engines.map { it.level.windows }
            playback.next()
            val overlap = awaitAudibleOverlap(before, second.uri)
            assertEquals(second.uri, overlap.uri)
            playback.pause()
            await("overlap pause fades both engines") { state -> state.engines.count { it.playing } == 2 && state.gain in .1f.. .8f }
            assertStopped(stop = false)

            playback.seekAndPlay(31_000)
            await("second track remains audible after 30 seconds") { state ->
                state.engines.any { it.uri == second.uri && it.position >= 33_000 && it.playing && it.gain > .99f && it.audible() }
            }
            playback.next()
            await("second overlap") { state -> state.engines.count { it.playing && it.gain in .15f.. .85f } == 2 }
            playback.stop()
            await("stop fade-out midpoint") { state -> state.engines.any { it.playing } && state.gain in .1f.. .7f }
            assertStopped(stop = true)
            Log.i(TAG, "crossfade openingWindows=${openingWindows.size} overlapRms=${overlap.engines.map { it.renderedLevel?.rms }} overlapPeak=${overlap.engines.map { it.renderedLevel?.peak }} gains=${overlap.engines.map { it.gain }}")
        }
    }

    @Test fun serviceAutomixCuesOverlapAndRestorePlaybackSpeed(): Unit = runBlocking {
        assumeTrue(arguments.getString("liveTransitions") == "true" && arguments.getString("automixPlaylistUri") != null)
        withProbe(automix = true) {
            val contextUri = requireNotNull(arguments.getString("automixPlaylistUri"))
            val playlist = SpotifyContent(contextUri.substringAfterLast(':'), contextUri, "", "", null, ContentKind.PLAYLIST)
            val playlistTracks = app.repository.detail(playlist).tracks
            val fromUri = arguments.getString("automixFromUri")
            val toUri = arguments.getString("automixToUri")
            require((fromUri == null) == (toUri == null)) { "Specify both transition track arguments together" }
            val tracks = if (fromUri == null) playlistTracks.take(2) else {
                require(fromUri != toUri) { "Transition requires two different tracks" }
                listOf(fromUri, requireNotNull(toUri)).map { uri ->
                    requireNotNull(playlistTracks.firstOrNull { it.uri == uri }) {
                        "Selected transition track must belong to the live playlist"
                    }
                }
            }
            require(tracks.size == 2)
            val recipe = requireNotNull(AutomixApiClient(app.sessionManager,
                { app.playbackTransitions.state.value.settings }).resolve(contextUri, tracks[0].uri, tracks[1].uri)) {
                "Selected playlist pair must provide a supported live transition"
            }
            require(recipe.outgoingStartMs > 12_000 && recipe.incomingStartMs > 1_000 && recipe.incomingSpeed != 1f)
            playback.play(tracks, startPositionMs = recipe.outgoingStartMs - 12_000, contextUri = contextUri)
            await("automix initial fade ready") { state -> state.engines.any { it.playing && it.gain > .99f } }
            val before = snapshot().engines.map { it.level.windows }
            playback.seek(recipe.outgoingStartMs - 2_000)
            val overlap = awaitAudibleOverlap(before, tracks[1].uri)
            val incoming = overlap.engines.single { it.uri == tracks[1].uri }
            val outgoing = overlap.engines.single { it.uri == tracks[0].uri }
            assertTrue("Incoming decoder must use the provided entry cue", incoming.position >= recipe.incomingStartMs)
            assertTrue("Entry must still be inside the supplied transition", incoming.position < recipe.incomingStartMs + recipe.durationMs * recipe.incomingSpeed + 1_000)
            assertTrue("Outgoing decoder must reach the provided exit cue", outgoing.position >= recipe.outgoingStartMs)
            assertEquals(recipe.incomingSpeed, incoming.speed, .002f)
            val after = await("automix overlap ends and restores speed") { state ->
                state.uri == tracks[1].uri && state.engines.count { it.playing } == 1 &&
                    state.engines.single { it.playing }.let { it.speed == 1f && it.gain > .99f }
            }
            assertEquals(1f, after.engines.single { it.playing }.speed, .001f)
            playback.stop()
            assertStopped(stop = true)
            Log.i(TAG, "automix outMs=${outgoing.position} inMs=${incoming.position} speed=${incoming.speed} finalSpeed=1.0 rms=${overlap.engines.map { it.renderedLevel?.rms }} gains=${overlap.engines.map { it.gain }}")
        }
    }

    private suspend fun withProbe(automix: Boolean, block: suspend Probe.() -> Unit) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val original = withTimeout(5_000) { app.playbackTransitions.state.first { it.isReady } }.settings
        val originalEffects = withTimeout(5_000) { app.audioEffects.state.first { it.isReady } }.settings
        val playback = LocalPlayback(context, TransitionAudioProbeService::class.java)
        val probe = Probe(app, playback)
        val errors = launch { playback.errors.collect { probe.failure = it } }
        try {
            app.audioEffects.setSettings(EqualizerSettings())
            app.playbackTransitions.setSettings(PlaybackTransitionSettings(fadeInSeconds = 3, fadeOutSeconds = 3,
                crossfadeSeconds = 6, peakNormalizationEnabled = false, automixEnabled = automix))
            ActivityScenario.launch(MainActivity::class.java).use { probe.block() }
        } finally {
            errors.cancel()
            try { playback.clear() } finally {
                try { playback.release() } finally {
                    context.stopService(Intent(context, TransitionAudioProbeService::class.java))
                    app.playbackTransitions.setSettings(original)
                    app.audioEffects.setSettings(originalEffects)
                    assertTrue("Restore transition settings", app.playbackTransitions.persistNow())
                    assertTrue("Restore audio settings", app.audioEffects.persistNow())
                    withTimeout(5_000) {
                        while (withContext(Dispatchers.Main) { TransitionAudioProbeService.activeSession != null }) delay(40)
                    }
                }
            }
        }
    }

    private class Probe(val app: AppContainer, val playback: LocalPlayback) {
        var failure: String? = null
        suspend fun track(uri: String): SpotifyContent = app.repository.detail(SpotifyContent(uri.substringAfterLast(':'),
            uri, "", "", null, ContentKind.TRACK)).content

        suspend fun snapshot(): Snapshot = withContext(Dispatchers.Main) {
            val player = checkNotNull(TransitionAudioProbeService.activeSession).player as TransitionPlayer
            Snapshot(player.currentMediaItem?.mediaId, player.playbackState, player.audioEngines().mapIndexed { index, engine ->
                check(engine.playerError == null) { "Raw engine $index failed: ${engine.playerError?.errorCode}" }
                val position = engine.currentPosition
                val timeline = engine.currentTimeline
                val periodUid = engine.currentPeriodIndex.takeIf { it in 0 until timeline.periodCount }?.let(timeline::getUidOfPeriod)
                val format = engine.audioFormat
                val delaySamples = format?.encoderDelay ?: -1
                val paddingSamples = format?.encoderPadding ?: -1
                Engine(engine.currentMediaItem?.mediaId, position, engine.isPlaying, engine.playWhenReady,
                    engine.volume, engine.playbackParameters.speed, TransitionAudioProbeService.reading(index),
                    periodUid?.takeIf { delaySamples == 0 && paddingSamples == 0 }?.let {
                        TransitionAudioProbeService.readingAt(index, it, position * 1_000)
                    }, delaySamples, paddingSamples, periodUid != null && TransitionAudioProbeService.matchesPeriod(index, periodUid))
            })
        }

        suspend fun await(label: String, timeoutMs: Long = 30_000, predicate: (Snapshot) -> Boolean): Snapshot = try {
            withTimeout(timeoutMs) {
                var state = snapshot()
                while (!predicate(state)) {
                    check(failure == null) { "Playback failed during $label: $failure" }
                    delay(40)
                    state = snapshot()
                }
                state
            }
        } catch (_: TimeoutCancellationException) {
            val metrics = snapshot().engines.map {
                "pos=${it.position} playing=${it.playing} gain=${it.gain} rms=${it.level.rms} peak=${it.level.peak} " +
                    "windows=${it.level.windows} ageMs=${it.readingAgeMs()} " +
                    "renderedStartUs=${it.renderedLevel?.startUs} renderedEndUs=${it.renderedLevel?.endUs} renderedRms=${it.renderedLevel?.rms} " +
                    "encoderDelay=${it.delaySamples} encoderPadding=${it.paddingSamples} periodMatch=${it.periodMatch}"
            }
            throw AssertionError("$label timed out: $metrics")
        }

        suspend fun awaitAudibleOverlap(before: List<Long>, incomingUri: String): Snapshot {
            val observed = List(2) { mutableSetOf<Long>() }
            val startedAt = SystemClock.elapsedRealtime()
            var lastTraceAt = startedAt - 250
            return await("both decoders produce nonzero PCM during overlap") { state ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastTraceAt >= 250) {
                    lastTraceAt = now
                    state.engines.forEachIndexed { index, engine ->
                        // PCM is metered when processed, before AudioTrack consumes its queue.
                        // Keep this timestamp separate from the player's actual position; do
                        // not count old nonzero PCM as proof of currently audible output.
                        Log.d(TAG, "overlap-trace tMs=${now - startedAt} engine=$index " +
                            "incoming=${if (engine.uri == incomingUri) 1 else 0} " +
                            "current=${if (state.uri == incomingUri) 1 else 0} " +
                            "playing=${if (engine.playing) 1 else 0} wantsPlay=${if (engine.wantsPlay) 1 else 0} " +
                            "gain=${engine.gain} posMs=${engine.position} speed=${engine.speed} " +
                            "rms=${engine.level.rms} peak=${engine.level.peak} windows=${engine.level.windows} " +
                            "deltaWindows=${engine.level.windows - before[index]} samples=${engine.level.samples} " +
                            "rate=${engine.level.sampleRate} channels=${engine.level.channels} ageMs=${engine.readingAgeMs(now)} " +
                            "latestStartUs=${engine.level.startUs} latestEndUs=${engine.level.endUs} generation=${engine.level.generation} " +
                            "encoderDelay=${engine.delaySamples} encoderPadding=${engine.paddingSamples} " +
                            "periodMatch=${if (engine.periodMatch) 1 else 0} " +
                            "renderedWindow=${engine.renderedLevel?.windows ?: -1} renderedStartUs=${engine.renderedLevel?.startUs ?: -1} " +
                            "renderedEndUs=${engine.renderedLevel?.endUs ?: -1} renderedRms=${engine.renderedLevel?.rms ?: -1.0} " +
                            "renderedPeak=${engine.renderedLevel?.peak ?: -1.0}")
                    }
                }
                val both = state.uri == incomingUri && state.engines.withIndex().all { (index, engine) ->
                    engine.playing && engine.gain in .1f.. .9f && engine.audible() &&
                        checkNotNull(engine.renderedLevel).windows > before[index]
                }
                if (both) {
                    assertEquals("Final engine gains must sum to unity", 1f, state.gain, .03f)
                    state.engines.forEachIndexed { index, engine -> observed[index] += checkNotNull(engine.renderedLevel).windows }
                }
                observed.all { it.size >= 3 } && both
            }
        }

        suspend fun assertStopped(stop: Boolean) {
            var stopped = await("both raw decoders stop") { state ->
                state.engines.none { it.playing || it.wantsPlay } && (!stop || state.state == Player.STATE_IDLE)
            }
            // The renderer's pause acknowledgement can correct position after the main-loop
            // flags become false. Wait for two equal samples, then enforce a stationary clock.
            withTimeout(1_500) {
                var settled: Boolean
                do {
                    delay(250)
                    val current = snapshot()
                    assertTrue("Both decoders must remain stopped while position settles",
                        current.engines.none { it.playing || it.wantsPlay })
                    if (stop) assertEquals(Player.STATE_IDLE, current.state)
                    settled = stopped.engines.map { it.position } == current.engines.map { it.position }
                    stopped = current
                } while (!settled)
            }
            delay(300)
            val later = snapshot()
            assertEquals(stopped.engines.map { it.position }, later.engines.map { it.position })
            assertTrue(later.engines.none { it.playing || it.wantsPlay })
            if (stop) assertEquals(Player.STATE_IDLE, later.state)
            assertFalse(playback.snapshot().isPlaying)
            assertFalse(playback.snapshot().playWhenReady)
        }
    }

    private data class Snapshot(val uri: String?, val state: Int, val engines: List<Engine>) {
        val gain: Float get() = engines.filter { it.playing }.sumOf { it.gain.toDouble() }.toFloat()
    }
    private data class Engine(val uri: String?, val position: Long, val playing: Boolean, val wantsPlay: Boolean,
        val gain: Float, val speed: Float, val level: MediaTimeLevelMeter.Reading,
        val renderedLevel: MediaTimeLevelMeter.Reading?, val delaySamples: Int, val paddingSamples: Int, val periodMatch: Boolean) {
        fun readingAgeMs(now: Long = SystemClock.elapsedRealtime()): Long =
            if (level.elapsedMs == 0L) -1L else now - level.elapsedMs
        fun audible(): Boolean = renderedLevel?.let { it.rms > .001 && it.peak > .002 } == true
    }
    private val arguments get() = InstrumentationRegistry.getArguments()
    companion object { private const val TAG = "PlayLiveTransitions" }
}
