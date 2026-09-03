package io.github.playmusic.data.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrackCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cachesAndReturnsFetchedData() = runBlocking {
        val cache = TrackCache(context)
        cache.clear()
        val data = cache.getOrFetch("https://example.com/audio/1") { "audio-bytes".toByteArray() }

        assertNotNull(data)
        assertEquals("audio-bytes", String(data!!))
        assertTrue(cache.has("https://example.com/audio/1"))
    }

    @Test
    fun skipsFetcherWhenCacheIsWarm() = runBlocking {
        val cache = TrackCache(context)
        cache.clear()
        cache.getOrFetch("https://example.com/audio/2") { "first".toByteArray() }

        var fetches = 0
        val data = cache.getOrFetch("https://example.com/audio/2") {
            fetches++
            "second".toByteArray()
        }

        assertEquals(0, fetches)
        assertEquals("first", String(data!!))
    }

    @Test
    fun returnsNullWhenFetchFailsAndDoesNotCache() = runBlocking {
        val cache = TrackCache(context)
        cache.clear()
        val data = cache.getOrFetch("https://example.com/audio/3") { error("network down") }

        assertNull(data)
        assertFalse(cache.has("https://example.com/audio/3"))
    }

    @Test
    fun evictsOldestEntriesWhenOverLimit() = runBlocking {
        val cache = TrackCache(context, maxBytes = 1024)
        cache.clear()
        val cacheDir = File(context.cacheDir, "spotify_track_cache")
        // Each entry is 300 bytes; three fill past the 1024-byte limit.
        cache.getOrFetch("https://example.com/audio/a") { ByteArray(300) }
        Thread.sleep(10)
        cache.getOrFetch("https://example.com/audio/b") { ByteArray(300) }
        Thread.sleep(10)
        cache.getOrFetch("https://example.com/audio/c") { ByteArray(300) }
        Thread.sleep(10)
        cache.getOrFetch("https://example.com/audio/d") { ByteArray(300) }

        assertTrue(cache.sizeBytes() <= 1024 + 300)
        assertFalse(cache.has("https://example.com/audio/a"))
        assertTrue(cache.has("https://example.com/audio/d"))
    }
}