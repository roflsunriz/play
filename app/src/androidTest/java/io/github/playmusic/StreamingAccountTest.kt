package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only resolution checks for saved items explicitly selected by the operator. */
class StreamingAccountTest {
    @Test fun playlistTrackResolves(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveStreaming") == "true" && args.getString("playlistName") != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val playlist = app.repository.library(ContentKind.PLAYLIST)
            .single { it.title == args.getString("playlistName") }
        val tracks = app.repository.detail(playlist).tracks.filter {
            it.title.contains(requireNotNull(args.getString("trackTitle")), ignoreCase = true)
        }
        assertTrue("Selected playlist must contain the requested track", tracks.isNotEmpty())
        for (track in tracks) {
            Log.i(TAG, "selected ${track.title} ${track.subtitle} ${track.uri}")
            app.streamingApi.resolve(track.uri)
        }
    }

    @Test fun albumTracksResolve(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveStreaming") == "true" && args.getString("albumQuery") != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val query = requireNotNull(args.getString("albumQuery"))
        val album = app.repository.library(ContentKind.ALBUM)
            .single { it.title.contains(query, ignoreCase = true) }
        val tracks = app.repository.detail(album).tracks
        assertTrue("Album must contain tracks", tracks.isNotEmpty())
        val failures = mutableListOf<String>()
        for (track in tracks) {
            try {
                app.streamingApi.resolve(track.uri)
                Log.i(TAG, "resolved ${track.title} ${track.uri}")
            } catch (error: Exception) {
                val reason = error.javaClass.simpleName + ": " + error.message
                Log.i(TAG, "failed ${track.title} ${track.uri} $reason")
                failures += reason
            }
        }
        assertTrue("Resolution failed: $failures", failures.isEmpty())
    }

    companion object { private const val TAG = "PlayStreamingCheck" }
}
