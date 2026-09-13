package io.github.playmusic.testing

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.session.MediaSession
import io.github.playmusic.data.playback.PlaybackService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Measures decoded amplitude only; no audio, credentials or license data are recorded. */
@OptIn(UnstableApi::class)
class AudioProbeService : PlaybackService() {
    override val cacheDirectoryName = "audio_probe_cache"

    override fun createAudioProcessors(): Array<AudioProcessor> =
        super.createAudioProcessors() + TeeAudioProcessor(LevelMeter())

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        super.onGetSession(controllerInfo).also { session ->
            if (session != null && !observing) {
                observing = true
                (session.player as ExoPlayer).addAnalyticsListener(object : AnalyticsListener {
                    override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime, keyRequestInfo: KeyRequestInfo) {
                        Log.i(TAG, "drm keys loaded")
                    }
                    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime,
                        format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                        Log.i(TAG, "input channels=${format.channelCount} drm=${format.drmInitData != null}")
                    }
                })
            }
        }

    private var observing = false

    private class LevelMeter : TeeAudioProcessor.AudioBufferSink {
        private var rate = 0
        private var channels = 0
        private var count = 0L
        private var sum = 0.0
        private var seconds = 0
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            check(encoding == C.ENCODING_PCM_16BIT)
            rate = sampleRateHz
            channels = channelCount
            Log.i(TAG, "output channels=$channels rate=$rate")
        }
        override fun handleBuffer(buffer: ByteBuffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 2) {
                val value = buffer.short.toDouble() / 32768
                sum += value * value
                count++
                if (count >= rate * channels) {
                    val rms = sqrt(sum / count)
                    seconds++
                    rmsSeconds[seconds] = rms
                    Log.i(TAG, "decoded second=$seconds rms=$rms channels=$channels")
                    if (rms > 0.001) {
                        audibleSeconds.add(seconds)
                        if (seconds in 16..30) audibleLateSeconds++
                    }
                    sum = 0.0
                    count = 0
                }
            }
        }
    }

    companion object {
        const val TAG = "PlayAudioProbe"
        @Volatile var audibleLateSeconds = 0
        private val audibleSeconds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        private val rmsSeconds = java.util.concurrent.ConcurrentHashMap<Int, Double>()
        fun resetMeasurements() { audibleLateSeconds = 0; audibleSeconds.clear(); rmsSeconds.clear() }
        fun audibleSecondsIn(first: Int, last: Int): Int = (first..last).count(audibleSeconds::contains)
        fun rmsAt(second: Int): Double? = rmsSeconds[second]
    }
}
