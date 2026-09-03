package io.github.playmusic.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTokenProtoTest {

    @Test
    fun encodesClientDataRequestInWireOrder() {
        val request = ClientTokenRequest(
            clientId = "9a8d2f0ce77a4e248bb71fefcb557637",
            clientVersion = "9.1.78.2218",
            deviceId = "device-1",
        )

        val bytes = request.encode()

        assertEquals(0x08, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt() and 0xFF)
        assertEquals(0x12, bytes[2].toInt() and 0xFF)
        assertNotNull(bytes)
    }

    @Test
    fun parsesChallengesResponseWithHexPrefix() {
        val prefixHex = "112C47D95C20F1285BBC94606A7C4C4E"
        val hashCash = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, 20))
            write(ProtoWire.fieldString(2, prefixHex))
        }.toByteArray()
        val challenge = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, ChallengeType.HASH_CASH.code))
            write(ProtoWire.fieldMessage(4, hashCash))
        }.toByteArray()
        val challenges = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldString(1, "state-123"))
            write(ProtoWire.fieldMessage(2, challenge))
        }.toByteArray()
        val response = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, ClientTokenResponseType.CHALLENGES_RESPONSE.code))
            write(ProtoWire.fieldMessage(3, challenges))
        }.toByteArray()

        val parsed = ClientTokenResponse.parse(response)

        assertEquals(ClientTokenResponseType.CHALLENGES_RESPONSE.code, parsed.responseType)
        assertEquals("state-123", parsed.challenges?.state)
        assertEquals(1, parsed.challenges?.challenges?.size)
        assertEquals(ChallengeType.HASH_CASH.code, parsed.challenges?.challenges?.get(0)?.type?.code)
        assertArrayEquals(prefixHex.hexToBytes(), parsed.challenges?.challenges?.get(0)?.hashCash?.prefix)
        assertEquals(20, parsed.challenges?.challenges?.get(0)?.hashCash?.length)
    }

    @Test
    fun encodesHashCashAnswerAsUppercaseHexString() {
        val suffix = ByteArray(16) { (0x10 + it).toByte() }

        val bytes = HashCashAnswer(suffix).encode()

        assertEquals(0x0A, bytes[0].toInt() and 0xFF)
        val expected = suffix.joinToString("") { "%02X".format(it) }
        assertEquals(expected, String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun hexHelpersRoundTrip() {
        val raw = byteArrayOf(0x11, 0x2C, 0x47.toByte(), 0xD9.toByte())
        assertEquals("112C47D9", raw.toUppercaseHex())
        assertArrayEquals(raw, "112C47D9".hexToBytes())
    }

    @Test
    fun encodesChallengeAnswersFlatWithoutWrapper() {
        val suffix = ByteArray(16) { (0x10 + it).toByte() }
        val request = ClientTokenRequest("id", "v", "dev")

        val bytes = request.encodeChallengeAnswers("state-1", HashCashAnswer(suffix))

        assertEquals(0x08, bytes[0].toInt() and 0xFF)
        assertEquals(0x02, bytes[1].toInt() and 0xFF)
        assertEquals(0x1A, bytes[2].toInt() and 0xFF)

        val reader = ProtoWire.Reader(bytes)
        var seenTag = 0
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.fieldNumber(tag) == 3) {
                val challengeAnswers = reader.readBytes()
                // ChallengeAnswersRequest: f1(state)=string tag is 0x0A
                assertEquals(0x0A, challengeAnswers[0].toInt() and 0xFF)

                var inner = ProtoWire.Reader(challengeAnswers)
                while (inner.hasNext()) {
                    val innerTag = inner.readTag()
                    if (inner.fieldNumber(innerTag) == 2) {
                        val answer = inner.readBytes()
                        // ChallengeAnswer must start with f1 varint 3 (HASH_CASH)
                        assertEquals(0x08, answer[0].toInt() and 0xFF)
                        assertEquals(0x03, answer[1].toInt() and 0xFF)
                        seenTag++
                    }
                }
            }
        }
        assertEquals(1, seenTag)
    }

    @Test
    fun capturedChallengeStateIsReusedWithoutWrapper() {
        val suffix = ByteArray(16) { 0x01 }
        val request = ClientTokenRequest("id", "v", "dev")

        val bytes = request.encodeChallengeAnswers("STATE-TOKEN", HashCashAnswer(suffix))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("STATE-TOKEN"))
        assertTrue(text.contains(suffix.toUppercaseHex()))
    }
}
