package io.github.playmusic.data.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(UnstableApi::class)
class TrackCacheTest {
    @Test fun cachesOpaqueAudioBytesAndReusesThemAfterTheSignedUrlChanges() = withCache(1024) { cache ->
        var fetches = 0
        val bytes = ByteArray(300) { (it * 17).toByte() }
        val factory = factory(cache) { fetches++; bytes }
        assertArrayEquals(bytes, read(factory, "file-a", "https://audio-fa.scdn.co/audio/a?token=first"))
        assertArrayEquals(bytes, read(factory, "file-a", "https://audio-fa.scdn.co/audio/a?token=second"))
        assertEquals(1, fetches)
        assertEquals(300L, cache.cacheSpace)
    }

    @Test fun preservesRangeReadsWithoutDownloadingTheCachedRangeAgain() = withCache(1024) { cache ->
        var fetches = 0
        val bytes = ByteArray(700) { it.toByte() }
        val factory = factory(cache) { fetches++; bytes }
        read(factory, "file-a")
        assertArrayEquals(bytes.copyOfRange(101, 250), read(factory, "file-a", position = 101, length = 149))
        assertEquals(1, fetches)
    }

    @Test fun reportsDownloadFailureAndLeavesNoUsableCacheEntry() = withCache(1024) { cache ->
        val factory = factory(cache) { throw IOException("Synthetic network failure") }
        assertThrows(IOException::class.java) { read(factory, "failed") }
        assertTrue(cache.getCachedSpans("failed").isEmpty())
        assertEquals(0L, cache.cacheSpace)
    }

    @Test fun evictsOldContentWithoutExceedingTheConfiguredCapacity() = withCache(1024) { cache ->
        val factory = factory(cache) { ByteArray(300) }
        for (key in listOf("a", "b", "c", "d")) read(factory, key)
        assertTrue(cache.cacheSpace <= 1024)
        assertTrue(cache.getCachedSpans("a").isEmpty())
        assertFalse(cache.getCachedSpans("d").isEmpty())
    }

    private fun factory(cache: SimpleCache, fetch: () -> ByteArray): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DataSource.Factory {
            object : DataSource {
                private var source: ByteArrayDataSource? = null
                override fun addTransferListener(listener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = ByteArrayDataSource(fetch()).also { source = it }.open(dataSpec)
                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(source).read(buffer, offset, length)
                override fun getUri(): Uri? = source?.uri
                override fun close() { source?.close() }
            }
        })

    private fun read(factory: DataSource.Factory, key: String, url: String = "https://audio-fa.scdn.co/audio/$key",
        position: Long = 0, length: Long = -1): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(url).setKey(key).setPosition(position).setLength(length).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally { source.close() }
    }

    private fun withCache(limit: Long, block: (SimpleCache) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("audio-cache-test-${java.util.UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(limit), database)
        try { block(cache) } finally {
            cache.release()
            database.close()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
