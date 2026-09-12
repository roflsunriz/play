package io.github.playmusic

import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.DeviceAuthorizationClient
import io.github.playmusic.data.auth.SpotifyAuthException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

class DeviceAuthorizationClientTest {
    @Test fun respectsPendingAndSlowerPollingBeforeGrantingAccess(): Unit = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<Connection>()
        val responses = ArrayDeque(listOf(
            200 to pendingResponse(),
            400 to """{"error":"authorization_pending"}""",
            400 to """{"error":"slow_down"}""",
            200 to tokenResponse(),
        ))
        val client = DeviceAuthorizationClient(
            openConnection = { uri -> Connection(uri, responses.removeFirst()).also { calls += it } },
            now = { now },
            wait = { waits += it; now += it },
        )
        val pending = client.begin()
        assertEquals("SYNTHETIC", pending.userCode)
        assertEquals("https://spotify.com/pair?code=SYNTHETIC", pending.verificationUri)
        val tokens = client.awaitAuthorization(pending)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), waits)
        assertEquals("synthetic-access", tokens.accessToken)
        assertEquals("synthetic-refresh", tokens.refreshToken)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", calls.last().form()["grant_type"])
        assertEquals("synthetic-device-code", calls.last().form()["device_code"])
        assertTrue(calls.first().form().getValue("scope").split(',').containsAll(listOf("user-library-read", "streaming")))
        assertTrue(calls.all { it.disconnected })
    }

    @Test fun stopsAtExpiryWithoutSendingAnotherPoll() {
        var now = 0L
        var calls = 0
        val client = DeviceAuthorizationClient(
            openConnection = { uri -> calls++; Connection(uri, 200 to pendingResponse(expires = 5)) },
            now = { now }, wait = { now += it },
        )
        assertThrows(SpotifyAuthException::class.java) { runBlocking { client.awaitAuthorization(client.begin()) } }
        assertEquals(1, calls)
    }

    @Test fun rejectsAnUntrustedAuthorizationPage() {
        val client = DeviceAuthorizationClient(openConnection = { uri ->
            Connection(uri, 200 to pendingResponse().replace("spotify.com/pair", "example.org/pair")) })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.begin() } }
    }

    @Test fun refreshMayKeepThePreviousRefreshToken(): Unit = runBlocking {
        var request: Connection? = null
        val client = DeviceAuthorizationClient(openConnection = { uri ->
            Connection(uri, 200 to tokenResponse(includeRefresh = false)).also { request = it } })
        val tokens = client.refresh("synthetic-refresh")
        assertNull(tokens.refreshToken)
        assertEquals("synthetic-refresh", request?.form()?.get("refresh_token"))
        assertEquals("refresh_token", request?.form()?.get("grant_type"))
    }

    @Test fun deviceCanStartAuthorizationWithTheRequestedScopes(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceAuthorizationProbe") == "true")
        val pending = DeviceAuthorizationClient().begin()
        assertTrue(pending.userCode.isNotBlank())
        assertTrue(pending.expiresAtEpochMs > System.currentTimeMillis())
    }

    private fun pendingResponse(expires: Int = 300) = """{"device_code":"synthetic-device-code","user_code":"SYNTHETIC",
        "verification_uri":"https://spotify.com/pair","verification_uri_complete":"https://spotify.com/pair?code=SYNTHETIC",
        "interval":5,"expires_in":$expires}"""

    private fun tokenResponse(includeRefresh: Boolean = true) = """{"access_token":"synthetic-access","token_type":"Bearer","expires_in":3600${
        if (includeRefresh) ",\"refresh_token\":\"synthetic-refresh\"" else ""}}"""

    private class Connection(uri: URI, private val response: Pair<Int, String>) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        var disconnected = false
        fun form(): Map<String, String> = output.toString(Charsets.UTF_8).split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        override fun getOutputStream() = output
        override fun getResponseCode() = response.first
        override fun getInputStream() = ByteArrayInputStream(response.second.toByteArray())
        override fun getErrorStream() = inputStream
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
}
