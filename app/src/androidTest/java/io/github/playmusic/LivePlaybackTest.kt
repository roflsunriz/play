package io.github.playmusic

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Playback-state/control test with muted output. AudioOutputTest separately checks decoded sound. */
class LivePlaybackTest {
    @Test fun protectedTrackControlsAndTimelineReachTheEnd(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlayback") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        check(app.sessionStore.loadSession()?.refreshToken != null) { "Complete normal sign-in first" }
        val trackUri = InstrumentationRegistry.getArguments().getString("trackUri") ?: TRACK
        val item = SpotifyContent(trackUri.substringAfterLast(':'), trackUri, "", "", null, ContentKind.TRACK)
        val detail = app.repository.detail(item).content
        assertTrue(detail.durationMs > 120_000)
        var failure: String? = null
        val errors = launch { app.localPlayback.errors.collect { failure = it } }
        var controller: MediaController? = null
        var volume = 1f
        suspend fun awaitState(label: String, timeout: Long = 45_000, predicate: suspend () -> Boolean) {
            withTimeout(timeout) {
                while (!predicate()) {
                    check(failure == null) { "$label failed: $failure" }
                    delay(100)
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller = withContext(Dispatchers.Main) {
                    val pending = MediaController.Builder(context, SessionToken(context,
                        ComponentName(context, PlaybackService::class.java))).buildAsync()
                    suspendCancellableCoroutine { continuation ->
                        pending.addListener({
                            if (continuation.isActive) try { continuation.resume(pending.get()) }
                            catch (error: Exception) { continuation.resumeWithException(error) }
                        }, ContextCompat.getMainExecutor(context))
                        continuation.invokeOnCancellation { MediaController.releaseFuture(pending) }
                    }
                }
                withContext(Dispatchers.Main) {
                    volume = checkNotNull(controller).volume
                    checkNotNull(controller).volume = 0f
                }
                app.localPlayback.play(listOf(detail))
                awaitState("Start protected audio") {
                    app.localPlayback.state.value.isPlaying && app.localPlayback.state.value.progressMs > 2_000
                }
                Log.i(TAG, "protected playback started, duration=${app.localPlayback.state.value.durationMs}")
                app.localPlayback.seek(90_000)
                awaitState("Seek beyond a preview") {
                    app.localPlayback.state.value.isPlaying && app.localPlayback.state.value.progressMs in 90_000..100_000
                }
                app.localPlayback.pause()
                awaitState("Pause protected audio") { !app.localPlayback.state.value.playWhenReady }
                app.localPlayback.resume()
                awaitState("Resume protected audio") { app.localPlayback.state.value.isPlaying }
                assertEquals(trackUri, app.localPlayback.state.value.item?.uri)
                if (InstrumentationRegistry.getArguments().getString("fullTrack") == "true") {
                    app.localPlayback.seek(0)
                    awaitState("Restart full track") {
                        app.localPlayback.state.value.isPlaying && app.localPlayback.state.value.progressMs < 5_000
                    }
                    Log.i(TAG, "full track verification started")
                    awaitState("Full track completion", detail.durationMs + 60_000) {
                        withContext(Dispatchers.Main) { controller?.playbackState == Player.STATE_ENDED }
                    }
                } else {
                    app.localPlayback.seek(detail.durationMs - 1_500)
                    awaitState("Track end") {
                        withContext(Dispatchers.Main) { controller?.playbackState == Player.STATE_ENDED }
                    }
                }
                Log.i(TAG, "protected track reached end")
                val secondUri = "spotify:track:2mvffzYUJ9Ld9xhsF5DUjU"
                val second = app.repository.detail(SpotifyContent(secondUri.substringAfterLast(':'), secondUri,
                    "", "", null, ContentKind.TRACK)).content
                app.localPlayback.play(listOf(detail, second))
                awaitState("First queue item") { app.localPlayback.state.value.isPlaying && app.localPlayback.state.value.progressMs > 500 }
                app.localPlayback.next()
                awaitState("Next protected item") {
                    app.localPlayback.state.value.item?.uri == secondUri && app.localPlayback.state.value.isPlaying &&
                        app.localPlayback.state.value.progressMs in 500..5_000
                }
                app.localPlayback.previous()
                awaitState("Previous protected item") {
                    app.localPlayback.state.value.item?.uri == trackUri && app.localPlayback.state.value.isPlaying &&
                        app.localPlayback.state.value.progressMs in 500..5_000
                }
                app.localPlayback.setRepeat(RepeatMode.TRACK)
                app.localPlayback.seek(detail.durationMs - 1_200)
                awaitState("Repeat protected item") {
                    app.localPlayback.state.value.item?.uri == trackUri && app.localPlayback.state.value.isPlaying &&
                        app.localPlayback.state.value.progressMs in 500..4_000
                }
                app.localPlayback.setRepeat(RepeatMode.CONTEXT)
                app.localPlayback.next()
                awaitState("Last queue item") { app.localPlayback.state.value.item?.uri == secondUri && app.localPlayback.state.value.isPlaying }
                app.localPlayback.seek(second.durationMs - 1_200)
                awaitState("Repeat protected queue") {
                    app.localPlayback.state.value.item?.uri == trackUri && app.localPlayback.state.value.isPlaying &&
                        app.localPlayback.state.value.progressMs in 500..4_000
                }
                app.localPlayback.setShuffle(true)
                assertTrue(app.localPlayback.snapshot().shuffle)
                app.localPlayback.setShuffle(false)
                app.localPlayback.setRepeat(RepeatMode.OFF)
                Log.i(TAG, "protected next/previous/repeat/shuffle verified")
            }
        } finally {
            errors.cancel()
            app.localPlayback.clear()
            withContext(Dispatchers.Main) { controller?.volume = volume; controller?.release() }
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    companion object {
        private const val TAG = "PlayLivePlaybackCheck"
        private const val TRACK = "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
    }
}
