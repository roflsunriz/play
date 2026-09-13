package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClientTokenPlatformDataTest {
    @Test
    fun androidVersionIsTextAndScreenFieldsAreDimensions() {
        val data = NativeAndroidData(
            screen = AndroidScreen(1080, 1920, 360, 420, 420),
            osVersion = "16", apiLevel = 36, name = "test-device", model = "test-model",
        )
        val fields = read(data.encode())
        assertEquals("16", (fields.getValue(2) as ByteArray).toString(Charsets.UTF_8))
        assertEquals(36L, fields[3])
        val screen = read(fields.getValue(1) as ByteArray)
        assertEquals(listOf(1080L, 1920L, 360L, 420L, 420L), (1..5).map(screen::get))
        assertFalse(fields.containsKey(9))
        assertFalse(fields.containsKey(10))
    }

    private fun read(bytes: ByteArray): Map<Int, Any> {
        val reader = ProtoWire.Reader(bytes)
        val result = linkedMapOf<Int, Any>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            result[reader.fieldNumber(tag)] = when (reader.wireType(tag)) {
                0 -> reader.readVarint()
                2 -> reader.readBytes()
                else -> error("Unexpected field in encoded platform data")
            }
        }
        return result
    }
}
