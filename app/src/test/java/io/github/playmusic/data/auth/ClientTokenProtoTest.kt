package io.github.playmusic.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URI

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

                val inner = ProtoWire.Reader(challengeAnswers)
                while (inner.hasNext()) {
                    val innerTag = inner.readTag()
                    if (inner.fieldNumber(innerTag) == 2) {
                        val answer = inner.readBytes()
                        // ChallengeAnswer must start with f1 varint 3 (HASH_CASH)
                        assertEquals(0x08, answer[0].toInt() and 0xFF)
                        assertEquals(0x03, answer[1].toInt() and 0xFF)
                        seenTag++
                    } else inner.skip(inner.wireType(innerTag))
                }
            } else reader.skip(reader.wireType(tag))
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

    @Test
    fun grantedDomainsRestrictCredentialsToTheirHttpsHosts() {
        val grant = ProtoWire.fieldString(1, "synthetic-secret-token") + ProtoWire.fieldVarint(2, 120) +
            ProtoWire.fieldVarint(3, 60) + ProtoWire.fieldMessage(9, byteArrayOf(1, 2, 3)) +
            ProtoWire.fieldMessage(4, ProtoWire.fieldString(1, ".EXAMPLE.test.")) +
            ProtoWire.fieldMessage(4, ProtoWire.fieldString(1, "example.test"))
        val token = GrantedClientToken.parse(grant)
        assertEquals(listOf("example.test"), token.domains)
        assertTrue(token.allows(URI("https://example.test/path")))
        assertTrue(token.allows(URI("https://license.example.test:443/path")))
        for (url in listOf("https://evilexample.test", "https://example.test.invalid", "http://example.test",
            "https://example.test:8080", "https://user@example.test")) {
            assertFalse(url, token.allows(URI(url)))
        }
        assertFalse(token.toString().contains("synthetic-secret-token"))
    }

    @Test
    fun malformedGrantLifetimeAndDomainAreRejected() {
        val identity = ProtoWire.fieldString(1, "synthetic-token")
        for (grant in listOf(
            identity,
            identity + ProtoWire.fieldVarint(2, -1),
            identity + ProtoWire.fieldVarint(2, Int.MAX_VALUE.toLong() + 1),
            identity + ProtoWire.fieldString(2, "120"),
            identity + ProtoWire.fieldVarint(2, 120) + ProtoWire.fieldMessage(4, ProtoWire.fieldString(1, "example.test/path")),
        )) {
            assertTrue(runCatching { GrantedClientToken.parse(grant) }.exceptionOrNull() is ProtoParseException)
        }
    }

    @Test
    fun responseTypeMustMatchItsGrantOrChallengePayload() {
        val grant = ProtoWire.fieldMessage(2, ProtoWire.fieldString(1, "synthetic-token") + ProtoWire.fieldVarint(2, 120))
        assertTrue(runCatching { ClientTokenResponse.parse(ProtoWire.fieldVarint(1, 2) + grant) }
            .exceptionOrNull() is ProtoParseException)
        assertTrue(runCatching { ClientTokenResponse.parse(grant) }.exceptionOrNull() is ProtoParseException)
    }
}
