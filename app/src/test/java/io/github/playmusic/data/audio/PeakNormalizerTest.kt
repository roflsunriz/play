package io.github.playmusic.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PeakNormalizerTest {
    @Test fun disabledIsBitExactAndSilenceCannotBuildExtremeGain() {
        val dsp = PeakNormalizer().apply { reset(48_000) }
        val frame = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        val copy = frame.copyOf()
        dsp.process(frame, 2, false)
        assertArrayEquals(copy, frame)
        repeat(480_000) {
            val silence = shortArrayOf(1, -1)
            dsp.process(silence, 2, true)
            assertArrayEquals(shortArrayOf(1, -1), silence)
        }
        val signal = shortArrayOf(8_000, -4_000)
        dsp.process(signal, 2, true)
        assertTrue(abs(signal[0].toInt() - 8_000) <= 1)
    }
    @Test fun quietTracksRiseToTheGainLimitWithoutChangingStereoBalance() {
        val dsp = PeakNormalizer().apply { reset(8_000) }
        var latest = shortArrayOf()
        repeat(8_000 * 20) {
            latest = shortArrayOf(1_000, -500)
            dsp.process(latest, 2, true)
            assertTrue(latest[0] <= 3_982)
            assertTrue(abs(latest[0] + latest[1] * 2) <= 1)
        }
        assertTrue(abs(latest[0].toInt() - 3_981) <= 1)
    }
    @Test fun unexpectedLoudPeakIsLimitedImmediatelyAndPeakIsHeldForTheTrack() {
        val dsp = PeakNormalizer().apply { reset(8_000) }
        repeat(80_000) { dsp.process(shortArrayOf(2_000), 1, true) }
        val loud = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        dsp.process(loud, 2, true)
        assertTrue(loud.all { abs(it.toInt()) <= 29_205 })
        var quiet = shortArrayOf()
        repeat(80_000) { quiet = shortArrayOf(1_000); dsp.process(quiet, 1, true) }
        assertEquals(891, quiet[0].toInt())
        dsp.reset(8_000)
        val nextTrack = shortArrayOf(1_000)
        dsp.process(nextTrack, 1, true)
        assertEquals(1_000, nextTrack[0].toInt())
    }
}
