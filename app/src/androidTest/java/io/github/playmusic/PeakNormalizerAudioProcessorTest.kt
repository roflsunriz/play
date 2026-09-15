package io.github.playmusic

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import io.github.playmusic.data.audio.PeakNormalizerAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class PeakNormalizerAudioProcessorTest {
    @Test fun fragmentedFramesAreIdenticalAndFlushStartsANewPeakHistory() {
        fun processor(enabled: () -> Boolean) = PeakNormalizerAudioProcessor(enabled).apply {
            configure(AudioFormat(8_000, 2, C.ENCODING_PCM_16BIT)); flush(StreamMetadata.DEFAULT)
        }
        val input = ByteBuffer.allocate(32_000).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(8_000) { putShort((it % 10 * 1000).toShort()); putShort((-it % 10 * 1000).toShort()) }
        }.array()
        fun consume(processor: PeakNormalizerAudioProcessor, bytes: ByteArray): ByteArray {
            processor.queueInput(ByteBuffer.wrap(bytes).asReadOnlyBuffer())
            return processor.output.let { output -> ByteArray(output.remaining()).also(output::get) }
        }
        val expected = consume(processor { true }, input)
        val fragmented = processor { true }
        val output = ByteArrayOutputStream()
        input.asList().chunked(37).forEach { output.write(consume(fragmented, it.toByteArray())) }
        fragmented.queueEndOfStream()
        assertTrue(fragmented.isEnded)
        assertArrayEquals(expected, output.toByteArray())
        fragmented.flush(StreamMetadata.DEFAULT)
        assertArrayEquals(expected, consume(fragmented, input))
        assertEquals(1_000_000L, fragmented.getDurationAfterProcessorApplied(1_000_000))
    }
    @Test fun bypassCanBeEnabledWithoutRestartingAndNeverDropsSamples() {
        var enabled = false
        val processor = PeakNormalizerAudioProcessor { enabled }.apply {
            configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)); flush(StreamMetadata.DEFAULT)
        }
        val input = ByteBuffer.allocate(2_000).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(1_000) { putShort(32_767) } }.array()
        processor.queueInput(ByteBuffer.wrap(input))
        assertArrayEquals(input, processor.output.let { ByteArray(it.remaining()).also(it::get) })
        enabled = true
        processor.queueInput(ByteBuffer.wrap(input))
        val changed = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(input.size, changed.remaining())
        while (changed.hasRemaining()) assertTrue(changed.short in 29_200..29_205)
        assertTrue(processor.isActive)
    }
}
