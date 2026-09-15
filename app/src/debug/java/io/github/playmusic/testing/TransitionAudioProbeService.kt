package io.github.playmusic.testing

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import io.github.playmusic.data.playback.PlaybackService
import java.util.concurrent.atomic.AtomicReferenceArray

/** Debug-only meters after the production DSP. Stores numbers, never decoded audio buffers. */
@OptIn(UnstableApi::class)
class TransitionAudioProbeService : PlaybackService() {
    override val cacheDirectoryName = "transition_audio_probe_cache"
    private var nextMeter = 0

    override fun createAudioProcessors(): Array<AudioProcessor> {
        // PlaybackService constructs the first engine/renderers synchronously, then the second.
        val engine = nextMeter++
        check(engine in 0..1)
        val meter = MediaTimeLevelMeter().also { meters.set(engine, it) }
        return super.createAudioProcessors() + meter
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        super.onGetSession(controllerInfo).also { if (it != null) activeSession = it }

    override fun onDestroy() {
        activeSession = null
        super.onDestroy()
    }

    companion object {
        // Only instrumentation on the application's main looper may dereference this player.
        var activeSession: MediaSession? = null
            private set
        private val meters = AtomicReferenceArray<MediaTimeLevelMeter>(2)
        fun reading(engine: Int): MediaTimeLevelMeter.Reading = meters.get(engine)?.latest ?: MediaTimeLevelMeter.Reading()
        fun readingAt(engine: Int, periodUid: Any?, positionUs: Long): MediaTimeLevelMeter.Reading? =
            meters.get(engine)?.atPosition(periodUid, positionUs)
        fun matchesPeriod(engine: Int, periodUid: Any?): Boolean = meters.get(engine)?.matchesPeriod(periodUid) == true
    }
}
