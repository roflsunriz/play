package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only, opt-in. Never prints or stores lyrics, account data, or tokens. */
class LyricsAccountTest {
    @Test fun suppliedTrackReturnsTimedLyrics(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveLyrics") == "true" && args.getString("trackUri") != null)
        val uri = requireNotNull(args.getString("trackUri"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val lyrics = app.lyricsApi.lyrics(uri)
        assertNotNull("Selected track must have provider lyrics", lyrics)
        val timed = requireNotNull(lyrics)
        assertEquals(uri, timed.trackUri)
        assertTrue("Selected track must provide timing", timed.isTimeSynced)
        assertTrue("Expected nonempty provider lyrics", timed.lines.isNotEmpty())
        assertTrue("Provider attribution must be kept", timed.providerDisplayName.isNotBlank())
        assertTrue(timed.lines.zipWithNext().all { (a, b) -> requireNotNull(a.startTimeMs) <= requireNotNull(b.startTimeMs) })
        Log.i("PlayLyricsCheck", "sync=${timed.syncType} lines=${timed.lines.size} firstMs=${timed.lines.first().startTimeMs} lastMs=${timed.lines.last().startTimeMs}")
    }
}
