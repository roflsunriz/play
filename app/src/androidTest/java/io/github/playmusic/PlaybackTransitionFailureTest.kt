package io.github.playmusic

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import io.github.playmusic.data.playback.AutomixResolver
import io.github.playmusic.data.playback.AutomixTransition
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.TransitionPlayer
import io.github.playmusic.data.playback.LicenseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class PlaybackTransitionFailureTest {
    @Test fun pauseAndStopWhileTheSelectionBuffersStopBothDecodersWithinTheFade(): Unit = runBlocking {
        for (stop in listOf(false, true)) {
            val gate = java.util.concurrent.CountDownLatch(1)
            withSyntheticPlayer(AutomixResolver { _, _, _ -> null }, secondGate = gate) { player ->
                try {
                    await { player.audioEngines()[0].isPlaying && player.audioEngines()[0].volume > .99f }
                    withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
                    await { player.currentMediaItem?.mediaId == TRACK2 && player.playbackState == Player.STATE_BUFFERING }
                    withContext(Dispatchers.Main) {
                        assertTrue(player.audioEngines().any { it.currentMediaItem?.mediaId == TRACK1 && it.isPlaying })
                        if (stop) player.stop() else player.pause()
                    }
                    // The incoming decoder remains blocked. A one-second pause/stop fade must
                    // advance from the outgoing audio instead of waiting for license readiness.
                    withTimeout(2_500) {
                        while (withContext(Dispatchers.Main) {
                            player.audioEngines().any { it.isPlaying || it.playWhenReady }
                        }) delay(20)
                    }
                    withContext(Dispatchers.Main) {
                        assertFalse(player.playWhenReady)
                        assertFalse(player.audioEngines().any { it.isPlaying || it.playWhenReady })
                        if (stop) assertEquals(Player.STATE_IDLE, player.playbackState)
                    }
                    assertEquals("The incoming source must still be blocked when stopping finishes", 1L, gate.count)
                } finally { gate.countDown() }
            }
        }
    }

    @Test fun nextDuringAReplacementQueueUsesThePendingQueueAndKeepsTheOutgoingDecoder(): Unit = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        withSyntheticPlayer(AutomixResolver { _, _, _ -> null }, secondGate = gate,
            trackIds = listOf(TRACK1)) { player ->
            try {
                await { player.isPlaying }
                withContext(Dispatchers.Main) {
                    val template = checkNotNull(player.currentMediaItem)
                    player.setMediaItems(listOf(TRACK2, TRACK3).map { uri ->
                        template.buildUpon().setMediaId(uri).setUri(uri).build()
                    }, 0, 0)
                    player.prepare()
                    player.play()
                }
                await { player.currentMediaItem?.mediaId == TRACK2 && player.playbackState == Player.STATE_BUFFERING }
                withContext(Dispatchers.Main) {
                    player.seekToNextMediaItem()
                    assertEquals("Next belongs to the newly selected queue", TRACK3, player.currentMediaItem?.mediaId)
                    assertEquals(2, player.mediaItemCount)
                    assertEquals(TRACK2, player.getMediaItemAt(0).mediaId)
                    assertEquals(TRACK3, player.getMediaItemAt(1).mediaId)
                    assertTrue("Replacing the pending queue must preserve the song still sounding",
                        player.audioEngines().any { it.currentMediaItem?.mediaId == TRACK1 && it.isPlaying })
                }
                gate.countDown()
                await { player.currentMediaItem?.mediaId == TRACK3 && player.isPlaying }
            } finally { gate.countDown() }
        }
    }

    @Test fun nextWhileTheSelectionBuffersKeepsTheOutgoingDecoder(): Unit = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        withSyntheticPlayer(AutomixResolver { _, _, _ -> null }, secondGate = gate,
            trackIds = listOf(TRACK1, TRACK2, TRACK3)) { player ->
            await { player.isPlaying }
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            await { player.currentMediaItem?.mediaId == TRACK2 && player.playbackState == Player.STATE_BUFFERING }
            withContext(Dispatchers.Main) {
                player.seekToNextMediaItem()
                assertTrue("The audible outgoing decoder must survive replacement of a pending selection",
                    player.audioEngines().any { it.currentMediaItem?.mediaId == TRACK1 && it.isPlaying })
            }
            gate.countDown()
            await { player.currentMediaItem?.mediaId == TRACK3 && player.isPlaying }
        }
    }

    @Test fun incomingRateLimitKeepsTheAudibleOutgoingSong(): Unit = runBlocking {
        var abandoned = 0
        withSyntheticPlayer(AutomixResolver { _, _, _ -> null }, failSecond = true,
            abandoned = { abandoned++ }) { player ->
            await { player.isPlaying && player.volume > .99f }
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            await { abandoned == 1 }
            withContext(Dispatchers.Main) {
                assertEquals(TRACK1, player.currentMediaItem?.mediaId)
                assertTrue(player.isPlaying)
                assertTrue(player.playWhenReady)
                assertNull(player.playerError)
            }
            delay(300)
            withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertEquals(1, abandoned) }
        }
    }

    @Test fun schedulerStallInsideTheOutroKeepsAutomixAndAdvancesTheEntry(): Unit = runBlocking {
        withSyntheticPlayer(AutomixResolver { _, from, to ->
            AutomixTransition(from, to, 8_000, 2_000, 5_000, 1.02f)
        }, crossfadeSeconds = 5) { player ->
            await { player.audioEngines()[0].isPlaying && player.audioEngines()[0].volume > .99f &&
                player.audioEngines()[1].playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) {
                player.seekTo(7_850)
                // Deliberately reproduce application-looper delay while audio keeps advancing.
                // This is the observed 243ms scheduling failure, not a normal UI wait.
                Thread.sleep(450)
            }
            await { player.audioEngines().all { it.isPlaying && it.volume in .1f.. .9f } }
            withContext(Dispatchers.Main) {
                val incoming = player.audioEngines()[1]
                assertEquals(TRACK2, player.currentMediaItem?.mediaId)
                assertTrue("Delayed Automix lost its entry: ${incoming.currentPosition}", incoming.currentPosition >= 2_000)
                assertEquals(1.02f, incoming.playbackParameters.speed, .0001f)
            }
        }
    }

    @Test fun shortOutroClipsAutomixAndInvalidExitCueUsesTheNormalTransition(): Unit = runBlocking {
        withSyntheticPlayer(AutomixResolver { _, from, to ->
            AutomixTransition(from, to, 8_000, 2_000, 5_000, 1.02f)
        }, crossfadeSeconds = 5) { player ->
            await { player.audioEngines()[0].isPlaying && player.audioEngines()[1].playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { player.seekTo(7_700) }
            await { player.audioEngines().all { it.isPlaying && it.volume in .1f.. .9f } }
            withContext(Dispatchers.Main) {
                assertEquals(TRACK2, player.currentMediaItem?.mediaId)
                assertTrue("Short-outro Automix lost its entry: ${player.currentPosition}", player.currentPosition >= 2_000)
                assertEquals(1.02f, player.audioEngines()[1].playbackParameters.speed, .0001f)
            }
            await { player.audioEngines()[0].playbackState == Player.STATE_IDLE && player.audioEngines()[1].volume > .99f }
            withContext(Dispatchers.Main) {
                // Four available seconds at speed 1.02 advance the incoming cue to about 6080ms.
                assertTrue(player.currentPosition in 5_900..6_500)
                assertEquals(1f, player.audioEngines()[1].playbackParameters.speed, 0f)
            }
        }
        withSyntheticPlayer(AutomixResolver { _, from, to ->
            AutomixTransition(from, to, 12_001, 2_000, 5_000, 1.02f)
        }, crossfadeSeconds = 5) { player ->
            await { player.audioEngines()[0].isPlaying && player.audioEngines()[1].playbackState == Player.STATE_READY &&
                player.audioEngines()[1].currentPosition == 0L }
            withContext(Dispatchers.Main) { player.seekTo(6_700) }
            await { player.audioEngines().all { it.isPlaying && it.volume in .1f.. .9f } }
            withContext(Dispatchers.Main) {
                assertEquals(TRACK2, player.currentMediaItem?.mediaId)
                assertTrue(player.currentPosition < 2_000)
                assertEquals(1f, player.audioEngines()[1].playbackParameters.speed, 0f)
            }
        }
    }

    @Test fun seekingWithinTheOutgoingSongKeepsThePreparedEntryCueAndDrmPlayer(): Unit = runBlocking {
        var calls = 0
        withSyntheticPlayer(AutomixResolver { _, from, to ->
            calls++
            AutomixTransition(from, to, 8_000, 2_000, 2_000, 1.02f)
        }) { player ->
            await {
                player.audioEngines().let { engines -> engines[0].isPlaying && engines[0].volume > .99f &&
                    engines[1].playbackState == Player.STATE_READY && engines[1].currentPosition >= 2_000 }
            }
            withContext(Dispatchers.Main) {
                player.seekTo(5_000)
                assertEquals(1, calls)
                assertEquals(Player.STATE_READY, player.audioEngines()[1].playbackState)
                assertEquals(2_000L, player.audioEngines()[1].currentPosition)
                player.seekTo(7_800)
                assertEquals(1, calls)
                assertEquals(Player.STATE_READY, player.audioEngines()[1].playbackState)
            }
            await { player.audioEngines().all { it.isPlaying && it.volume in .1f.. .9f } }
            withContext(Dispatchers.Main) {
                assertEquals(TRACK2, player.currentMediaItem?.mediaId)
                val incoming = player.audioEngines()[1]
                assertTrue(incoming.currentPosition in 2_000..4_100)
                assertEquals(1.02f, incoming.playbackParameters.speed, .0001f)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun automixTransportFailureWarnsOnceAndKeepsTheSongPlaying(): Unit = runBlocking {
        var warnings = 0
        withSyntheticPlayer(AutomixResolver { _, _, _ -> error("Synthetic metadata failure") }, { warnings++ }) { player ->
            await { warnings == 1 && player.audioEngines()[0].isPlaying && player.currentPosition >= 1_500 }
            withContext(Dispatchers.Main) {
                assertNull(player.playerError)
                assertTrue(player.playWhenReady)
                assertTrue(player.isPlaying)
                assertEquals(1, warnings)
            }
        }
    }

    @Test fun foregroundFailureAndTimeoutPublishAValidIdleErrorAndStopBothEngines(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failures: List<suspend () -> Unit> = listOf(
            { throw IllegalStateException("Synthetic foreground failure") },
            { withTimeout(1) { delay(100) } },
        )
        for (failure in failures) {
            val player = withContext(Dispatchers.Main) {
                val first = ExoPlayer.Builder(context).build()
                val second = ExoPlayer.Builder(context).build()
                first.setMediaSource(SilenceMediaSource.Factory().setDurationUs(10_000_000).createMediaSource())
                first.prepare()
                TransitionPlayer(context, first, second, { PlaybackTransitionSettings() }, beforeAudioFocus = failure)
            }
            try {
                withTimeout(10_000) {
                    while (withContext(Dispatchers.Main) { player.playbackState != Player.STATE_READY }) delay(20)
                }
                withContext(Dispatchers.Main) { player.play() }
                withTimeout(5_000) {
                    while (withContext(Dispatchers.Main) { player.playerError == null }) delay(20)
                }
                withContext(Dispatchers.Main) {
                    assertNotNull(player.playerError)
                    assertEquals(Player.STATE_IDLE, player.playbackState)
                    assertFalse(player.isLoading)
                    assertFalse(player.isPlaying)
                    assertFalse(player.playWhenReady)
                    assertFalse(player.audioEngines().any { it.isPlaying || it.playWhenReady })
                }
            } finally { withContext(Dispatchers.Main) { player.release() } }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(20)
    }

    private suspend fun withSyntheticPlayer(resolver: AutomixResolver, warning: () -> Unit = {}, crossfadeSeconds: Int = 2,
        failSecond: Boolean = false, abandoned: () -> Unit = {},
        secondGate: java.util.concurrent.CountDownLatch? = null, trackIds: List<String> = listOf(TRACK1, TRACK2),
        block: suspend (TransitionPlayer) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = ByteBuffer.allocate(8_000 * 12 * 2 + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            val size = capacity() - 44
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array()
        ActivityScenario.launch(PlaylistUiTestActivity::class.java).use {
            val player = withContext(Dispatchers.Main) {
                fun engine() = ExoPlayer.Builder(context).setMediaSourceFactory(
                    ProgressiveMediaSource.Factory(DataSource.Factory {
                        val source = ByteArrayDataSource(wav)
                        object : DataSource by source {
                            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                                if (dataSpec.uri.toString() == TRACK2) secondGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                if (failSecond && dataSpec.uri.toString() == TRACK2) {
                                    throw LicenseHttpClient.LicenseHttpException(LicenseHttpClient.Failure.HTTP, 429)
                                }
                                return source.open(dataSpec)
                            }
                        }
                    }).setLoadErrorHandlingPolicy(io.github.playmusic.data.playback.DrmRetryPolicy)).build()
                TransitionPlayer(context, engine(), engine(),
                    { PlaybackTransitionSettings(fadeInSeconds = 1, fadeOutSeconds = 1, crossfadeSeconds = crossfadeSeconds) },
                    resolver, onAutomixFailure = warning, onSwitchAbandoned = abandoned).apply {
                    setMediaItems(trackIds.map { uri -> MediaItem.Builder().setMediaId(uri).setUri(uri)
                        .setMimeType(MimeTypes.AUDIO_WAV).setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                            putString("contextUri", "spotify:playlist:0000000000000000000001"); putLong("durationMs", 12_000)
                        }).build()).build() })
                    prepare(); play()
                }
            }
            try { block(player) } finally { withContext(Dispatchers.Main) { player.release() } }
        }
    }

    companion object {
        private const val TRACK1 = "spotify:track:0000000000000000000001"
        private const val TRACK2 = "spotify:track:0000000000000000000002"
        private const val TRACK3 = "spotify:track:0000000000000000000003"
    }
}
