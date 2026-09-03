package io.github.playmusic.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClientTokenProtoTest {
    @Test
    fun encodesClientDataRequestInWireOrder() {
        val request = ClientTokenRequest(
            clientId = "65b708073fc0480ea92a077233ca87bd",
            clientVersion = "9.1.78.2218",
            deviceId = "device-1",
        )

        val bytes = request.encode()

        // request_type(1) = varint 1
        // client_data(2) = message
        assertEquals(0x08, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt() and 0xFF)
        assertEquals(0x12, bytes[2].toInt() and 0xFF)
        assertNotNull(bytes)
    }

    @Test
    fun parsesChallengesResponseWithRawPrefix() {
        // Challenge { type(1) = HASH_CASH(3); hash_cash(4) { length(1)=20; prefix(2)=<32 raw bytes> } }
        val prefix = ByteArray(32) { it.toByte() }
        val hashCash = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, 20))
            write(ProtoWire.fieldBytes(2, prefix))
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
        assertArrayEquals(prefix, parsed.challenges?.challenges?.get(0)?.hashCash?.prefix)
        assertEquals(20, parsed.challenges?.challenges?.get(0)?.hashCash?.length)
    }

    @Test
    fun encodesHashCashAnswerAsBytes() {
        val suffix = ByteArray(16) { (0x10 + it).toByte() }

        val bytes = HashCashAnswer(suffix).encode()

        // field 1 (suffix) must be length-delimited containing the raw bytes
        assertEquals(0x0A, bytes[0].toInt() and 0xFF)
        assertEquals(16, bytes[1].toInt() and 0xFF)
        assertArrayEquals(suffix, bytes.copyOfRange(2, 18))
    }

    @Test
    fun encodesChallengeAnswersInWireOrder() {
        val answer = HashCashAnswer(ByteArray(16) { 1 })

        val bytes = ClientTokenRequest(
            clientId = "id",
            clientVersion = "v",
            deviceId = "dev",
        ).encodeChallengeAnswers("state-xyz", answer)

        // request_type(1) = CHALLENGE_ANSWERS_REQUEST(2)
        // challenge_answers(3) = message
        assertEquals(0x08, bytes[0].toInt() and 0xFF)
        assertEquals(0x02, bytes[1].toInt() and 0xFF)
        assertEquals(0x1A, bytes[2].toInt() and 0xFF)
        assertNotNull(bytes)
    }
}