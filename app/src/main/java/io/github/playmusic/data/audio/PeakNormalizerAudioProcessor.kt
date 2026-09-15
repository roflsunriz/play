package io.github.playmusic.data.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class PeakNormalizerAudioProcessor(private val enabled: () -> Boolean) : BaseAudioProcessor() {
    private val normalizer = PeakNormalizer()
    private var samples = ShortArray(0)
    private var partial = ByteArray(0)
    private var partialSize = 0
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount !in 1..8) throw UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush(streamMetadata: StreamMetadata) {
        if (inputAudioFormat == AudioFormat.NOT_SET) return
        normalizer.reset(inputAudioFormat.sampleRate)
        samples = ShortArray(inputAudioFormat.channelCount)
        partial = ByteArray(inputAudioFormat.bytesPerFrame)
        partialSize = 0
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val frameSize = inputAudioFormat.bytesPerFrame
        check(frameSize > 0)
        val outputBytes = (partialSize.toLong() + inputBuffer.remaining()) / frameSize * frameSize
        check(outputBytes <= Int.MAX_VALUE)
        val output = replaceOutputBuffer(outputBytes.toInt())
        val active = enabled()
        while (inputBuffer.hasRemaining()) {
            partial[partialSize++] = inputBuffer.get()
            if (partialSize != frameSize) continue
            for (channel in samples.indices) samples[channel] =
                ((partial[channel * 2].toInt() and 255) or (partial[channel * 2 + 1].toInt() shl 8)).toShort()
            normalizer.process(samples, samples.size, active)
            for (sample in samples) { output.put(sample.toByte()); output.put((sample.toInt() shr 8).toByte()) }
            partialSize = 0
        }
        output.flip()
    }
    override fun onQueueEndOfStream() { check(partialSize == 0) { "Incomplete PCM frame" } }
    override fun onReset() { samples = ShortArray(0); partial = ByteArray(0); partialSize = 0 }
}
