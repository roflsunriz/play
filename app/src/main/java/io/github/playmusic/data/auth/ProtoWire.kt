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
        return varint64(value.toLong())
    }

    fun tag(fieldNumber: Int, wireType: Int): ByteArray {
        require(fieldNumber in 1..0x1FFFFFFF && wireType in 0..5)
        return varint64((fieldNumber.toLong() shl 3) or wireType.toLong())
    }

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
            if (tagValue !in 1..0xFFFFFFFFL || tagValue ushr 3 == 0L) {
                throw ProtoParseException("Invalid protobuf tag")
            }
            return tagValue.toInt()
        }

        fun fieldNumber(tag: Int): Int = tag ushr 3

        fun wireType(tag: Int): Int = tag and 0x7

        fun readVarint(): Long {
            var result = 0L
            for (index in 0..9) {
                if (!hasNext()) throw ProtoParseException("Unexpected end of data in varint")
                val byte = data[position++].toInt() and 0xFF
                if (index == 9 && byte and 0xFE != 0) throw ProtoParseException("Varint too long")
                result = result or ((byte and 0x7F).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return result
            }
            throw ProtoParseException("Varint too long")
        }

        fun readBytes(): ByteArray {
            val length = readLength()
            val result = data.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readFloat(): Float {
            val start = position
            advance(4)
            var bits = 0
            for (index in 0..3) bits = bits or ((data[start + index].toInt() and 255) shl (index * 8))
            return Float.fromBits(bits)
        }

        fun readDouble(): Double {
            val start = position
            advance(8)
            var bits = 0L
            for (index in 0..7) bits = bits or ((data[start + index].toLong() and 255) shl (index * 8))
            return Double.fromBits(bits)
        }

        private fun readLength(): Int {
            val length = readVarint()
            if (length < 0 || length > data.size - position) {
                throw ProtoParseException("Invalid length-delimited field")
            }
            return length.toInt()
        }

        private fun advance(length: Int) {
            if (length < 0 || length > data.size - position) {
                throw ProtoParseException("Unexpected end of data in field")
            }
            position += length
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH_DELIMITED -> {
                    // Read the length before advancing: += evaluates the old position first.
                    val length = readLength()
                    advance(length)
                }
                WIRE_FIXED32 -> advance(4)
                WIRE_START_GROUP -> skipGroup()
                WIRE_END_GROUP -> throw ProtoParseException("Unexpected end of group")
                else -> throw ProtoParseException("Unsupported wire type $wireType")
            }
        }

        private fun skipGroup() {
            var depth = 1
            while (depth > 0) {
                if (position >= data.size) throw ProtoParseException("Unexpected end of data in group")
                val tag = readTag()
                when (wireType(tag)) {
                    WIRE_START_GROUP -> depth++
                    WIRE_END_GROUP -> depth--
                    else -> skip(wireType(tag))
                }
            }
        }
    }
}

class ProtoParseException(message: String) : Exception(message)

