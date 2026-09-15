package io.github.playmusic.data.playback

import io.github.playmusic.data.auth.ProtoParseException
import io.github.playmusic.data.auth.ProtoWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AutomixMetadataTest {
    @Test fun measuredExtensionReplyKeepsTypesIdentitiesAndBeatTiming() {
        val values = AutomixMetadata.response(fixture(), keys.toSet())
        assertTrue(AutomixMetadata.defaultMode(checkNotNull(values[keys[0]])))
        val from = AutomixMetadata.cues(checkNotNull(values[keys[1]]))
        val to = AutomixMetadata.cues(checkNotNull(values[keys[2]]))
        assertEquals(183_867L, from.outgoing.first().positionMs)
        assertEquals(128f, from.outgoing.first().tempo, 0f)
        assertEquals(7_825L, to.incoming.first().positionMs)
        assertEquals(123f, to.incoming.first().tempo, 0f)
        for (seconds in 1..12) {
            val mix = checkNotNull(AutomixMetadata.transition(TRACK1, TRACK2, from, to, seconds))
            assertEquals(TRACK1, mix.fromUri)
            assertEquals(TRACK2, mix.toUri)
            assertEquals(183_867L, mix.outgoingStartMs)
            assertEquals(7_825L, mix.incomingStartMs)
            assertEquals(128f / 123, mix.incomingSpeed, .00001f)
            assertTrue(mix.durationMs in 1_000..seconds * 1_000L)
        }
    }
    @Test fun unsupportedIsDistinctFromMissingWrongTypedAmbiguousAndFailedReplies() {
        val key = keys[0]
        assertNull(AutomixMetadata.response(response(key, 404, null), setOf(key))[key])
        assertFalse(AutomixMetadata.defaultMode(ProtoWire.fieldVarint(1, 0)))
        assertFalse(AutomixMetadata.defaultMode(ProtoWire.fieldVarint(1, 15)))
        val emptyMode = response(key, 200, ProtoWire.fieldString(1, "type.googleapis.com/spotify.automix.proto.AutomixMode"))
        val emptyValue = checkNotNull(AutomixMetadata.response(emptyMode, setOf(key))[key])
        assertTrue(emptyValue.isEmpty())
        assertFalse(AutomixMetadata.defaultMode(emptyValue))
        val cueKey = keys[1]
        val emptyCues = response(cueKey, 200, ProtoWire.fieldString(1, "type.googleapis.com/spotify.automix.proto.Cuepoints"))
        assertTrue(AutomixMetadata.cues(checkNotNull(AutomixMetadata.response(emptyCues, setOf(cueKey))[cueKey])).incoming.isEmpty())
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(response(key, 200, byteArrayOf()), setOf(key)) }
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(response(key, 500, null), setOf(key)) }
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(byteArrayOf(), setOf(key)) }
        val wrong = response(key, 200, ProtoWire.fieldString(1, "type.googleapis.com/other.Mode") + ProtoWire.fieldBytes(2, byteArrayOf(8, 1)))
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(wrong, setOf(key)) }
        val duplicate = response(key, 404, null)
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(duplicate + duplicate, setOf(key)) }
        assertThrows(IllegalStateException::class.java) { AutomixMetadata.response(fixture(), setOf(AutomixMetadata.Key("wrong-uri", 28))) }
    }
    @Test fun unreliableCuepointsOrExtremeTempoChangesKeepNormalTransitions() {
        fun cues(tempo: Float, confidence: Double = 1.0) = AutomixMetadata.Cues(
            listOf(AutomixMetadata.Cue(1_000, tempo, confidence)), listOf(AutomixMetadata.Cue(100_000, tempo, confidence)))
        val valid = cues(120f)
        for (candidate in listOf(cues(60f), cues(240f), cues(120f, .89), cues(Float.NaN), cues(120f, Double.NaN))) {
            assertNull(AutomixMetadata.transition(TRACK1, TRACK2, valid, candidate, 5))
        }
        assertNotNull(AutomixMetadata.transition(TRACK1, TRACK2, valid, cues(125f), 5))
    }

    @Test fun actualFileEndClipsTheFadeWithoutChangingCuesOrTempo() {
        val original = AutomixTransition(TRACK1, TRACK2, 183_867, 7_825, 5_625, 128f / 123)
        val clipped = checkNotNull(original.fitWithin(186_209, 200_000))
        assertEquals(2_342L, clipped.durationMs)
        assertEquals(original.copy(durationMs = 2_342), clipped)
        val synthetic = AutomixTransition(TRACK1, TRACK2, 8_000, 2_000, 5_000, 1.02f)
        assertEquals(4_000L, checkNotNull(synthetic.fitWithin(12_000, 12_000)).durationMs)
        val limitedByIncoming = checkNotNull(synthetic.fitWithin(20_000, 5_000))
        assertEquals(((5_000 - 2_000).toDouble() / 1.02f).toLong(), limitedByIncoming.durationMs)
        assertTrue(limitedByIncoming.incomingStartMs + limitedByIncoming.durationMs * limitedByIncoming.incomingSpeed <= 5_000)
        assertNull(synthetic.fitWithin(8_999, 12_000))
        assertNull(synthetic.fitWithin(8_000, 12_000))
        assertNull(synthetic.fitWithin(12_000, 2_000))
        assertNull(synthetic.fitWithin(-1, 12_000))
    }

    @Test fun schedulerDelayAdvancesTheIncomingPhaseAndPreservesTheTransitionEnd() {
        val recipe = checkNotNull(AutomixTransition(TRACK1, TRACK2, 183_867, 7_825, 5_625, 128f / 123)
            .fitWithin(186_209, 212_459))
        val delayed = checkNotNull(recipe.advanceTo(184_110))
        assertEquals(184_110L, delayed.outgoingStartMs)
        assertEquals(8_077L, delayed.incomingStartMs)
        assertEquals(2_099L, delayed.durationMs)
        assertEquals(recipe.incomingSpeed, delayed.incomingSpeed, 0f)
        assertEquals(recipe.outgoingStartMs + recipe.durationMs, delayed.outgoingStartMs + delayed.durationMs)
        assertEquals(recipe, recipe.advanceTo(183_000))
        assertEquals(1_000L, checkNotNull(recipe.advanceTo(185_209)).durationMs)
        assertNull(recipe.advanceTo(185_210))
        assertNull(recipe.advanceTo(186_209))
    }
    @Test fun fixedWidthReadsRespectLittleEndianAndRejectTruncationWithoutAdvancing() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(123f).putDouble(.9375).array()
        val reader = ProtoWire.Reader(bytes)
        assertEquals(123f, reader.readFloat(), 0f)
        assertEquals(.9375, reader.readDouble(), 0.0)
        assertFalse(reader.hasNext())
        for (length in 0..3) assertThrows(ProtoParseException::class.java) { ProtoWire.Reader(bytes.copyOf(length)).readFloat() }
        for (length in 0..7) assertThrows(ProtoParseException::class.java) { ProtoWire.Reader(bytes.copyOf(length)).readDouble() }
    }
    private fun response(key: AutomixMetadata.Key, status: Int, any: ByteArray?): ByteArray = ProtoWire.fieldMessage(2,
        ProtoWire.fieldMessage(1, ProtoWire.fieldVarint(1, 200)) + ProtoWire.fieldVarint(2, key.kind) +
            ProtoWire.fieldMessage(3, ProtoWire.fieldMessage(1, ProtoWire.fieldVarint(1, status)) +
                ProtoWire.fieldString(2, key.uri) + (any?.let { ProtoWire.fieldMessage(3, it) } ?: byteArrayOf())))

    companion object {
        const val CONTEXT = "spotify:playlist:0000000000000000000001"
        const val TRACK1 = "spotify:track:0000000000000000000001"
        const val TRACK2 = "spotify:track:0000000000000000000002"
        private val keys = listOf(AutomixMetadata.Key(CONTEXT, 27), AutomixMetadata.Key(TRACK1, 28), AutomixMetadata.Key(TRACK2, 28))
        // Live 2026-09-15 reply: identities replaced and cache validators removed; cuepoint payloads unchanged.
        fun fixture(): ByteArray = checkNotNull(AutomixMetadataTest::class.java.getResourceAsStream("/automix-default.pb")).use { it.readBytes() }
    }
}
