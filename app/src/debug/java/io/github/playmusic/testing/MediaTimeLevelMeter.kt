package io.github.playmusic.testing

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A transparent debug meter after EQ/normalization and before Sonic's time transformation.
 * Stores only 100 ms statistics, never PCM captures. The passthrough output buffer is consumed
 * by the unchanged production audio pipeline.
 *
 * Media3 1.11.1 DefaultAudioSink.setupAudioProcessors computes positionOffsetUs as
 * presentationTimeUs - outputStreamOffsetUs + period.positionInWindowUs. This identifies the
 * first PCM frame after flush, rather than assuming it equals a requested seek position.
 * See DefaultAudioSinkTest.setOutputStreamOffset_withTimeline_updatesStreamMetadata in:
 * https://github.com/androidx/media/tree/1.11.1/libraries/exoplayer/src/test/java/androidx/media3/exoplayer/audio
 */
@OptIn(UnstableApi::class)
class MediaTimeLevelMeter(private val now: () -> Long = SystemClock::elapsedRealtime) : BaseAudioProcessor() {
    data class Reading(
        val windows: Long = 0,
        val samples: Long = 0,
        val elapsedMs: Long = 0,
        val rms: Double = 0.0,
        val peak: Double = 0.0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val startUs: Long = C.TIME_UNSET,
        val endUs: Long = C.TIME_UNSET,
        val generation: Long = 0,
    )

    private val lock = Any()
    private val windows = ArrayDeque<Reading>()
    private var periodUid: Any? = null
    private var offsetUs = C.TIME_UNSET
    private var generation = 0L
    private var sequence = 0L
    private var samples = 0L
    private var frames = 0L
    private var windowStartFrame = 0L
    private var sum = 0.0
    private var peak = 0.0
    private var frameChannels = 0
    private var frameSum = 0.0
    private var framePeak = 0.0
    private var pendingLowByte = -1

    @Volatile var latest: Reading = Reading()
        private set

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        require(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount > 0 && inputAudioFormat.sampleRate > 0)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        synchronized(lock) {
            generation++
            periodUid = streamMetadata.periodUid
            offsetUs = streamMetadata.positionOffsetUs
            windows.clear()
            latest = Reading(windows = sequence, samples = samples, sampleRate = inputAudioFormat.sampleRate,
                channels = inputAudioFormat.channelCount, generation = generation)
        }
        frames = 0; windowStartFrame = 0
        sum = 0.0; peak = 0.0
        frameChannels = 0; frameSum = 0.0; framePeak = 0.0; pendingLowByte = -1
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.asReadOnlyBuffer()
        while (input.hasRemaining()) {
            val next = input.get().toInt() and 0xff
            if (pendingLowByte < 0) {
                pendingLowByte = next
            } else {
                val value = ((next shl 8) or pendingLowByte).toShort().toDouble() / 32768.0
                pendingLowByte = -1
                frameSum += value * value
                framePeak = max(framePeak, abs(value))
                frameChannels++
                if (frameChannels == inputAudioFormat.channelCount) {
                    frames++
                    samples += frameChannels
                    sum += frameSum
                    peak = max(peak, framePeak)
                    frameChannels = 0; frameSum = 0.0; framePeak = 0.0
                    if (frames - windowStartFrame >= max(1, inputAudioFormat.sampleRate / 10)) emitWindow()
                }
            }
        }
        if (inputBuffer.hasRemaining()) replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    override fun onQueueEndOfStream() {
        check(pendingLowByte < 0 && frameChannels == 0) { "PCM ended inside an audio frame" }
        if (frames > windowStartFrame) emitWindow()
        // End-of-input is not end-of-playout. AudioTrack may still contain these exact frames.
        // Keep the numeric windows until an actual flush/seek or reset starts another generation.
    }

    override fun onReset() {
        synchronized(lock) {
            windows.clear(); periodUid = null; offsetUs = C.TIME_UNSET
            latest = Reading(windows = sequence, samples = samples, generation = ++generation)
        }
    }

    private fun emitWindow() {
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val value = Reading(++sequence, samples, now(), sqrt(sum / ((frames - windowStartFrame) * channels)),
            peak, rate, channels,
            if (offsetUs == C.TIME_UNSET) C.TIME_UNSET else offsetUs + windowStartFrame * 1_000_000L / rate,
            if (offsetUs == C.TIME_UNSET) C.TIME_UNSET else offsetUs + frames * 1_000_000L / rate,
            generation)
        synchronized(lock) {
            windows.addLast(value)
            // 51 seconds of numeric metadata; a missing/evicted position is never guessed.
            while (windows.size > 512) windows.removeFirst()
            latest = value
        }
        windowStartFrame = frames; sum = 0.0; peak = 0.0
    }

    fun atPosition(expectedPeriodUid: Any?, positionUs: Long): Reading? = synchronized(lock) {
        if (periodUid != expectedPeriodUid || offsetUs == C.TIME_UNSET) return@synchronized null
        windows.firstOrNull { it.generation == generation && positionUs >= it.startUs && positionUs < it.endUs }
    }

    fun matchesPeriod(expectedPeriodUid: Any?): Boolean = synchronized(lock) { periodUid == expectedPeriodUid }
}
