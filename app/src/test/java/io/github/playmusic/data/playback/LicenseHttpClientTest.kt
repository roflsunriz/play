package io.github.playmusic.data.playback

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import io.github.playmusic.data.auth.AuthException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPOutputStream

class LicenseHttpClientTest {
    @Test
    fun sendsThePlatformMessageOnceWithoutAutomaticRedirects() {
        val connections = mutableListOf<Connection>()
        val attempts = mutableListOf<Boolean>()
        val client = client(connections)
        val response = client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
        assertArrayEquals(RESPONSE, response)
        assertEquals(listOf(false), attempts)
        val request = connections.single()
        assertEquals("POST", request.requestMethod)
        assertFalse(request.instanceFollowRedirects)
        assertEquals(15_000, request.connectTimeout)
        assertEquals(20_000, request.readTimeout)
        assertFalse(request.useCaches)
        assertEquals("application/octet-stream", request.getRequestProperty("Content-Type"))
        assertEquals("Bearer $ACCESS", request.getRequestProperty("Authorization"))
        assertEquals(CLIENT_TOKEN, request.getRequestProperty("client-token"))
        assertArrayEquals(REQUEST, request.sent.toByteArray())
        assertTrue(request.disconnected)
    }

    @Test
    fun unauthorizedLicenseRequestsRefreshOnceAndReplayTheSameMessage() {
        for (secondStatus in listOf(200, 401)) {
            val connections = mutableListOf<Connection>()
            val attempts = mutableListOf<Boolean>()
            val client = client(connections, statuses = listOf(401, secondStatus))
            val result = runCatching {
                client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
            }
            assertEquals(listOf(false, true), attempts)
            assertEquals(2, connections.size)
            assertEquals("Bearer $ACCESS", connections.first().getRequestProperty("Authorization"))
            assertEquals("Bearer refreshed-$ACCESS", connections.last().getRequestProperty("Authorization"))
            assertTrue(connections.all { it.sent.toByteArray().contentEquals(REQUEST) && it.disconnected })
            if (secondStatus == 200) assertArrayEquals(RESPONSE, result.getOrThrow())
            else {
                val error = result.exceptionOrNull() as LicenseHttpClient.LicenseHttpException
                assertEquals(401, error.responseCode)
                assertNoSecrets(error)
            }
        }
    }

    @Test
    fun redirectsAndOtherErrorsNeverSendCredentialsToAnotherLocation() {
        for (status in listOf(301, 302, 303, 307, 308, 400, 403, 500)) {
            val connections = mutableListOf<Connection>()
            val attempts = mutableListOf<Boolean>()
            val client = client(connections, statuses = listOf(status))
            val error = runCatching {
                client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
            }.exceptionOrNull() as LicenseHttpClient.LicenseHttpException
            assertEquals(status, error.responseCode)
            assertEquals(listOf(false), attempts)
            assertEquals(1, connections.size)
            val request = connections.single()
            assertEquals(LICENSE.toString(), request.url.toString())
            assertFalse(request.instanceFollowRedirects)
            assertEquals(0, request.errorReads)
            assertTrue(request.disconnected)
            assertNoSecrets(error)
        }
    }

