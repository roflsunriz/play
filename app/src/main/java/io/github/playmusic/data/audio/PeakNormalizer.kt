package io.github.playmusic.data.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Streaming peak normalization, with a track-long peak hold and a shared stereo gain.
 * Gain rises over two seconds, never amplifies below -60 dBFS, and is capped at +12 dB.
 * A newly observed peak reduces gain immediately so output never exceeds -1 dBFS.
 */
internal class PeakNormalizer {
    private var peak = 0.0
    private var gain = 1.0
    private var rise = 0.0
    fun reset(sampleRate: Int) { require(sampleRate > 0); peak = 0.0; gain = 1.0; rise = 1 - exp(-1.0 / (2 * sampleRate)) }
    fun process(samples: ShortArray, channels: Int, enabled: Boolean) {
        if (!enabled) { peak = 0.0; gain = 1.0; return }
        var framePeak = 0.0
        for (channel in 0 until channels) framePeak = max(framePeak, abs(samples[channel].toDouble() / 32768))
        peak = max(peak, framePeak)
        val target = if (peak < SILENCE_THRESHOLD) 1.0 else min(MAX_GAIN, TARGET_PEAK / peak)
        gain = if (target < gain) target else gain + (target - gain) * rise
        for (channel in 0 until channels) samples[channel] = (samples[channel] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }
    companion object {
        const val TARGET_PEAK = 0.8912509381337456
        const val MAX_GAIN = 3.9810717055349722
        const val SILENCE_THRESHOLD = 0.001
    }
}
