package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtoWireTest {
    @Test
    fun maximumFieldNumberUsesAnUnsignedTag() {
        assertArrayEquals(byteArrayOf(0xfa.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f), ProtoWire.tag(0x1FFFFFFF, 2))
        val reader = ProtoWire.Reader(ProtoWire.fieldBytes(0x1FFFFFFF, byteArrayOf(1)))
        val tag = reader.readTag()
        assertEquals(0x1FFFFFFF, reader.fieldNumber(tag))
        assertEquals(2, reader.wireType(tag))
    }

    @Test
    fun skippingUnknownBytesKeepsTheFollowingFieldAligned() {
        for (size in listOf(0, 1, 127, 128, 300)) {
            val reader = ProtoWire.Reader(
                ProtoWire.fieldBytes(20, ByteArray(size) { 0x7f }) + ProtoWire.fieldString(1, "next"),
            )
            reader.skip(reader.wireType(reader.readTag()))
            assertEquals(10, reader.readTag())
            assertEquals("next", reader.readString())
            assertFalse(reader.hasNext())
        }
    }

    @Test
    fun skipsFixedFieldsAndNestedGroups() {
        val bytes = byteArrayOf(0xA3.toByte(), 1, 0x0B, 0x12, 1, 7, 0x0C, 0xA4.toByte(), 1) +
            byteArrayOf(0xA9.toByte(), 1) + ByteArray(8) +
            byteArrayOf(0xB5.toByte(), 1) + ByteArray(4) + byteArrayOf(8, 42)
        val reader = ProtoWire.Reader(bytes)
        repeat(3) { reader.skip(reader.wireType(reader.readTag())) }
        assertEquals(8, reader.readTag())
        assertEquals(42L, reader.readVarint())
        assertFalse(reader.hasNext())
    }

    @Test
    fun rejectsTruncatedAndOverflowingFields() {
        val invalidFields = listOf(
            byteArrayOf(8),
            byteArrayOf(8, 0x80.toByte()),
            byteArrayOf(10, 2, 1),
            byteArrayOf(10) + ProtoWire.varint64(0x100000000L),
            byteArrayOf(9, 1),
            byteArrayOf(13, 1),
            byteArrayOf(11, 8, 1),
            byteArrayOf(12),
            byteArrayOf(8) + ByteArray(10) { 0xff.toByte() },
            byteArrayOf(15),
        )
        invalidFields.forEach { bytes ->
            assertThrows(ProtoParseException::class.java) {
                val reader = ProtoWire.Reader(bytes)
                reader.skip(reader.wireType(reader.readTag()))
            }
        }
        assertThrows(ProtoParseException::class.java) { ProtoWire.Reader(byteArrayOf()).readVarint() }
        assertThrows(ProtoParseException::class.java) { ProtoWire.Reader(byteArrayOf(0)).readTag() }
    }

    @Test
    fun supportsTheFullSignedVarintRange() {
        for (value in listOf(0L, 127L, 128L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
            assertEquals(value, ProtoWire.Reader(ProtoWire.varint64(value)).readVarint())
        }
        assertEquals(-1L, ProtoWire.Reader(ProtoWire.varint(-1)).readVarint())
    }
}
