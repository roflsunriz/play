package io.github.playmusic.testing

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import io.github.playmusic.data.playback.PlaybackService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug-only source for testing the real service/controller with locally generated silence. */
@OptIn(UnstableApi::class)
class PlaybackTestService : PlaybackService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        super.onGetSession(controllerInfo).also { activeSession = it }

    override fun onDestroy() {
        activeSession = null
        super.onDestroy()
    }

    override val cacheDirectoryName = "playback_test_cache"
    override fun createMediaSources(mediaCache: SimpleCache): MediaSource.Factory =
        ProgressiveMediaSource.Factory(DataSource.Factory { ByteArrayDataSource(silence()) })
            .setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }

    private fun silence(): ByteArray {
        val samples = 8_000 * 12
        val size = samples * 2
        return ByteBuffer.allocate(size + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array()
    }

    companion object {
        // Read only from the player's application looper in instrumentation tests.
        var activeSession: MediaSession? = null
            private set
    }
}
