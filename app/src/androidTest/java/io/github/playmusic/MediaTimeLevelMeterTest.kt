package io.github.playmusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import io.github.playmusic.testing.MediaTimeLevelMeter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Synthetic PCM only. Tests diagnostic timing without storing or recording actual audio. */
class MediaTimeLevelMeterTest {
    @Test fun fragmentedFramesKeepExactOutputAndWindowAlignedMediaTime() {
        val period = Any()
        val meter = meter(period, 31_000_000)
        val pcm = pcm(frames = 1_600)
        val output = feed(meter, pcm, intArrayOf(1, 7, 257, 2, 99))
        assertArrayEquals(pcm, output)
        val first = requireNotNull(meter.atPosition(period, 31_050_000))
        val second = requireNotNull(meter.atPosition(period, 31_150_000))
        assertEquals(31_000_000, first.startUs)
        assertEquals(31_100_000, first.endUs)
        assertEquals(31_100_000, second.startUs)
        assertEquals(31_200_000, second.endUs)
        assertEquals(sqrt((.25 + .0625) / 2), first.rms, .000001)
        assertEquals(.5, first.peak, .000001)
        assertNull(meter.atPosition(period, 30_999_999))
        assertNull(meter.atPosition(period, 31_200_000))
        assertNull(meter.atPosition(Any(), 31_050_000))
    }

    @Test fun eosPublishesPartialWindowAndKeepsItForQueuedPlayoutWithoutAgeGuess() {
        var now = 1_000L
        val period = Any()
        val meter = meter(period, 183_867_000, now = { now })
        val pcm = pcm(frames = 400)
        assertArrayEquals(pcm, feed(meter, pcm))
        assertNull(meter.atPosition(period, 183_887_000))
        meter.queueEndOfStream()
        val tail = requireNotNull(meter.atPosition(period, 183_887_000))
        assertEquals(183_867_000, tail.startUs)
        assertEquals(183_917_000, tail.endUs)
        assertEquals(.5, tail.peak, .000001)
        now += 20_000
        assertEquals(tail, meter.atPosition(period, 183_887_000))
        assertEquals(tail, meter.latest)
        assertTrue(meter.isEnded)
        assertNull(meter.atPosition(period, 183_917_000))
    }

    @Test fun flushAndSeekSeparateGenerationsAndSilenceNeverReusesOldPeak() {
        val period = Any()
        val meter = meter(period, 0)
        feed(meter, pcm(800))
        val original = requireNotNull(meter.atPosition(period, 50_000))
        meter.flush(StreamMetadata.Builder().setPeriodUid(period).setPositionOffsetUs(9_000_000).build())
        assertNull(meter.atPosition(period, 50_000))
        assertNull(meter.atPosition(period, 9_050_000))
        feed(meter, pcm(800, first = 0, second = 0))
        val silence = requireNotNull(meter.atPosition(period, 9_050_000))
        assertTrue(silence.generation > original.generation)
        assertTrue(silence.windows > original.windows)
        assertEquals(0.0, silence.rms, 0.0)
        assertEquals(0.0, silence.peak, 0.0)
        val nextPeriod = Any()
        meter.flush(StreamMetadata.Builder().setPeriodUid(nextPeriod).setPositionOffsetUs(9_000_000).build())
        feed(meter, pcm(800))
        assertNull(meter.atPosition(period, 9_050_000))
        assertNotNull(meter.atPosition(nextPeriod, 9_050_000))
        meter.reset()
        assertNull(meter.atPosition(nextPeriod, 9_050_000))
    }

    @Test fun incompleteFrameIsRejectedAtEosAndFlushDiscardsTheCanceledPartialFrame() {
        val period = Any()
        val meter = meter(period, 0)
        feed(meter, byteArrayOf(1, 2, 3))
        assertTrue(runCatching { meter.queueEndOfStream() }.exceptionOrNull() is IllegalStateException)
        meter.flush(StreamMetadata.Builder().setPeriodUid(period).setPositionOffsetUs(5_000_000).build())
        feed(meter, pcm(800))
        meter.queueEndOfStream()
        assertEquals(.5, requireNotNull(meter.atPosition(period, 5_050_000)).peak, .000001)
    }

    @Test fun boundedNumericHistoryAndUnknownOffsetsNeverInventPositionMatches() {
        val period = Any()
        val meter = meter(period, 0, rate = 1_000)
        feed(meter, pcm(52_000))
        assertNull(meter.atPosition(period, 50_000))
        assertNotNull(meter.atPosition(period, 51_950_000))
        meter.flush(StreamMetadata.Builder().setPeriodUid(period).setPositionOffsetUs(C.TIME_UNSET).build())
        feed(meter, pcm(100))
        assertNull(meter.atPosition(period, 50_000))
        assertEquals(C.TIME_UNSET, meter.latest.startUs)
    }

    private fun meter(period: Any, offsetUs: Long, rate: Int = 8_000, now: () -> Long = { 1_000L }) =
        MediaTimeLevelMeter(now).apply {
            configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush(StreamMetadata.Builder().setPeriodUid(period).setPositionOffsetUs(offsetUs).build())
        }

    private fun pcm(frames: Int, first: Int = 16_384, second: Int = -8_192): ByteArray =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort(first.toShort()); putShort(second.toShort()) }
        }.array()

    private fun feed(meter: MediaTimeLevelMeter, pcm: ByteArray, sizes: IntArray = intArrayOf(4_096)): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        var fragment = 0
        while (offset < pcm.size) {
            val count = minOf(sizes[fragment++ % sizes.size], pcm.size - offset)
            val input = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder()).put(pcm, offset, count).apply { flip() }
            meter.queueInput(input)
            val processed = meter.output
            val bytes = ByteArray(processed.remaining())
            processed.get(bytes)
            output.write(bytes)
            offset += count
        }
        return output.toByteArray()
    }
}
