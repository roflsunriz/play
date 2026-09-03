package io.github.playmusic.data.auth

import java.io.ByteArrayOutputStream

object ProtoWire {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4

    fun varint(value: Int): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while (remaining >= 0x80) {
            out.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
        return out.toByteArray()
    }

    fun tag(fieldNumber: Int, wireType: Int): ByteArray = varint((fieldNumber shl 3) or wireType)

    fun fieldVarint(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(fieldNumber, WIRE_VARINT))
        out.write(varint64(value))
        return out.toByteArray()
    }

    fun fieldVarint(fieldNumber: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(fieldNumber, WIRE_VARINT))
        out.write(varint(value))
        return out.toByteArray()
    }

    fun varint64(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while ((remaining and -0x80L) != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    fun fieldBytes(fieldNumber: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(fieldNumber, WIRE_LENGTH_DELIMITED))
        out.write(varint(value.size))
        out.write(value)
        return out.toByteArray()
    }

    fun fieldString(fieldNumber: Int, value: String): ByteArray =
        fieldBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun fieldMessage(fieldNumber: Int, value: ByteArray): ByteArray = fieldBytes(fieldNumber, value)

    class Reader(private val data: ByteArray, private var position: Int = 0) {
        fun hasNext(): Boolean = position < data.size

        fun readTag(): Int {
            val tagValue = readVarint()
            return tagValue.toInt()
        }

        fun fieldNumber(tag: Int): Int = tag ushr 3

        fun wireType(tag: Int): Int = tag and 0x7

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = data[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (position >= data.size) throw ProtoParseException("Unexpected end of data in varint")
                if (shift >= 64) throw ProtoParseException("Varint too long")
            }
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= data.size) { "Invalid length-delimited field" }
            val result = data.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> position += 8
                WIRE_LENGTH_DELIMITED -> position += readVarint().toInt()
                WIRE_FIXED32 -> position += 4
                WIRE_START_GROUP -> skipGroup()
                WIRE_END_GROUP -> Unit
                else -> throw ProtoParseException("Unsupported wire type $wireType")
            }
        }

        private fun skipGroup() {
            var depth = 1
            while (depth > 0) {
                val tag = readTag()
                when (wireType(tag)) {
                    WIRE_START_GROUP -> depth++
                    WIRE_END_GROUP -> depth--
                    WIRE_VARINT -> readVarint()
                    WIRE_FIXED64 -> position += 8
                    WIRE_LENGTH_DELIMITED -> position += readVarint().toInt()
                    WIRE_FIXED32 -> position += 4
                    else -> throw ProtoParseException("Unsupported wire type ${wireType(tag)} in group")
                }
            }
        }
    }

    class ProtoParseException(message: String) : Exception(message)
}
