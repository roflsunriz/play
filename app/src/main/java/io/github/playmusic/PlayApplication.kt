package io.github.playmusic

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toPath

class PlayApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("spotify_artwork_cache").absolutePath.toPath())
                .maxSizeBytes(ARTWORK_CACHE_LIMIT_BYTES)
                .build()
        }
        .build()

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.20
        const val ARTWORK_CACHE_LIMIT_BYTES = 128L * 1024L * 1024L
    }
}
