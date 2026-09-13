package io.github.playmusic

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.github.playmusic.data.audio.EqualizerAudioProcessor
import io.github.playmusic.data.audio.EqualizerBands
import io.github.playmusic.data.audio.EqualizerPreset
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.audio.settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class EqualizerAudioProcessorTest {
    @Test fun inactiveSettingsStayInThePipelineAndCanBeEnabledWithoutReconfiguration() {
        var settings = EqualizerSettings()
        val processor = prepared(EqualizerAudioProcessor { settings }, 48_000, 2)
        assertTrue(processor.isActive)
        val allValues = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        assertArrayEquals(pcm(allValues), consume(processor, pcm(allValues)))
        settings = EqualizerSettings(enabled = true, preampDb = 10f)
        val boosted = shorts(consume(processor, pcm(ShortArray(9600) { 1000 })))
        assertTrue(boosted[0] in 1001..1004)
        assertTrue(boosted.takeLast(1000).all { it == 3162.toShort() })
        settings = settings.copy(enabled = false)
        val disabled = shorts(consume(processor, pcm(ShortArray(9600) { 1000 })))
        assertArrayEquals(ShortArray(1000) { 1000 }, disabled.takeLast(1000).toShortArray())
        assertTrue(processor.isActive)
        processor.queueEndOfStream()
        assertTrue(processor.isEnded)
        processor.reset()
        assertFalse(processor.isActive)
    }

    @Test fun fragmentsOfStereoFramesAndReadOnlyInputPreserveEveryOutputSample() {
        val settings = EqualizerPreset.POP.settings()
        val input = pcm(ShortArray(12_000) { ((it * 17 % 5000) - 2500).toShort() })
        val expected = consume(prepared(EqualizerAudioProcessor { settings }, 48_000, 2), input)
        val processor = prepared(EqualizerAudioProcessor { settings }, 48_000, 2)
        val actual = ByteArrayOutputStream()
        val sizes = intArrayOf(1, 3, 2, 5, 127, 1024, 11)
        var position = 0
        var chunk = 0
        while (position < input.size) {
            val end = (position + sizes[chunk++ % sizes.size]).coerceAtMost(input.size)
            val buffer = direct(input.copyOfRange(position, end)).asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            processor.queueInput(buffer)
            assertEquals(buffer.limit(), buffer.position())
            actual.write(bytes(processor.output))
            position = end
        }
        processor.queueEndOfStream()
        actual.write(bytes(processor.output))
        assertTrue(processor.isEnded)
        assertEquals(input.size, actual.size())
        assertArrayEquals(expected, actual.toByteArray())
    }

    @Test fun pendingFormatIsAppliedOnlyOnFlushAndSeekClearsFramesAndHistory() {
        val settings = EqualizerSettings(enabled = true,
            bandGainsDb = List(EqualizerBands.COUNT) { if (it == 16) 12f else 0f })
        val processor = prepared(EqualizerAudioProcessor { settings }, 48_000, 2)
        consume(processor, pcm(shortArrayOf(2000, -2000)))
        processor.configure(AudioFormat(32_000, 1, C.ENCODING_PCM_16BIT))
        assertEquals(4, consume(processor, pcm(shortArrayOf(0, 0))).size)
        assertEquals(0, consume(processor, byteArrayOf(1, 2, 3)).size)
        processor.flush(StreamMetadata.DEFAULT)
        assertArrayEquals(ByteArray(1000), consume(processor, ByteArray(1000)))
        processor.queueInput(direct(pcm(shortArrayOf(1000))))
        processor.queueEndOfStream()
        assertFalse(processor.isEnded)
        assertEquals(2, processor.output.remaining())
        assertTrue(processor.isEnded)
        processor.reset()
        prepared(processor, 96_000, 2)
        assertArrayEquals(ByteArray(400), consume(processor, ByteArray(400)))
    }

    @Test fun meterAfterTheProcessorReceivesChangedPcmWithTheSameDuration() {
        val processor = EqualizerAudioProcessor { EqualizerSettings(enabled = true, preampDb = -10f) }
        var measuredSamples = 0
        var sumSquares = 0.0
        val meter = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                assertEquals(48_000, sampleRateHz)
                assertEquals(2, channelCount)
                assertEquals(C.ENCODING_PCM_16BIT, encoding)
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                buffer.order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    val sample = buffer.short.toDouble()
                    sumSquares += sample * sample
                    measuredSamples++
                }
            }
        })
        val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val afterEq = processor.configure(format)
        assertEquals(format, afterEq)
        assertEquals(format, meter.configure(afterEq))
        processor.flush(StreamMetadata.DEFAULT)
        meter.flush(StreamMetadata.DEFAULT)
        processor.queueInput(direct(pcm(ShortArray(96_000) { 1000 })))
        meter.queueInput(processor.output)
        assertEquals(192_000, meter.output.remaining())
        assertEquals(96_000, measuredSamples)
        assertEquals(316.0, sqrt(sumSquares / measuredSamples), 0.01)
        assertEquals(1_000_000L, processor.getDurationAfterProcessorApplied(1_000_000L))
    }

    @Test fun unsupportedEncodingsAndIncompleteFinalFramesAreRejected() {
        val processor = EqualizerAudioProcessor { EqualizerSettings() }
        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT, C.ENCODING_PCM_24BIT)) {
            val error = runCatching { processor.configure(AudioFormat(48_000, 2, encoding)) }.exceptionOrNull()
            assertTrue(error is AudioProcessor.UnhandledAudioFormatException)
        }
        prepared(processor, 48_000, 2)
        consume(processor, byteArrayOf(1, 2, 3))
        assertTrue(runCatching { processor.queueEndOfStream() }.exceptionOrNull() is IllegalStateException)
        processor.flush(StreamMetadata.DEFAULT)
        assertArrayEquals(pcm(shortArrayOf(1, 2)), consume(processor, pcm(shortArrayOf(1, 2))))
    }

    private fun prepared(processor: EqualizerAudioProcessor, rate: Int, channels: Int): EqualizerAudioProcessor {
        val format = AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush(StreamMetadata.DEFAULT)
        return processor
    }

    private fun consume(processor: AudioProcessor, input: ByteArray): ByteArray {
        val buffer = direct(input)
        processor.queueInput(buffer)
        assertEquals(buffer.limit(), buffer.position())
        return bytes(processor.output)
    }

    private fun direct(input: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(input.size)
        .order(ByteOrder.nativeOrder()).apply { put(input); flip() }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also(buffer::get)

    private fun pcm(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.nativeOrder()).apply { samples.forEach { putShort(it) } }.array()

    private fun shorts(bytes: ByteArray): ShortArray = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .let { buffer -> ShortArray(bytes.size / 2) { buffer.short } }
}
