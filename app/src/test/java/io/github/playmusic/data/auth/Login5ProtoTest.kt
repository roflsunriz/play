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

    @Test
    fun parsesCodeChallenge() {
        // Real captured: Challenges{ Challenge{ code{ f1=2, f2=6, f3=300, f5="email" } } }
        val code = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, 2))
            write(ProtoWire.fieldVarint(2, 6))
            write(ProtoWire.fieldVarint(3, 300))
            write(ProtoWire.fieldString(5, "r***z@y***.co.jp"))
        }.toByteArray()
        val challenge = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(2, code))
        }.toByteArray()
        val challenges = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(1, challenge))
        }.toByteArray()
        val response = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(3, challenges))
            write(ProtoWire.fieldBytes(5, byteArrayOf(0x04, 0x00)))
        }.toByteArray()

        val parsed = LoginResponse.parse(response)

        assertEquals(1, parsed.challenges.size)
        assertEquals("r***z@y***.co.jp", parsed.challenges[0].code?.maskedTarget)
        assertEquals(null, parsed.challenges[0].hashcash)
        assertArrayEquals(byteArrayOf(0x04, 0x00), parsed.loginContext)
    }

    @Test
    fun encodesCodeSolutionAndAuthFlow() {
        val request = LoginRequest(
            clientInfo = ClientInfo("9a8d2f0ce77a4e248bb71fefcb557637", "device-1"),
            loginContext = byteArrayOf(0x04, 0x00),
            challengeSolutions = listOf(
                ChallengeSolution(code = CodeSolution("749769")),
            ),
            authFlow = LoginAuthFlow(
                redirectUri = "https://auth-callback.spotify.com/r/android/music/login",
                callbackUuid = "54b6a0a6-1901-4353-95d4-eb4e34997323",
                language = "ja",
            ),
            clientRequestId = "68f3bde3-bfc0-4ac8-a122-a7c6b94a8d73",
            password = LoginPassword("user@example.com", "pass"),
        )

        val bytes = request.encode()

        // f1 client_info, f2 login_context, f3 challenge_solutions, f4 auth_flow, f6 client_request_id, f111 password
        val reader = ProtoWire.Reader(bytes)
        val seen = mutableSetOf<Int>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            seen.add(reader.fieldNumber(tag))
            when (reader.fieldNumber(tag)) {
                3 -> {
                    val solutions = reader.readBytes()
                    val r2 = ProtoWire.Reader(solutions)
                    while (r2.hasNext()) {
                        val t2 = r2.readTag()
                        if (r2.fieldNumber(t2) == 1) {
                            val sol = r2.readBytes()
                            // ChallengeSolution: f2=code solution
                            val r3 = ProtoWire.Reader(sol)
                            while (r3.hasNext()) {
                                val t3 = r3.readTag()
                                if (r3.fieldNumber(t3) == 2) {
                                    val codeSol = r3.readBytes()
                                    assertEquals("749769", String(codeSol.copyOfRange(2, codeSol.size), Charsets.UTF_8))
                                }
                            }
                        }
                    }
                }
                111 -> {
                    val pw = reader.readBytes()
                    val text = String(pw, Charsets.UTF_8)
                    assertTrue(text.contains("user@example.com"))
                }
                else -> {
                    // Fallback: skip unknown length-delimited or varint fields safely.
                    when (reader.wireType(tag)) {
                        0 -> reader.readVarint()
                        2 -> reader.readBytes()
                        else -> reader.skip(reader.wireType(tag))
                    }
                }
            }
        }
        assertTrue(1 in seen)
        assertTrue(2 in seen)
        assertTrue(3 in seen)
        assertTrue(4 in seen)
        assertTrue(6 in seen)
        assertTrue(111 in seen)
    }
}
