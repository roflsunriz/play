package io.github.playmusic

import android.content.Intent
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.PlaybackService
import io.github.playmusic.testing.PlaybackTestService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackTest {
    @Test fun clearingDuringConnectionStopsTheExistingServiceQueue(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = LocalPlayback(context, PlaybackTestService::class.java)
        val reconnecting = LocalPlayback(context, PlaybackTestService::class.java)
        val track = SpotifyContent("0000000000000000000001", "spotify:track:0000000000000000000001",
            "Synthetic", "", null, ContentKind.TRACK)
        try {
            original.play(listOf(track))
            withTimeout(15_000) { while (!original.state.value.isPlaying) delay(50) }
            val connection = async(start = CoroutineStart.UNDISPATCHED) { reconnecting.snapshot() }
            reconnecting.clear()
            connection.await()
            withTimeout(15_000) {
                while (withContext(Dispatchers.Main) {
                    checkNotNull(PlaybackTestService.activeSession).player.mediaItemCount != 0
                }) delay(50)
            }
            assertFalse(reconnecting.snapshot().isPlaying)
            assertEquals(null, reconnecting.state.value.item)
        } finally {
            original.clear()
            original.release()
            reconnecting.release()
            context.stopService(Intent(context, PlaybackTestService::class.java))
        }
    }

    @Test fun productionServiceStartsWithItsRealCacheAndDrmConfiguration(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playback = LocalPlayback(context)
        try {
            val state = withTimeout(15_000) { playback.snapshot() }
            assertEquals(null, state.item)
            assertFalse(state.isPlaying)
        } finally {
            playback.clear()
            playback.release()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    @Test fun serviceControlsPauseSeekNextPreviousRepeatAndShuffle(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = LocalPlayback(context, PlaybackTestService::class.java)
        val tracks = listOf("0000000000000000000001", "0000000000000000000002").mapIndexed { index, id ->
            SpotifyContent(id, "spotify:track:$id", "Synthetic ${index + 1}", "Local verification", null,
                ContentKind.TRACK, durationMs = 12_000)
        }
        suspend fun <T> actualPlayer(read: Player.() -> T): T = withContext(Dispatchers.Main) {
            read(checkNotNull(PlaybackTestService.activeSession).player)
        }
        suspend fun awaitState(description: String = "Playback control", predicate: suspend () -> Boolean) {
            try {
                withTimeout(15_000) { while (!predicate()) delay(50) }
            } catch (timeout: TimeoutCancellationException) {
                val service = actualPlayer { "position=$currentPosition playing=$isPlaying ready=$playWhenReady state=$playbackState index=$currentMediaItemIndex" }
                val ui = player.state.value
                throw AssertionError("$description: service($service), UI(position=${ui.progressMs} playing=${ui.isPlaying} ready=${ui.playWhenReady})", timeout)
            }
        }
        try {
            player.play(tracks)
            awaitState { player.state.value.isPlaying && player.state.value.progressMs > 200 }
            assertEquals(tracks[0].uri, player.state.value.item?.uri)
            player.pause()
            awaitState { !player.state.value.isPlaying && !player.state.value.playWhenReady }
            // Controller commands update optimistically; wait for the service to acknowledge pause.
            awaitState { actualPlayer { !isPlaying && !playWhenReady } }
            var servicePausedAt = actualPlayer { currentPosition }
            // The audio renderer acknowledges pause after the main-thread player flag changes.
            awaitState {
                delay(250)
                val position = actualPlayer { currentPosition }
                val settled = kotlin.math.abs(position - servicePausedAt) < 50
                servicePausedAt = position
                settled
            }
            // MediaController estimates its position independently (MediaUtils.getUpdatedCurrentPositionMs).
            // Assert that both clocks stop, not that their estimates are sample-accurate.
            var pausedAt = player.snapshot().progressMs
            awaitState("Controller position must stop after pause") {
                delay(250)
                val position = player.snapshot().progressMs
                val settled = kotlin.math.abs(position - pausedAt) < 50
                pausedAt = position
                settled
            }
            delay(700)
            val afterPause = player.snapshot().progressMs
            assertTrue("Paused position changed from $pausedAt to $afterPause", kotlin.math.abs(afterPause - pausedAt) < 100)
            assertEquals(servicePausedAt, actualPlayer { currentPosition })
            player.seek(4_000)
            awaitState { player.state.value.progressMs in 3_900..4_100 }
            player.resume()
            awaitState { player.state.value.isPlaying }
            player.next()
            awaitState { player.state.value.item?.uri == tracks[1].uri }
            player.previous()
            awaitState { player.state.value.item?.uri == tracks[0].uri }
            player.setShuffle(true)
            assertTrue(player.snapshot().shuffle)
            player.setShuffle(false)
            player.setRepeat(RepeatMode.TRACK)
            assertEquals(RepeatMode.TRACK, player.snapshot().repeatMode)
            player.seek(11_700)
            awaitState { player.state.value.item?.uri == tracks[0].uri && player.state.value.progressMs < 2_000 }
            player.setRepeat(RepeatMode.CONTEXT)
            assertEquals(RepeatMode.CONTEXT, player.snapshot().repeatMode)
            player.next()
            awaitState { player.state.value.item?.uri == tracks[1].uri }
            player.seek(11_700)
            awaitState { player.state.value.item?.uri == tracks[0].uri }
            player.setRepeat(RepeatMode.OFF)
            assertEquals(RepeatMode.OFF, player.snapshot().repeatMode)
            player.next()
            awaitState { actualPlayer { currentMediaItemIndex == 1 } }
            player.seek(11_700)
            awaitState { actualPlayer { playbackState == Player.STATE_ENDED } }
            awaitState { !player.state.value.playWhenReady }
            player.resume()
            awaitState { actualPlayer { isPlaying && currentPosition in 100..2_000 } }
            player.clear()
            assertFalse(player.snapshot().isPlaying)
            assertEquals(null, player.state.value.item)
        } finally {
            player.clear()
            player.release()
            context.stopService(Intent(context, PlaybackTestService::class.java))
        }
    }
}
