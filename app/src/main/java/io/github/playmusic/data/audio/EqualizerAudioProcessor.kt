package io.github.playmusic.data.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Always stays in the PCM pipeline so enabling EQ during playback needs no renderer restart. */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor(settingsProvider: () -> EqualizerSettings) : BaseAudioProcessor() {
    private val dsp = EqualizerDsp(settingsProvider)
    private var partialFrame = AudioProcessor.EMPTY_BUFFER

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount !in 1..EqualizerDsp.MAX_CHANNELS) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        if (inputAudioFormat == AudioFormat.NOT_SET) return
        dsp.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        val frameBytes = inputAudioFormat.bytesPerFrame
        if (partialFrame.capacity() != frameBytes) {
            partialFrame = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder())
        } else partialFrame.clear()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val frameBytes = inputAudioFormat.bytesPerFrame
        check(frameBytes > 0)
        val outputBytes = (inputBuffer.remaining().toLong() + partialFrame.position()) / frameBytes * frameBytes
        check(outputBytes <= Int.MAX_VALUE)
        val output = replaceOutputBuffer(outputBytes.toInt())
        if (partialFrame.position() > 0) {
            while (partialFrame.hasRemaining() && inputBuffer.hasRemaining()) partialFrame.put(inputBuffer.get())
            if (!partialFrame.hasRemaining()) {
                partialFrame.flip()
                dsp.process(partialFrame, output)
                partialFrame.clear()
            }
        }
        val wholeBytes = inputBuffer.remaining() / frameBytes * frameBytes
        if (wholeBytes > 0) {
            val oldLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + wholeBytes)
            try { dsp.process(inputBuffer, output) } finally { inputBuffer.limit(oldLimit) }
        }
        while (inputBuffer.hasRemaining()) partialFrame.put(inputBuffer.get())
        output.flip()
    }

    override fun onQueueEndOfStream() {
        check(partialFrame.position() == 0) { "Incomplete PCM frame at end of stream" }
    }

    override fun onReset() {
        dsp.reset()
        partialFrame = AudioProcessor.EMPTY_BUFFER
    }
}
