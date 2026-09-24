package io.github.playmusic.data.auth

import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

class WebClientTokenClientTest {
    private val device = WebClientDevice("synthetic-play-id", "test-brand", "test-model", "16", false)
    private val clientId = "0123456789abcdef0123456789abcdef"
    private val clientVersion = "1.3.test"

    @Test
    fun sendsTheProvidedIdentityWithOnlyTheSuppliedAndroidData() = runTest {
        var sent: Connection? = null
        val client = WebClientTokenClient(device, clientId, clientVersion, openConnection = { uri ->
            Connection(uri, grant()).also { sent = it }
        })
        val token = client.acquire()
        assertTrue(token.allows(URI("https://license.example.test/audio")))
        val connection = checkNotNull(sent)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(setOf("client_data"), body.keys().asSequence().toSet())
        // The reference JSON transport does not send the native protobuf request_type field.
        assertFalse(body.has("request_type"))
        val data = body.getJSONObject("client_data")
        assertEquals(setOf("client_version", "client_id", "js_sdk_data"), data.keys().asSequence().toSet())
        assertEquals(clientId, data.getString("client_id"))
        assertEquals(clientVersion, data.getString("client_version"))
        assertFalse(data.has("connectivity_sdk_data"))
        val sdk = data.getJSONObject("js_sdk_data")
        assertEquals("synthetic-play-id", sdk.getString("device_id"))
        assertEquals("test-model", sdk.getString("device_model"))
        assertEquals("test-brand", sdk.getString("device_brand"))
        assertEquals("android", sdk.getString("os"))
        assertEquals("16", sdk.getString("os_version"))
        assertEquals("smartphone", sdk.getString("device_type"))
        assertFalse(sdk.has("container_version"))
        assertFalse(sdk.has("platform_identifier"))
    }

    @Test
    fun malformedOrChallengedResponsesAreNotTreatedAsUsableTokens() {
        val invalid = listOf(
            JSONObject("""{"challenges":{"state":"synthetic"}}"""),
            grant().put("response_type", "RESPONSE_CHALLENGES_RESPONSE"),
            grant().also { it.getJSONObject("granted_token").put("expires_after_seconds", -1) },
            grant().also { it.getJSONObject("granted_token").put("expires_after_seconds", 2.5) },
            grant().also { it.getJSONObject("granted_token").put("domains", org.json.JSONArray("""[{"domain":"example.test/path"}]""")) },
        )
        for (response in invalid) {
            assertTrue(runCatching { WebClientTokenClient.parseGrant(response) }.exceptionOrNull() is AuthException)
        }
    }

    @Test
    fun httpErrorReportsOnlyItsStatusWithoutReadingOrExposingResponseValues() = runTest {
        val secret = "synthetic-value-must-not-leak"
        val response = JSONObject().put("error", JSONObject().put("code", "INVALID_CLIENT")
            .put("message", secret).put("path", "client_data.client_id"))
            .put(secret, secret)
        var sent: Connection? = null
        val client = WebClientTokenClient(device, clientId, clientVersion,
            openConnection = { uri -> Connection(uri, response, 400).also { sent = it } })
        val failure = runCatching { client.acquire() }.exceptionOrNull()
        assertTrue(failure is AuthException)
        val description = failure?.message.orEmpty()
        assertEquals("Client token request failed (400)", description)
        assertEquals(0, checkNotNull(sent).errorReads)
        assertFalse(description.contains(secret))
        assertFalse(description.contains(device.deviceId))
        assertSanitizedFailure(failure, secret, device.deviceId)
    }

    @Test
    fun malformedJsonAndNetworkErrorsNeverReturnTheirText() = runTest {
        val secret = "synthetic-token-and-UA-value"
        val malformed = WebClientTokenClient(device, clientId, clientVersion,
            openConnection = { uri -> Connection(uri, grant(), rawReply = "{broken-json:$secret") })
        val jsonError = runCatching { malformed.acquire() }.exceptionOrNull()
        assertSanitizedFailure(jsonError, secret, "broken-json")
        val network = WebClientTokenClient(device, clientId, clientVersion,
            openConnection = { throw IOException(secret) })
        val networkError = runCatching { network.acquire() }.exceptionOrNull()
        assertSanitizedFailure(networkError, secret)
    }

    @Test
    fun grantedCredentialsMustBeSafeForHeadersAndCoveredByDomains() {
        val token = WebClientTokenClient.parseGrant(grant().also {
            it.getJSONObject("granted_token").put("domains", org.json.JSONArray("""[{"domain":".EXAMPLE.test."}]"""))
        })
        assertEquals(listOf("example.test"), token.domains)
        assertTrue(token.allows(URI("https://license.example.test/audio")))
        assertFalse(token.allows(URI("https://evilexample.test/audio")))
        val unsafe = grant().also { it.getJSONObject("granted_token").put("token", "synthetic\r\nheader") }
        assertTrue(runCatching { WebClientTokenClient.parseGrant(unsafe) }.exceptionOrNull() is AuthException)
    }

    private fun assertSanitizedFailure(failure: Throwable?, vararg sensitiveValues: String) {
        assertTrue(failure is AuthException)
        val root = checkNotNull(failure)
        val rendered = root.stackTraceToString()
        for (value in sensitiveValues) assertFalse(rendered.contains(value))
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            // Coroutine recovery may add a sanitized copy; raw transport/parser errors must stay private.
            assertFalse(current is JSONException || current is IOException)
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
    }

    private fun grant(): JSONObject = JSONObject("""{
        "response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE",
        "granted_token":{"token":"synthetic-token","expires_after_seconds":120,"refresh_after_seconds":60,
        "domains":[{"domain":"example.test"}]}}
    """)

    private class Connection(uri: URI, private val reply: JSONObject, private val status: Int = 200,
        private val rawReply: String? = null) : HttpURLConnection(uri.toURL()) {
        val sent = ByteArrayOutputStream()
        var errorReads = 0
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream((rawReply ?: reply.toString()).toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = ByteArrayInputStream(reply.toString().toByteArray(Charsets.UTF_8)).also { errorReads++ }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