    @Test
    fun rateLimitedLicenseRequestsBackOffAndReplayTheSameMessage() {
        val connections = mutableListOf<Connection>()
        val attempts = mutableListOf<Boolean>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(429, 200),
            headers = listOf(mapOf("Retry-After" to "2")), sleeps = sleeps)
        val response = client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
        assertArrayEquals(RESPONSE, response)
        assertEquals(listOf(false, false), attempts)
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(2, connections.size)
        assertTrue(connections.all { it.sent.toByteArray().contentEquals(REQUEST) && it.disconnected })
    }

    @Test
    fun persistentRateLimitingFailsAfterThreeRetriesWithGrowingDelays() {
        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(429, 429, 429, 429, 429), sleeps = sleeps)
        val error = runCatching {
            client.post(LICENSE, REQUEST) { attempts -> headers(attempts) }
        }.exceptionOrNull() as LicenseHttpClient.LicenseHttpException
        assertEquals(429, error.responseCode)
        assertEquals(4, connections.size)
        assertEquals(listOf(10_000L, 30_000L, 60_000L), sleeps)
        assertNoSecrets(error)
    }

    @Test
    fun retryAfterHeaderValuesAreParsedAndCapped() {
        val future = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(System.currentTimeMillis() + 5_000))
        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(429, 429, 429, 200),
            headers = listOf(mapOf("Retry-After" to "120"), mapOf("Retry-After" to "not-a-date"),
                mapOf("Retry-After" to future)), sleeps = sleeps)
        assertArrayEquals(RESPONSE, client.post(LICENSE, REQUEST, ::headers))
        assertEquals(60_000L, sleeps[0])
        assertEquals(30_000L, sleeps[1])
        assertTrue(sleeps[2] in 1_000L..10_000L)
        assertEquals(4, connections.size)
    }

    @Test
    fun backToBackLicenseRequestsAreSpacedProcessWide() {
        val firstPace = mutableListOf<Long>()
        val firstSleeps = mutableListOf<Long>()
        assertArrayEquals(RESPONSE, client(mutableListOf(), sleeps = firstSleeps, paceSleeps = firstPace)
            .post(LICENSE, REQUEST, ::headers))
        assertTrue(firstPace.isEmpty() && firstSleeps.isEmpty())
        val secondPace = mutableListOf<Long>()
        val secondSleeps = mutableListOf<Long>()
        assertArrayEquals(RESPONSE, client(mutableListOf(), sleeps = secondSleeps, paceSleeps = secondPace,
            resetPacer = false).post(LICENSE, REQUEST, ::headers))
        assertTrue(secondSleeps.isEmpty())
        assertEquals(1, secondPace.size)
        assertTrue(secondPace.single() in 1L..4_000L)
    }

    @Test
    fun pastRetryAfterDatesDoNotWaitAndOtherBusySignalsMatchRateLimiting() {        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(503, 200),
            headers = listOf(mapOf("Retry-After" to "Thu, 01 Jan 1970 00:00:00 GMT")), sleeps = sleeps)
        assertArrayEquals(RESPONSE, client.post(LICENSE, REQUEST, ::headers))
        assertEquals(listOf(0L), sleeps)
    }

    @Test
    fun rateLimitFailuresAreRecognizedThroughTheirCauseChain() {
        val limited = LicenseHttpClient.LicenseHttpException(LicenseHttpClient.Failure.HTTP, 429)
        val busy = LicenseHttpClient.LicenseHttpException(LicenseHttpClient.Failure.HTTP, 503)
        val denied = LicenseHttpClient.LicenseHttpException(LicenseHttpClient.Failure.HTTP, 403)
        assertTrue(isLicenseRateLimited(limited))
        assertTrue(isLicenseRateLimited(busy))
        assertTrue(isLicenseRateLimited(java.io.IOException("outer", busy)))
        assertTrue(isLicenseRateLimited(java.io.IOException("outer",
            java.io.IOException("middle", limited))))
        assertFalse(isLicenseRateLimited(denied))
        assertFalse(isLicenseRateLimited(java.io.IOException("outer", denied)))
        assertFalse(isLicenseRateLimited(java.io.IOException("plain")))
        assertFalse(isLicenseRateLimited(null))
    }

    @Test
    fun transientTransportFailuresReplayOnce() {
        LicensePacer.resetForTests()
        val connections = mutableListOf<Connection>()
        val attempts = mutableListOf<Boolean>()
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val client = LicenseHttpClient({ uri ->
            if (calls++ == 0) throw java.io.IOException("synthetic transport cut")
            Connection(uri, 200, RESPONSE, false).also { connections += it }
        }, { sleeps.add(it) }, { })
        val response = client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
        assertArrayEquals(RESPONSE, response)
        assertEquals(listOf(false, false), attempts)
        assertTrue(sleeps.isEmpty())
        assertEquals(1, connections.size)
        val failing = LicenseHttpClient({ throw java.io.IOException("synthetic outage") }, { sleeps.add(it) }, { })
        val error = runCatching { failing.post(LICENSE, REQUEST, ::headers) }.exceptionOrNull()
            as LicenseHttpClient.LicenseHttpException
        assertEquals(LicenseHttpClient.Failure.NETWORK, error.failure)
        assertNoSecrets(error)
    }

    @Test
    fun compressedResponsesAreDecodedAndBothMessageDirectionsHaveBounds() {
        val compressed = gzip(RESPONSE)
        assertArrayEquals(RESPONSE, client(mutableListOf(), reply = compressed, gzip = true)
            .post(LICENSE, REQUEST, ::headers))
        val connections = mutableListOf<Connection>()
        val tooLarge = ByteArray(1_048_577) { 1 }
        val requestError = runCatching { client(connections).post(LICENSE, tooLarge, ::headers) }.exceptionOrNull()
        assertEquals(LicenseHttpClient.Failure.SIZE_LIMIT, (requestError as LicenseHttpClient.LicenseHttpException).failure)
        assertTrue(connections.isEmpty())
        for (compressedReply in listOf(false, true)) {
            val client = client(connections, reply = if (compressedReply) gzip(tooLarge) else tooLarge, gzip = compressedReply)
            val failure = runCatching { client.post(LICENSE, REQUEST, ::headers) }.exceptionOrNull()
                as LicenseHttpClient.LicenseHttpException
            assertEquals(LicenseHttpClient.Failure.SIZE_LIMIT, failure.failure)
            assertTrue(failure.bytesLoaded <= 1_048_576)
            assertTrue(connections.last().disconnected)
            assertNoSecrets(failure)
        }
    }

    @Test
    fun authorizationFailuresAreNotMaskedAsNetworkFaults() {
        val transfer = PlaybackAuthorizationClient.PlaybackAuthorizationException(
            PlaybackAuthorizationClient.Stage.TRANSFER, PlaybackAuthorizationClient.Failure.HTTP, 401)
        assertSame(transfer, runCatching {
            client(mutableListOf()).post(LICENSE, REQUEST) { throw transfer }
        }.exceptionOrNull())
        val clientToken = AuthException("synthetic client token failure")
        assertSame(clientToken, runCatching {
            client(mutableListOf()).post(LICENSE, REQUEST) { throw clientToken }
        }.exceptionOrNull())
        val signIn = BrowserAuthorizationRequiredException()
        assertSame(signIn, runCatching {
            client(mutableListOf()).post(LICENSE, REQUEST) { throw signIn }
        }.exceptionOrNull())
    }

    @Test
    fun networkAndHeaderErrorsDiscardMessagesWhileCancellationIsPreserved() {
        val failing = LicenseHttpClient { throw IOException("$ACCESS $CLIENT_TOKEN $BODY_SECRET") }
        val failure = runCatching { failing.post(LICENSE, REQUEST, ::headers) }.exceptionOrNull()
            as LicenseHttpClient.LicenseHttpException
        assertEquals(LicenseHttpClient.Failure.NETWORK, failure.failure)
        assertNoSecrets(failure)
        val connections = mutableListOf<Connection>()
        val headerFailure = runCatching { client(connections).post(LICENSE, REQUEST) {
            throw IllegalArgumentException("$ACCESS $CLIENT_TOKEN")
        } }.exceptionOrNull() as LicenseHttpClient.LicenseHttpException
        assertNoSecrets(headerFailure)
        assertTrue(connections.isEmpty())
        val cancellation = CancellationException("cancelled")
        val result = runCatching { client(connections).post(LICENSE, REQUEST) { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, result)
        assertTrue(connections.isEmpty())
    }

    private fun client(connections: MutableList<Connection>, statuses: List<Int> = listOf(200),
        reply: ByteArray = RESPONSE, gzip: Boolean = false,
        headers: List<Map<String, String>> = emptyList(), sleeps: MutableList<Long>? = null,
        resetPacer: Boolean = true, paceSleeps: MutableList<Long>? = null): LicenseHttpClient {
        if (resetPacer) LicensePacer.resetForTests()
        return LicenseHttpClient({ uri ->
            val status = statuses.getOrElse(connections.size) { statuses.last() }
            Connection(uri, status, reply, gzip, headers.getOrElse(connections.size) { emptyMap() })
                .also { connections += it }
        }, { sleeps?.add(it) }, { paceSleeps?.add(it) })
    }

    private class Connection(uri: URI, private val status: Int, private val reply: ByteArray,
        private val gzip: Boolean, private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(uri.toURL()) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var errorReads = 0
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(reply)
        override fun getErrorStream(): ByteArrayInputStream {
            errorReads++
            return ByteArrayInputStream("$ACCESS $CLIENT_TOKEN $BODY_SECRET".toByteArray())
        }
        override fun getContentEncoding(): String? = if (gzip) "gzip" else null
        override fun getHeaderField(name: String): String? = headers[name]
            ?: if (name.equals("Location", true)) "https://outside.invalid/$ACCESS" else null
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

    private companion object {
        val LICENSE = URI("https://license.example.test/license")
        const val ACCESS = "synthetic-private-access"
        const val CLIENT_TOKEN = "synthetic-private-client-token"
        const val BODY_SECRET = "synthetic-private-platform-message"
        val REQUEST = BODY_SECRET.toByteArray()
        val RESPONSE = byteArrayOf(8, 7, 6, 5, 4)
        fun headers(refresh: Boolean): Map<String, String> = mapOf(
            "Authorization" to "Bearer ${if (refresh) "refreshed-" else ""}$ACCESS", "client-token" to CLIENT_TOKEN)
        fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        fun assertNoSecrets(error: Throwable) {
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            for (secret in listOf(ACCESS, CLIENT_TOKEN, BODY_SECRET, "outside.invalid")) {
                assertFalse(error.toString().contains(secret))
            }
        }
    }
}
