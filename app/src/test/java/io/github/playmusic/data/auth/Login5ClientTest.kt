package io.github.playmusic.data.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class Login5ClientTest {
    @Test
    fun fatalErrorAfterTimeoutIsNotIgnored() = runTest {
        val requests = AtomicInteger()
        val client = client(requests, listOf(error(Login5Error.TIMEOUT), error(Login5Error.INVALID_CREDENTIALS), success()))
        val failure = runCatching { client.loginWithStoredCredential("synthetic-user", byteArrayOf(1), "device") }.exceptionOrNull()
        assertTrue(failure is SpotifyAuthException)
        assertTrue(failure?.message.orEmpty().contains("INVALID_CREDENTIALS"))
        assertEquals(2, requests.get())
    }

    @Test
    fun tooManyAttemptsDoesNotTriggerMoreSignInAttempts() = runTest {
        val requests = AtomicInteger()
        val client = client(requests, listOf(error(Login5Error.TOO_MANY_ATTEMPTS), success()))
        val failure = runCatching { client.loginWithPassword("synthetic-user", "test-password", "device") }.exceptionOrNull()
        assertTrue(failure is SpotifyAuthException)
        assertEquals(1, requests.get())
    }

    @Test
    fun malformedSuccessDoesNotCreateAnAuthenticatedSession() = runTest {
        val requests = AtomicInteger()
        val client = client(requests, listOf(ProtoWire.fieldBytes(1, ByteArray(0))))
        val failure = runCatching { client.loginWithPassword("synthetic-user", "test-password", "device") }.exceptionOrNull()
        assertTrue(failure is SpotifyAuthException)
    }

    private fun client(requests: AtomicInteger, responses: List<ByteArray>): SpotifyLogin5Client {
        val tokens = SpotifyClientTokenClient(openConnection = { uri -> Connection(uri) {
            ProtoWire.fieldVarint(1, 1) + ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "synthetic-sdk-token") + ProtoWire.fieldVarint(2, 3600))
        } })
        return SpotifyLogin5Client("synthetic-client", tokens) { uri -> Connection(uri) {
            responses.getOrElse(requests.getAndIncrement()) { throw IllegalStateException("Unexpected extra login request") }
        } }
    }

    private fun error(code: Login5Error) = ProtoWire.fieldVarint(2, code.code)
    private fun success() = ProtoWire.fieldBytes(1,
        ProtoWire.fieldString(1, "synthetic-user") + ProtoWire.fieldString(2, "synthetic-access") + ProtoWire.fieldVarint(4, 3600))

    private class Connection(uri: URI, private val reply: () -> ByteArray) : HttpURLConnection(uri.toURL()) {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(reply())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
