package io.github.playmusic.data.audio

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Thirty constant-Q peaking filters, followed only by saturating PCM16 quantization.
 * Coefficients use https://www.w3.org/TR/audio-eq-cookbook/#formulae (peakingEQ).
 * Q corresponds to a nominal third-octave bandwidth; centres at/above Nyquist are bypassed.
 *
 * Call on one audio thread. The provider must return an immutable snapshot without I/O or locks.
 * Two fixed-coefficient banks crossfade over 20 ms, so live changes never interpolate IIR poles.
 * Updates received during a fade are coalesced to the latest snapshot at its end. Neither audio
 * nor filter state is retained outside this instance; flush discards history on seek/stream change.
 */
internal class EqualizerDsp(private val settingsProvider: () -> EqualizerSettings) {
    private var channels = 0
    private var fadeFrames = 1
    private var fadePosition = 0
    private var fading = false
    private var current: FilterBank? = null
    private var next: FilterBank? = null
    private var currentSettings = EqualizerSettings()
    private var nextSettings = currentSettings

    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0 && channelCount in 1..MAX_CHANNELS)
        channels = channelCount
        fadeFrames = (sampleRate.toLong() * FADE_MILLISECONDS / 1000).toInt().coerceAtLeast(1)
        val geometry = BandGeometry(sampleRate)
        current = FilterBank(channelCount, geometry)
        next = FilterBank(channelCount, geometry)
        flush()
    }

    fun flush() {
        val bank = current ?: return
        currentSettings = settingsProvider()
        nextSettings = currentSettings
        bank.setSettings(currentSettings)
        next?.clearHistory()
        fading = false
        fadePosition = 0
    }

    fun reset() {
        current?.clearHistory()
        next?.clearHistory()
        current = null
        next = null
        channels = 0
        fading = false
        fadePosition = 0
    }

    /** Consumes whole, interleaved PCM16 frames. Both buffers use native byte order. */
    fun process(input: ByteBuffer, output: ByteBuffer) {
        check(channels > 0)
        require(input.remaining() % (channels * 2) == 0 && output.remaining() >= input.remaining())
        val requested = settingsProvider()
        while (input.hasRemaining()) {
            if (!fading && requested != currentSettings) startFade(requested)
            val from = checkNotNull(current)
            if (!fading && from.isBypass) {
                output.put(input)
                return
            }
            val to = checkNotNull(next)
            val mix = if (fading) (fadePosition + 1).toDouble() / fadeFrames else 0.0
            var channel = 0
            while (channel < channels) {
                val sample = input.short.toDouble()
                val previous = from.process(sample, channel)
                val value = if (fading) previous + (to.process(sample, channel) - previous) * mix else previous
                // Clamp before integer conversion: positive boosts must never wrap into negative PCM.
                output.putShort(value.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).roundToInt().toShort())
                channel++
            }
            if (fading && ++fadePosition == fadeFrames) {
                current = to
                next = from
                currentSettings = nextSettings
                fading = false
            }
        }
    }

    private fun startFade(settings: EqualizerSettings) {
        val target = checkNotNull(next)
        target.setSettings(settings)
        nextSettings = settings
        fadePosition = 0
        if (checkNotNull(current).isBypass && target.isBypass) {
            currentSettings = settings
        } else {
            fading = true
        }
    }

    private class BandGeometry(sampleRate: Int) {
        val eligible = BooleanArray(EqualizerBands.COUNT)
        val cosine = DoubleArray(EqualizerBands.COUNT)
        val alpha = DoubleArray(EqualizerBands.COUNT)

        init {
            val q = 1.0 / (2.0 * sinh(ln(2.0) / 6.0))
            for (band in 0 until EqualizerBands.COUNT) {
                val frequency = EqualizerBands.frequenciesHz[band].toDouble()
                eligible[band] = frequency < sampleRate / 2.0
                if (eligible[band]) {
                    val omega = 2.0 * PI * frequency / sampleRate
                    cosine[band] = cos(omega)
                    alpha[band] = sin(omega) / (2.0 * q)
                }
            }
        }
    }

    private class FilterBank(private val channels: Int, private val geometry: BandGeometry) {
        private val b0 = DoubleArray(EqualizerBands.COUNT)
        private val b1 = DoubleArray(EqualizerBands.COUNT)
        private val b2 = DoubleArray(EqualizerBands.COUNT)
        private val a1 = DoubleArray(EqualizerBands.COUNT)
        private val a2 = DoubleArray(EqualizerBands.COUNT)
        private val firstDelay = DoubleArray(EqualizerBands.COUNT * channels)
        private val secondDelay = DoubleArray(EqualizerBands.COUNT * channels)
        private val activeBands = IntArray(EqualizerBands.COUNT)
        private var activeCount = 0
        private var preamp = 1.0
        val isBypass: Boolean get() = activeCount == 0 && preamp == 1.0

        fun setSettings(settings: EqualizerSettings) {
            clearHistory()
            activeCount = 0
            preamp = if (settings.enabled) 10.0.pow(settings.preampDb / 20.0) else 1.0
            if (!settings.enabled) return
            for (band in 0 until EqualizerBands.COUNT) {
                val gain = settings.bandGainsDb[band]
                if (gain == 0f || !geometry.eligible[band]) continue
                val amplitude = 10.0.pow(gain / 40.0)
                val alpha = geometry.alpha[band]
                val denominator = 1.0 + alpha / amplitude
                b0[band] = (1.0 + alpha * amplitude) / denominator
                b1[band] = -2.0 * geometry.cosine[band] / denominator
                b2[band] = (1.0 - alpha * amplitude) / denominator
                a1[band] = b1[band]
                a2[band] = (1.0 - alpha / amplitude) / denominator
                activeBands[activeCount++] = band
            }
        }

        fun process(input: Double, channel: Int): Double {
            var sample = input * preamp
            var index = 0
            while (index < activeCount) {
                val band = activeBands[index++]
                val state = band * channels + channel
                val result = b0[band] * sample + firstDelay[state]
                val first = b1[band] * sample - a1[band] * result + secondDelay[state]
                val second = b2[band] * sample - a2[band] * result
                // Avoid subnormal tails after long silence, far below one PCM quantization step.
                firstDelay[state] = if (abs(first) < 1e-24) 0.0 else first
                secondDelay[state] = if (abs(second) < 1e-24) 0.0 else second
                sample = result
            }
            return sample
        }

        fun clearHistory() {
            firstDelay.fill(0.0)
            secondDelay.fill(0.0)
        }
    }

    companion object {
        private const val FADE_MILLISECONDS = 20L
        const val MAX_CHANNELS = 32
    }
}
