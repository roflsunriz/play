package io.github.playmusic.data.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Controls the app's media service; no external playback device is required. */
class LocalPlayback(context: Context, private val serviceClass: Class<out MediaSessionService> = PlaybackService::class.java) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(Playback())
    private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val state = mutableState.asStateFlow()
    val errors = mutableErrors.asSharedFlow()
    internal fun reportWarning(message: String) { mutableErrors.tryEmit(message) }
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null

    suspend fun snapshot(): Playback = withContext(Dispatchers.Main.immediate) {
        publish(connectedController())
        mutableState.value
    }

    suspend fun play(items: List<MusicContent>, index: Int = 0, startPositionMs: Long = 0,
        contextUri: String? = null): Unit = withContext(Dispatchers.Main.immediate) {
        require(items.isNotEmpty() && index in items.indices)
        require(items.all { it.kind == ContentKind.TRACK && it.uri.matches(Regex("spotify:track:[A-Za-z0-9]{22}")) })
        connectedController().apply {
            setMediaItems(items.map { mediaItem(it, contextUri) }, index, startPositionMs.coerceAtLeast(0))
            prepare()
            play()
            publish(this)
        }
    }

    suspend fun resume() = command {
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
    suspend fun pause() = command { pause() }
    suspend fun stop() = command { stop() }
    suspend fun next() = command { if (hasNextMediaItem()) seekToNextMediaItem() }
    suspend fun previous() = command { seekToPrevious() }
    suspend fun seek(positionMs: Long) = command {
        val upper = duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: Long.MAX_VALUE
        seekTo(positionMs.coerceIn(0, upper))
    }
    suspend fun seekAndPlay(positionMs: Long) = command {
        val upper = duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: Long.MAX_VALUE
        seekTo(positionMs.coerceIn(0, upper))
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
    suspend fun setShuffle(enabled: Boolean) = command { shuffleModeEnabled = enabled }
    suspend fun setRepeat(mode: RepeatMode) = command {
        repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.CONTEXT -> Player.REPEAT_MODE_ALL
            RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
        }
    }

    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        try {
            if (controller != null || connection != null) connectedController().apply { stop(); clearMediaItems() }
        } finally {
            progressJob?.cancel()
            mutableState.value = Playback()
        }
    }

    suspend fun release() = withContext(Dispatchers.Main.immediate) {
        progressJob?.cancel()
        connection?.let(MediaController::releaseFuture) ?: controller?.release()
        controller = null
        connection = null
        scope.cancel()
    }

    private suspend fun command(action: MediaController.() -> Unit): Unit = withContext(Dispatchers.Main.immediate) {
        connectedController().apply { action(); publish(this) }
    }

    private suspend fun connectedController(): MediaController {
        controller?.let { return it }
        val pending = connection ?: MediaController.Builder(context,
            SessionToken(context, ComponentName(context, serviceClass)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(mediaController: MediaController) {
                    if (controller === mediaController) {
                        controller = null
                        connection = null
                        progressJob?.cancel()
                        mutableState.value = mutableState.value.copy(isPlaying = false, playWhenReady = false, isBuffering = false)
                    }
                }
            }).buildAsync().also { connection = it }
        val result = suspendCancellableCoroutine { continuation ->
            pending.addListener({
                if (continuation.isActive) try { continuation.resume(pending.get()) }
                catch (exception: Exception) { connection = null; continuation.resumeWithException(exception) }
            }, ContextCompat.getMainExecutor(context))
        }
        if (controller == null) {
            controller = result
            result.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publish(result) }
                override fun onPlayerError(error: PlaybackException) { mutableErrors.tryEmit(error.errorCodeName) }
            })
        }
        publish(result)
        return result
    }

    private fun publish(player: MediaController) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        mutableState.value = Playback(
            item = if (item == null || metadata == null) null else MusicContent(
                id = item.mediaId.substringAfterLast(':'), uri = item.mediaId,
                title = metadata.title?.toString().orEmpty(), subtitle = metadata.artist?.toString().orEmpty(),
                imageUrl = metadata.artworkUri?.toString(), kind = ContentKind.TRACK,
                durationMs = metadata.extras?.getLong("durationMs") ?: 0,
                albumUri = metadata.extras?.getString("albumUri"), albumTitle = metadata.albumTitle?.toString(),
            ),
            progressMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: metadata?.extras?.getLong("durationMs") ?: 0,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            shuffle = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.TRACK
                Player.REPEAT_MODE_ALL -> RepeatMode.CONTEXT
                else -> RepeatMode.OFF
            },
        )
        if (player.isPlaying && progressJob?.isActive != true) {
            progressJob = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    delay(200)
                    controller?.let { mutableState.value = mutableState.value.copy(progressMs = it.currentPosition.coerceAtLeast(0)) }
                }
            }
        } else if (!player.isPlaying) { progressJob?.cancel(); progressJob = null }
    }

    private fun mediaItem(content: MusicContent, contextUri: String?): MediaItem = MediaItem.Builder()
        .setMediaId(content.uri).setUri(content.uri).setMimeType(MimeTypes.AUDIO_MP4)
        .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(StreamingApiClient.LICENSE_URL).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(content.title).setArtist(content.subtitle)
            .setAlbumTitle(content.albumTitle).setArtworkUri(content.imageUrl?.toUri())
            .setExtras(Bundle().apply { putLong("durationMs", content.durationMs); putString("albumUri", content.albumUri)
                putString("contextUri", contextUri) }).build())
        .build()
}
