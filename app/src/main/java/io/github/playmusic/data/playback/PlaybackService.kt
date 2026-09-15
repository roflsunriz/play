package io.github.playmusic.data.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.playmusic.MainActivity
import io.github.playmusic.PlayApplication
import io.github.playmusic.BuildConfig
import io.github.playmusic.R
import io.github.playmusic.data.audio.EqualizerAudioProcessor
import io.github.playmusic.data.audio.PeakNormalizerAudioProcessor
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

@OptIn(UnstableApi::class)
open class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    private var foregroundReady: CompletableDeferred<Unit>? = null
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        val database = StandaloneDatabaseProvider(this).also { this.database = it }
        val mediaCache = SimpleCache(cacheDir.resolve(cacheDirectoryName),
            LeastRecentlyUsedCacheEvictor(MUSIC_CACHE_BYTES), database).also { cache = it }
        val player = TransitionPlayer(this, createEngine(mediaCache), createEngine(mediaCache),
            { (application as PlayApplication).container.playbackTransitions.state.value.settings }, createAutomixResolver(),
            beforeAudioFocus = ::awaitPlaybackForeground,
            onAutomixFailure = {
                (application as PlayApplication).container.localPlayback.reportWarning(getString(R.string.playback_automix_failed))
            })
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setId(javaClass.simpleName).setSessionActivity(launch).build()
        player.audioEngines().forEach { engine -> engine.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!destroying) triggerNotificationUpdate() }
        }) }
        (application as PlayApplication).container.sleepTimer.attach(player)
    }

    protected open fun createAutomixResolver(): AutomixResolver =
        (application as PlayApplication).container.let { container ->
            AutomixApiClient(container.sessionManager, { container.playbackTransitions.state.value.settings })
        }

    private fun createEngine(mediaCache: SimpleCache): ExoPlayer =
        ExoPlayer.Builder(this, createRenderers()).setMediaSourceFactory(createMediaSources(mediaCache)).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder().setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build(),
            ).build()
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }

    protected open val cacheDirectoryName = "music_stream_cache"

    protected open fun createAudioProcessors(): Array<AudioProcessor> {
        val effects = (application as PlayApplication).container.audioEffects
        val transitions = (application as PlayApplication).container.playbackTransitions
        return arrayOf(EqualizerAudioProcessor { effects.state.value.settings },
            PeakNormalizerAudioProcessor { transitions.state.value.settings.peakNormalizationEnabled })
    }

    protected open fun createRenderers(): RenderersFactory = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParameters: Boolean): AudioSink {
            val sink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(createAudioProcessors())
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters).build()
            // Custom processors are only applied to PCM. Refuse compressed passthrough/offload
            // capabilities even on HDMI/Bluetooth routes, so live EQ never silently disappears.
            return object : ForwardingAudioSink(sink) {
                override fun supportsFormat(format: Format): Boolean =
                    format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

                override fun getFormatSupport(format: Format): Int =
                    if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                    else AudioSink.SINK_FORMAT_UNSUPPORTED

                override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
                    AudioOffloadSupport.DEFAULT_UNSUPPORTED
            }
        }
    }

    protected open fun createMediaSources(mediaCache: SimpleCache): MediaSource.Factory {
        val container = (application as PlayApplication).container
        val api = container.streamingApi
        val http = DefaultHttpDataSource.Factory().setUserAgent("Play/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val cacheFactory = CacheDataSource.Factory().setCache(mediaCache).setUpstreamDataSourceFactory(http)
        val dataSources = DataSource.Factory { CachedAudioDataSource(api, cacheFactory) }
        val drm = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID,
            FrameworkMediaDrm.DEFAULT_PROVIDER).setMultiSession(true).setSessionKeepaliveMs(C.TIME_UNSET)
            .build(AuthenticatedDrmCallback(container.playbackAuthorization))
        return ProgressiveMediaSource.Factory(dataSources).setDrmSessionManagerProvider { drm }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        val waiting = foregroundReady
        val foreground = startInForegroundRequired || waiting != null ||
            (session.player as? TransitionPlayer)?.audioEngines()?.any { it.isPlaying } == true
        val update = super.onUpdateNotificationAsync(session, foreground)
        if (waiting != null) update.addListener({
            try { update.get(); waiting.complete(Unit) }
            catch (error: Exception) { waiting.completeExceptionally(error) }
        }, ContextCompat.getMainExecutor(this))
        return update
    }

    private suspend fun awaitPlaybackForeground() {
        val ready = CompletableDeferred<Unit>()
        foregroundReady = ready
        try { triggerNotificationUpdate(); withTimeout(5_000) { ready.await() } }
        finally { if (foregroundReady === ready) foregroundReady = null }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if ((player as? TransitionPlayer)?.audioEngines()?.any { it.isPlaying } == true) return
        if (player == null || !player.playWhenReady ||
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) stopSelf()
    }

    override fun onDestroy() {
        destroying = true
        foregroundReady?.cancel(); foregroundReady = null
        session?.player?.let { (application as PlayApplication).container.sleepTimer.detach(it) }
        session?.run { player.release(); release() }
        session = null
        cache?.release()
        database?.close()
        super.onDestroy()
    }

    companion object { const val MUSIC_CACHE_BYTES = 512L * 1024 * 1024 }
}
