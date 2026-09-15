package io.github.playmusic.data.playback

import io.github.playmusic.data.auth.ProtoWire
import kotlin.math.floor

/** Extension 27/28 contracts verified against both native descriptors and live replies. */
internal object AutomixMetadata {
    const val MODE = 27
    const val CUEPOINTS = 28
    private const val MODE_TYPE = "type.googleapis.com/spotify.automix.proto.AutomixMode"
    private const val CUE_TYPE = "type.googleapis.com/spotify.automix.proto.Cuepoints"
    data class Key(val uri: String, val kind: Int)
    data class Cue(val positionMs: Long, val tempo: Float, val confidence: Double) {
        val usable: Boolean get() = positionMs >= 0 && tempo.isFinite() && tempo in 40f..240f &&
            confidence.isFinite() && confidence in 0.9..1.0
    }
    data class Cues(val incoming: List<Cue>, val outgoing: List<Cue>)

    fun request(keys: List<Key>): ByteArray = keys.fold(byteArrayOf()) { bytes, key -> bytes +
        ProtoWire.fieldMessage(2, ProtoWire.fieldString(1, key.uri) + ProtoWire.fieldMessage(2, ProtoWire.fieldVarint(1, key.kind))) }

    fun response(bytes: ByteArray, keys: Set<Key>): Map<Key, ByteArray?> {
        require(bytes.size <= 2_000_000) { "Transition metadata is too large" }
        val result = linkedMapOf<Key, ByteArray?>()
        val root = ProtoWire.Reader(bytes)
        while (root.hasNext()) {
            val tag = root.readTag()
            if (tag != 18) { root.skip(root.wireType(tag)); continue }
            val array = ProtoWire.Reader(root.readBytes())
            var kind: Int? = null
            var status: Int? = null
            val entities = mutableListOf<ByteArray>()
            while (array.hasNext()) {
                when (val field = array.readTag()) {
                    10 -> status = status(array.readBytes())
                    16 -> kind = array.readVarint().toInt()
                    26 -> { check(entities.size < 500); entities += array.readBytes() }
                    else -> array.skip(array.wireType(field))
                }
            }
            if (keys.none { it.kind == kind }) continue
            if (status == 404 || status == 204) {
                keys.filter { it.kind == kind }.forEach { key -> check(!result.containsKey(key)); result[key] = null }
                continue
            }
            check(status == 200) { "Transition extension request failed" }
            for (data in entities) {
                val entity = ProtoWire.Reader(data)
                var uri: String? = null
                var entityStatus: Int? = null
                var payload: ByteArray? = null
                while (entity.hasNext()) {
                    when (val field = entity.readTag()) {
                        10 -> entityStatus = status(entity.readBytes())
                        18 -> uri = entity.readString()
                        26 -> payload = entity.readBytes()
                        else -> entity.skip(entity.wireType(field))
                    }
                }
                val key = Key(uri.orEmpty(), checkNotNull(kind))
                if (key !in keys) continue
                check(!result.containsKey(key)) { "Ambiguous transition identity" }
                result[key] = when (entityStatus) {
                    404, 204 -> null
                    200 -> unpack(checkNotNull(payload), if (kind == MODE) MODE_TYPE else CUE_TYPE)
                    else -> error("Transition entity request failed")
                }
            }
        }
        check(result.keys.containsAll(keys)) { "Transition metadata is missing the requested identity" }
        return result
    }

    /** Native automix_mode.proto: NONE=0, DEFAULT=1. Other styles require their own contracts. */
    fun defaultMode(bytes: ByteArray): Boolean {
        val reader = ProtoWire.Reader(bytes)
        var style = 0L
        while (reader.hasNext()) {
            when (val tag = reader.readTag()) { 8 -> style = reader.readVarint(); else -> reader.skip(reader.wireType(tag)) }
        }
        return style == 1L
    }

    fun cues(bytes: ByteArray): Cues {
        val reader = ProtoWire.Reader(bytes)
        var bestIn: Cue? = null
        var bestOut: Cue? = null
        val incoming = mutableListOf<Cue>()
        val outgoing = mutableListOf<Cue>()
        while (reader.hasNext()) {
            when (val tag = reader.readTag()) {
                10 -> bestIn = cue(reader.readBytes())
                18 -> bestOut = cue(reader.readBytes())
                26 -> { check(incoming.size < 500); incoming += cue(reader.readBytes()) }
                34 -> { check(outgoing.size < 500); outgoing += cue(reader.readBytes()) }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return Cues((listOfNotNull(bestIn) + incoming).distinct(), (listOfNotNull(bestOut) + outgoing).distinct())
    }

    fun transition(from: String, to: String, outgoing: Cues, incoming: Cues, requestedSeconds: Int): AutomixTransition? {
        require(requestedSeconds in 1..12)
        val out = outgoing.outgoing.firstOrNull { it.usable } ?: return null
        val into = incoming.incoming.firstOrNull { it.usable && out.tempo / it.tempo in 0.9f..1.1f } ?: return null
        val beatMs = 60_000.0 / out.tempo
        val beats = floor(requestedSeconds * 1_000.0 / beatMs)
        // Keep the user-visible one-second minimum even when one whole beat is longer,
        // or rounding two beats down would produce a sub-second fade.
        val duration = (beats * beatMs).toLong().coerceAtLeast(1_000)
        if (duration !in 1_000..12_000) return null
        return AutomixTransition(from, to, out.positionMs, into.positionMs, duration, out.tempo / into.tempo)
    }

    private fun cue(bytes: ByteArray): Cue {
        val reader = ProtoWire.Reader(bytes)
        var position = 0L
        var tempo = 0f
        var confidence = 0.0
        while (reader.hasNext()) {
            when (val tag = reader.readTag()) {
                8 -> position = reader.readVarint()
                21 -> tempo = reader.readFloat()
                33 -> confidence = reader.readDouble()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return Cue(position, tempo, confidence)
    }
    private fun status(bytes: ByteArray): Int {
        val reader = ProtoWire.Reader(bytes)
        var code: Int? = null
        while (reader.hasNext()) {
            when (val tag = reader.readTag()) { 8 -> code = reader.readVarint().toInt(); else -> reader.skip(reader.wireType(tag)) }
        }
        return checkNotNull(code) { "Missing extension status" }
    }
    private fun unpack(bytes: ByteArray, expectedType: String): ByteArray {
        val reader = ProtoWire.Reader(bytes)
        var type: String? = null
        var value: ByteArray? = null
        while (reader.hasNext()) {
            when (val tag = reader.readTag()) { 10 -> type = reader.readString(); 18 -> value = reader.readBytes(); else -> reader.skip(reader.wireType(tag)) }
        }
        check(type == expectedType) { "Unexpected transition metadata type" }
        // Proto3 omits bytes at their empty default (NONE mode / no cuepoints).
        return value ?: byteArrayOf()
    }
}
