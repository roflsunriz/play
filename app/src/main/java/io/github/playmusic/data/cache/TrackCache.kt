package io.github.playmusic.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class TrackCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val directory = File(context.cacheDir, TRACK_CACHE_DIR)

    init {
        rebuildIfCorrupt()
    }

    suspend fun getOrFetch(url: String, fetcher: suspend (String) -> ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            val key = cacheKey(url)
            val file = File(directory, key)
            if (file.isFile && file.length() > 0) {
                touch(file)
                return@withContext file.readBytes()
            }
            val data = runCatching { fetcher(url) }.getOrNull() ?: return@withContext null
            if (data.isEmpty()) return@withContext null
            directory.mkdirs()
            File(directory, key).writeBytes(data)
            evictIfNeeded()
            data
        }

    fun has(url: String): Boolean {
        val file = File(directory, cacheKey(url))
        return file.isFile && file.length() > 0
    }

    fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun evictIfNeeded() {
        while (sizeBytes() > maxBytes) {
            val oldest = directory.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.minByOrNull { it.lastModified() }
                ?: return
            oldest.delete()
        }
    }

    private fun rebuildIfCorrupt() {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.length() == 0L) file.delete()
        }
    }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".audio"
    }

    private companion object {
        const val TRACK_CACHE_DIR = "spotify_track_cache"
        const val DEFAULT_MAX_BYTES = 100L * 1024L * 1024L
    }
}