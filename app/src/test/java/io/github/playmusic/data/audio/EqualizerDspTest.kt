package io.github.playmusic.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerDspTest {
    @Test fun disabledAndEnabledFlatPreserveEveryPcmValueExactly() {
        val samples = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        for (settings in listOf(EqualizerSettings(), EqualizerPreset.FLAT.settings(),
            EqualizerSettings(false, 10f, List(EqualizerBands.COUNT) { 12f }))) {
            assertArrayEquals(samples, render(EqualizerDsp { settings }.apply { configure(48_000, 2) }, samples))
        }
    }

    @Test fun eachOfThirtyBandsProducesItsGainAtItsOwnCenter() {
        for (sampleRate in listOf(44_100, 48_000)) {
            for ((band, frequency) in EqualizerBands.frequenciesHz.withIndex()) {
                for (gain in listOf(-6f, 6f)) {
                    assertEquals("$frequency Hz at $sampleRate Hz, $gain dB", gain.toDouble(),
                        measuredGain(sampleRate, frequency.toDouble(), singleBand(band, gain)), 0.04)
                }
            }
        }
    }

    @Test fun peakingGainIsLocalAndPreampCoversBothEndpoints() {
        val peak = singleBand(16, 12f)
        assertEquals(12.0, measuredGain(48_000, 1000.0, peak), 0.04)
        assertEquals(0.0, measuredGain(48_000, 100.0, peak), 0.06)
        assertEquals(0.0, measuredGain(48_000, 10_000.0, peak), 0.06)
        for (gain in listOf(-10f, 10f)) {
            assertEquals(gain.toDouble(), measuredGain(48_000, 1000.0,
                EqualizerSettings(enabled = true, preampDb = gain)), 0.02)
        }
        assertEquals(2.0, measuredGain(48_000, 1000.0, singleBand(16, 6f).copy(preampDb = -4f)), 0.03)
    }

    @Test fun stereoChannelsHaveIndependentFilterHistories() {
        val settings = EqualizerPreset.ROCK.settings()
        val left = tone(48_000, 100.0, 48_000)
        val right = ShortArray(left.size).also { it[3] = 3000 }
        val stereo = ShortArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        val output = render(EqualizerDsp { settings }.apply { configure(48_000, 2) }, stereo)
        val leftOutput = render(EqualizerDsp { settings }.apply { configure(48_000, 1) }, left)
        val rightOutput = render(EqualizerDsp { settings }.apply { configure(48_000, 1) }, right)
        assertArrayEquals(leftOutput, ShortArray(left.size) { output[it * 2] })
        assertArrayEquals(rightOutput, ShortArray(right.size) { output[it * 2 + 1] })
        assertTrue("Impulse must exercise filter memory", rightOutput.drop(4).any { it != 0.toShort() })
    }

    @Test fun bufferBoundariesDoNotChangeTheResponse() {
        val settings = EqualizerPreset.VOCAL.settings()
        val samples = tone(48_000, 630.0, 20_000).flatMap { listOf(it, (-it).toShort()) }.toShortArray()
        val expected = render(EqualizerDsp { settings }.apply { configure(48_000, 2) }, samples)
        val dsp = EqualizerDsp { settings }.apply { configure(48_000, 2) }
        val actual = ShortArray(samples.size)
        var offset = 0
        var chunk = 0
        val frames = intArrayOf(1, 7, 63, 1024, 17, 333)
        while (offset < samples.size) {
            val length = (frames[chunk++ % frames.size] * 2).coerceAtMost(samples.size - offset)
            render(dsp, samples.copyOfRange(offset, offset + length)).copyInto(actual, offset)
            offset += length
        }
        assertArrayEquals(expected, actual)
    }

    @Test fun liveGainChangesFadeAndConvergeToTheLatestSnapshot() {
        var settings = EqualizerSettings()
        val dsp = EqualizerDsp { settings }.apply { configure(48_000, 1) }
        val initial = render(dsp, ShortArray(100) { 1000 })
        settings = EqualizerSettings(enabled = true, preampDb = 10f)
        val beginning = render(dsp, ShortArray(480) { 1000 })
        assertTrue(beginning.first() in 1001..1004)
        assertTrue(beginning.last() in 2000..2200)
        settings = EqualizerSettings(enabled = true, preampDb = -10f)
        val newest = render(dsp, ShortArray(4800) { 1000 })
        val combined = initial + beginning + newest
        assertTrue("Preamp change must be a ramp", largestStep(combined) <= 4)
        assertTrue(newest.takeLast(1000).all { it == 316.toShort() })
        settings = settings.copy(enabled = false)
        val disabled = render(dsp, ShortArray(2400) { 1000 })
        assertTrue(largestStep(disabled) <= 2)
        assertArrayEquals(ShortArray(1000) { 1000 }, disabled.takeLast(1000).toShortArray())
    }

    @Test fun rapidFilterUpdatesStayBoundedAndFinishAtFlat() {
        var settings = singleBand(16, 12f)
        val dsp = EqualizerDsp { settings }.apply { configure(48_000, 2) }
        val wave = tone(48_000, 1000.0, 48_000, amplitude = 500.0)
        var offset = 0
        repeat(12) { iteration ->
            settings = singleBand(16, if (iteration % 2 == 0) -12f else 12f)
            val input = wave.copyOfRange(offset, offset + 480).flatMap { listOf(it, it) }.toShortArray()
            val output = render(dsp, input)
            assertTrue(output.all { abs(it.toInt()) < 4000 })
            assertTrue(largestStep(ShortArray(output.size / 2) { output[it * 2] }) < 1000)
            assertArrayEquals(ShortArray(output.size / 2) { output[it * 2] },
                ShortArray(output.size / 2) { output[it * 2 + 1] })
            offset += 480
        }
        settings = EqualizerPreset.FLAT.settings()
        val ending = wave.copyOfRange(offset, offset + 9600).flatMap { listOf(it, it) }.toShortArray()
        val output = render(dsp, ending)
        assertArrayEquals(ending.takeLast(9600).toShortArray(), output.takeLast(9600).toShortArray())
    }

    @Test fun flushDiscardsHistoryAndReconfigurationUsesTheNewRateAndChannels() {
        var settings = singleBand(16, 12f)
        val dsp = EqualizerDsp { settings }.apply { configure(48_000, 1) }
        val impulse = ShortArray(100).also { it[0] = 2000 }
        val initial = render(dsp, impulse)
        assertTrue(initial.drop(1).any { it != 0.toShort() })
        dsp.flush()
        assertArrayEquals(ShortArray(200), render(dsp, ShortArray(200)))
        dsp.flush()
        assertArrayEquals(initial, render(dsp, impulse))
        settings = settings.copy(enabled = false)
        dsp.flush()
        assertArrayEquals(impulse, render(dsp, impulse))
        settings = singleBand(16, 6f)
        dsp.configure(96_000, 2)
        val stereo = tone(96_000, 1000.0, 192_000).flatMap { listOf(it, it) }.toShortArray()
        val output = render(dsp, stereo)
        assertEquals(6.0, gain(stereo, output, 192_000), 0.04)
        dsp.reset()
        assertTrue(runCatching { render(dsp, shortArrayOf(1, 2)) }.isFailure)
        dsp.configure(8_000, 1)
        assertArrayEquals(ShortArray(500), render(dsp, ShortArray(500)))
    }

    @Test fun nyquistBandsAreBypassedWithoutMovingThemToAnotherFrequency() {
        for (sampleRate in listOf(8_000, 16_000, 22_050, 32_000, 40_000)) {
            val settings = EqualizerSettings(enabled = true,
                bandGainsDb = EqualizerBands.frequenciesHz.map { if (it >= sampleRate / 2.0) 12f else 0f })
            val samples = tone(sampleRate, 500.0, sampleRate)
            assertArrayEquals("$sampleRate Hz", samples,
                render(EqualizerDsp { settings }.apply { configure(sampleRate, 1) }, samples))
        }
        for (sampleRate in listOf(8_000, 22_050, 32_000, 44_100, 48_000, 96_000, 192_000)) {
            assertEquals("$sampleRate Hz", 6.0, measuredGain(sampleRate, 1000.0, singleBand(16, 6f)), 0.04)
        }
    }

    @Test fun positiveGainSaturatesInsteadOfWrappingAndExtremeCurvesDecay() {
        val preamp = EqualizerDsp { EqualizerSettings(enabled = true, preampDb = 10f) }
            .apply { configure(48_000, 1) }
        assertArrayEquals(shortArrayOf(32767, -32768, 0), render(preamp, shortArrayOf(30_000, -30_000, 0)))
        for (sampleRate in listOf(8_000, 40_001, 44_100, 192_000)) {
            for (gain in listOf(-12f, 12f)) {
                val dsp = EqualizerDsp { EqualizerSettings(true, 10f, List(EqualizerBands.COUNT) { gain }) }
                    .apply { configure(sampleRate, 1) }
                val impulse = ShortArray(sampleRate * 3).also { it[0] = 1000 }
                val output = render(dsp, impulse)
                assertTrue(output.any { it != 0.toShort() })
                assertTrue("Filter tail at $sampleRate Hz/$gain dB did not decay",
                    output.takeLast(sampleRate / 10).all { abs(it.toInt()) <= 1 })
            }
        }
    }

    @Test fun presetsAreDistinctCompleteCurvesAndChangeTheIntendedFrequencyRanges() {
        val presets = EqualizerPreset.entries.map { it.settings() }
        assertEquals(7, presets.distinct().size)
        assertTrue(presets.all { it.enabled && it.bandGainsDb.size == 30 && it.preampDb <= 0f })
        assertTrue(EqualizerPreset.FLAT.settings().bandGainsDb.all { it == 0f })
        val bass = EqualizerPreset.BASS_BOOST.settings()
        assertTrue(measuredGain(48_000, 50.0, bass) > measuredGain(48_000, 4000.0, bass) + 4)
        val treble = EqualizerPreset.TREBLE_BOOST.settings()
        assertTrue(measuredGain(48_000, 10_000.0, treble) > measuredGain(48_000, 100.0, treble) + 4)
    }

    private fun singleBand(band: Int, gain: Float) = EqualizerSettings(enabled = true,
        bandGainsDb = List(EqualizerBands.COUNT) { if (it == band) gain else 0f })

    private fun measuredGain(rate: Int, frequency: Double, settings: EqualizerSettings): Double {
        val input = tone(rate, frequency, rate * 2)
        val output = render(EqualizerDsp { settings }.apply { configure(rate, 1) }, input)
        return gain(input, output, rate)
    }

    private fun gain(input: ShortArray, output: ShortArray, skip: Int): Double {
        var before = 0.0
        var after = 0.0
        for (index in skip until input.size) {
            before += input[index].toDouble() * input[index]
            after += output[index].toDouble() * output[index]
        }
        return 20.0 * log10(sqrt(after / before))
    }

    private fun tone(rate: Int, frequency: Double, frames: Int, amplitude: Double = 2000.0): ShortArray =
        ShortArray(frames) { (sin(2 * PI * frequency * it / rate) * amplitude).roundToInt().toShort() }

    private fun render(dsp: EqualizerDsp, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        dsp.process(input, output)
        assertEquals(input.limit(), input.position())
        assertEquals(samples.size * 2, output.position())
        output.flip()
        return ShortArray(samples.size) { output.short }
    }

    private fun largestStep(samples: ShortArray): Int = samples.asList().zipWithNext { a, b -> abs(a.toInt() - b) }.maxOrNull() ?: 0
}
