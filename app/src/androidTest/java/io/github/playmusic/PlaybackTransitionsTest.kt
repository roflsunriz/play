package io.github.playmusic

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.TransitionPlayer
import io.github.playmusic.testing.PlaybackTestService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Runs the production transition/session code against locally generated media. */
class PlaybackTransitionsTest {
    @Test fun backgroundControllerResumesAfterThePauseFadeReleasesAudioFocus(): Unit = runBlocking {
        assumeTrue("Background navigation is isolated-emulator-only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as PlayApplication).container.playbackTransitions
        val original = store.state.first { it.isReady }.settings
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        suspend fun <T> read(block: TransitionPlayer.() -> T): T = withContext(Dispatchers.Main) {
            (checkNotNull(PlaybackTestService.activeSession).player as TransitionPlayer).block()
        }
        suspend fun await(block: suspend () -> Boolean) = withTimeout(20_000) { while (!block()) delay(25) }
        val tracks = (1..2).map { index ->
            val id = index.toString().padStart(22, '0')
            SpotifyContent(id, "spotify:track:$id", "Synthetic $index", "", null, ContentKind.TRACK, durationMs = 12_000)
        }
        ActivityScenario.launch(PlaylistUiTestActivity::class.java).use {
            try {
                store.setSettings(PlaybackTransitionSettings(fadeInSeconds = 1, fadeOutSeconds = 1, crossfadeSeconds = 2, automixEnabled = false))
                playback.play(tracks)
                await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME"))
                    .use { output -> output.readBytes() }
                playback.pause()
                await { read { audioEngines().none { it.isPlaying || it.playWhenReady } } }
                delay(500)
                playback.resume()
                await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
                playback.next()
                await { read { currentMediaItem?.mediaId == tracks[1].uri && audioEngines().count { it.isPlaying } == 2 } }
                playback.stop()
                await { read { playbackState == Player.STATE_IDLE && audioEngines().none { it.isPlaying || it.playWhenReady } } }
            } finally {
                playback.clear(); playback.release()
                context.stopService(Intent(context, PlaybackTestService::class.java))
                store.setSettings(original); store.persistNow()
            }
        }
    }

    @Test fun replacingTheQueueMidSongCrossfadesWithoutSilencingBothDecoders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as PlayApplication).container.playbackTransitions
        val original = store.state.first { it.isReady }.settings
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        suspend fun <T> read(block: TransitionPlayer.() -> T): T = withContext(Dispatchers.Main) {
            (checkNotNull(PlaybackTestService.activeSession).player as TransitionPlayer).block()
        }
        suspend fun await(predicate: suspend () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(20) }
        fun track(id: String) = SpotifyContent(id, "spotify:track:$id", "Synthetic $id", "", null,
            ContentKind.TRACK, durationMs = 12_000)
        val first = listOf(track("a".padStart(22, '0')))
        val second = listOf(track("b".padStart(22, '0')))
        try {
            store.setSettings(PlaybackTransitionSettings(fadeInSeconds = 1, fadeOutSeconds = 1, crossfadeSeconds = 2, automixEnabled = false))
            playback.play(first)
            await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
            // Tapping another song replaces the queue while it is sounding. The trailing
            // play() must not silence both decoders and restart the fade: the outgoing
            // song keeps full gain until the overlap fades it out.
            playback.play(second)
            read {
                val total = audioEngines().sumOf { it.volume.toDouble() }.toFloat()
                assertTrue("Replacing the queue must not silence both decoders (total=$total)", total > .9f)
            }
            await { read { audioEngines().count { it.isPlaying } == 2 } }
            await { read { audioEngines().count { it.isPlaying } == 1 && audioEngines().any { it.isPlaying && it.volume > .99f } } }
            read { assertEquals(second.single().uri, currentMediaItem?.mediaId) }
            playback.stop()
            await { read { playbackState == Player.STATE_IDLE && audioEngines().none { it.isPlaying || it.playWhenReady } } }
        } finally {
            playback.clear(); playback.release()
            store.setSettings(original); store.persistNow()
        }
    }

    @Test fun seekingMidSongWithSeekCrossfadeOverlapsBothDecoders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as PlayApplication).container.playbackTransitions
        val original = store.state.first { it.isReady }.settings
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        suspend fun <T> read(block: TransitionPlayer.() -> T): T = withContext(Dispatchers.Main) {
            (checkNotNull(PlaybackTestService.activeSession).player as TransitionPlayer).block()
        }
        suspend fun await(predicate: suspend () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(20) }
        val id = "c".padStart(22, '0')
        val tracks = listOf(SpotifyContent(id, "spotify:track:$id", "Synthetic seek", "", null,
            ContentKind.TRACK, durationMs = 12_000))
        try {
            store.setSettings(PlaybackTransitionSettings(fadeInSeconds = 1, fadeOutSeconds = 1, crossfadeSeconds = 2,
                seekCrossfadeEnabled = true, seekCrossfadeSeconds = 2, automixEnabled = false))
            playback.play(tracks)
            await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
            playback.seek(8_000)
            await { read { audioEngines().count { it.isPlaying && it.volume in 0.05f..0.95f } == 2 } }
            read {
                val audible = audioEngines().filter { it.isPlaying }
                assertEquals(1f, audible.sumOf { it.volume.toDouble() }.toFloat(), .02f)
                assertEquals(tracks.single().uri, currentMediaItem?.mediaId)
            }
            await { read { audioEngines().count { it.isPlaying } == 1 && audioEngines().any { it.isPlaying && it.volume > .99f } } }
            read { assertTrue("Seek must land near its target, was ${currentPosition}", currentPosition in 8_000..10_500) }
            playback.stop()
            await { read { playbackState == Player.STATE_IDLE && audioEngines().none { it.isPlaying || it.playWhenReady } } }
        } finally {
            playback.clear(); playback.release()
            store.setSettings(original); store.persistNow()
        }
    }

    @Test fun fadesOverlapPauseCancelSeekAndSleepShareTheServiceTransport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as PlayApplication).container.playbackTransitions
        val original = store.state.first { it.isReady }.settings
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        suspend fun <T> read(block: TransitionPlayer.() -> T): T = withContext(Dispatchers.Main) {
            (checkNotNull(PlaybackTestService.activeSession).player as TransitionPlayer).block()
        }
        suspend fun await(predicate: suspend () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(20) }
        val tracks = (1..3).map { value ->
            val id = value.toString().padStart(22, '0')
            SpotifyContent(id, "spotify:track:$id", "Synthetic $value", "", null, ContentKind.TRACK, durationMs = 12_000)
        }
        try {
            store.setSettings(PlaybackTransitionSettings(fadeInSeconds = 1, fadeOutSeconds = 1, crossfadeSeconds = 2, automixEnabled = false))
            playback.play(tracks)
            await { read { audioEngines().any { it.isPlaying && it.volume in 0.1f..0.8f } } }
            await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
            playback.play(tracks) // Replacing a playing queue cannot leave the new queue at zero gain.
            await { read { audioEngines().any { it.isPlaying && it.volume > .99f } } }
            playback.pause()
            await { read { audioEngines().any { it.isPlaying && it.volume in 0.1f..0.8f } } }
            playback.resume() // Reversing a fade must not let its old callback stop the resumed song.
            delay(1_300)
            assertTrue(read { isPlaying && audioEngines().any { it.isPlaying && it.volume > .99f } })
            playback.seek(9_500)
            await { read { audioEngines().count { it.isPlaying && it.volume in 0.05f..0.95f } == 2 } }
            read {
                val audible = audioEngines().filter { it.isPlaying }
                assertEquals(1f, audible.sumOf { it.volume.toDouble() }.toFloat(), .02f)
                assertEquals(tracks[1].uri, currentMediaItem?.mediaId)
            }
            playback.pause()
            await { read { audioEngines().none { it.isPlaying || it.playWhenReady } } }
            val stopped = read { currentPosition }
            delay(300)
            assertEquals(stopped, read { currentPosition })
            playback.seekAndPlay(3_000)
            await { read { isPlaying && currentPosition in 3_000..5_000 } }
            playback.next()
            await { read { currentMediaItem?.mediaId == tracks[2].uri && audioEngines().count { it.isPlaying } == 2 } }
            read {
                pause()
                assertFalse(playWhenReady)
                assertTrue(hasActiveAudioOrPendingPlayback())
                setSleepFading(true); volume = 0f; stopImmediately(); setSleepFading(false)
            }
            assertFalse(read { audioEngines().any { it.isPlaying || it.playWhenReady } })
            assertEquals(Player.STATE_IDLE, read { playbackState })
        } finally {
            playback.clear(); playback.release()
            context.stopService(Intent(context, PlaybackTestService::class.java))
            store.setSettings(original); store.persistNow()
        }
    }
}
