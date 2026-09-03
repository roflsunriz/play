package io.github.playmusic.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Login5ProtoTest {
    @Test
    fun encodesPasswordRequestWithClientInfo() {
        val request = LoginRequest(
            clientInfo = ClientInfo("client-1", "device-1"),
            password = LoginPassword("user", "pass"),
        )

        val bytes = request.encode()
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun parsesSuccessResponse() {
        val ok = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldString(1, "user"))
            write(ProtoWire.fieldString(2, "token"))
            write(ProtoWire.fieldBytes(3, byteArrayOf(1, 2, 3)))
            write(ProtoWire.fieldVarint(4, 3600))
        }.toByteArray()
        val response = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(1, ok))
        }.toByteArray()

        val parsed = LoginResponse.parse(response)

        assertEquals("user", parsed.ok?.username)
        assertEquals("token", parsed.ok?.accessToken)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.ok?.storedCredential)
        assertEquals(3600, parsed.ok?.accessTokenExpiresIn)
        assertNull(parsed.error)
    }

    @Test
    fun parsesErrorResponse() {
        val response = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(2, Login5Error.INVALID_CREDENTIALS.code))
        }.toByteArray()

        val parsed = LoginResponse.parse(response)

        assertNull(parsed.ok)
        assertEquals(Login5Error.INVALID_CREDENTIALS.code, parsed.error)
    }

    @Test
    fun parsesHashcashChallenge() {
        val hashcash = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldBytes(1, byteArrayOf(0x01, 0x02)))
            write(ProtoWire.fieldVarint(2, 10))
        }.toByteArray()
        val challenge = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(1, hashcash))
        }.toByteArray()
        val challenges = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(1, challenge))
        }.toByteArray()
        val response = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(3, challenges))
            write(ProtoWire.fieldBytes(5, byteArrayOf(0x0A)))
        }.toByteArray()

        val parsed = LoginResponse.parse(response)

        assertEquals(1, parsed.challenges.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02), parsed.challenges[0].hashcash?.prefix)
        assertEquals(10, parsed.challenges[0].hashcash?.length)
        assertArrayEquals(byteArrayOf(0x0A), parsed.loginContext)
    }
}