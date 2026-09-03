package io.github.playmusic.data.playback

import android.media.MediaPlayer
import io.github.playmusic.data.cache.TrackCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class PreviewPlayer(private val trackCache: TrackCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null

    fun play(url: String) {
        stop()
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                trackCache.getOrFetch(url) { fetch(url) }
            }
            if (cached == null) return@launch
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("preview", ".mp3")
                temp.writeBytes(cached)
                temp.deleteOnExit()
                prepareAndStart(temp)
            }
        }
    }

    fun stop() {
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    private fun prepareAndStart(file: File) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.setOnCompletionListener { release() }
        mediaPlayer.prepare()
        mediaPlayer.start()
        player = mediaPlayer
    }

    private fun release() {
        player?.release()
        player = null
    }

    private fun fetch(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection()
        return connection.getInputStream().use { it.readBytes() }
    }

    fun destroy() {
        scope.cancel()
        stop()
    }
}