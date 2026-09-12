package io.github.playmusic.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Resolves an authenticated track lazily and caches only its encrypted media bytes. */
@OptIn(UnstableApi::class)
internal class CachedAudioDataSource(
    private val api: StreamingApiClient,
    private val cacheFactory: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null
    private var originalUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        originalUri = dataSpec.uri
        val audio = try { runBlocking { api.resolve(dataSpec.uri.toString()) } }
        catch (exception: Exception) { throw IOException("Audio information could not be loaded", exception) }
        var failure: IOException? = null
        for (url in audio.urls.take(3)) {
            val source = cacheFactory.createDataSource()
            listeners.forEach(source::addTransferListener)
            try {
                val length = source.open(dataSpec.buildUpon().setUri(url).setKey("audio:${audio.fileId}").build())
                current = source
                return length
            } catch (exception: IOException) {
                failure = exception
                runCatching(source::close)
            }
        }
        throw IOException("Audio download failed", failure)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)
    override fun getUri(): Uri? = originalUri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
    override fun close() { current?.close(); current = null; originalUri = null }
}
