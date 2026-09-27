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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        assertEquals(4_000L, sleeps.sum())
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
        assertEquals(listOf(0L, 10_000L, 40_000L, 100_000L), connections.map { it.sentAtMs })
        assertEquals(100_000L, sleeps.sum())
        assertNoSecrets(error)
    }

    @Test
    fun retryAfterHeaderValuesUseTheServerDeadline() {
        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(429, 429, 200),
            headers = listOf(mapOf("Retry-After" to "Thu, 01 Jan 1970 00:00:05 GMT"),
                mapOf("Retry-After" to "not-a-date")), sleeps = sleeps)
        assertArrayEquals(RESPONSE, client.post(LICENSE, REQUEST, ::headers))
        assertEquals(listOf(0L, 5_000L, 35_000L), connections.map { it.sentAtMs })
        assertEquals(35_000L, sleeps.sum())
    }

    @Test
    fun backToBackLicenseRequestsShareActualSendSpacing() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val first = mutableListOf<Connection>()
        val second = mutableListOf<Connection>()
        client(first, clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
        client(second, clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
        assertEquals(0L, first.single().sentAtMs)
        assertEquals(4_000L, second.single().sentAtMs)
    }

    @Test
    fun pastRetryAfterDatesStillObserveMinimumSpacingForBusyResponses() {
        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(503, 200),
            headers = listOf(mapOf("Retry-After" to "Thu, 01 Jan 1970 00:00:00 GMT")), sleeps = sleeps)
        assertArrayEquals(RESPONSE, client.post(LICENSE, REQUEST, ::headers))
        assertEquals(4_000L, sleeps.sum())
    }

    @Test
    fun oversizedRetryAfterNeverWrapsOrRetriesBeforeTheDeadline() {
        for (value in listOf("120", Long.MAX_VALUE.toString(), "999999999999999999999999999999")) {
            for (status in listOf(429, 503)) {
                val clock = FakeClock()
                val pacer = LicensePacer { clock.now }
                val connections = mutableListOf<Connection>()
                val sleeps = mutableListOf<Long>()
                val failing = client(connections, statuses = listOf(status),
                    headers = listOf(mapOf("Retry-After" to value)), clock = clock, pacer = pacer, sleeps = sleeps)
                val error = runCatching { failing.post(LICENSE, REQUEST, ::headers) }.exceptionOrNull()
                    as LicenseHttpClient.LicenseHttpException
                assertEquals(status, error.responseCode)
                assertTrue(error.retryAfterMs!! > 60_000)
                assertEquals(1, connections.count { it.sent.size() > 0 })
                assertTrue(sleeps.isEmpty())
                // A different client is subject to the same deadline, including after failure.
                val later = mutableListOf<Connection>()
                val laterFailure = runCatching {
                    client(later, clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
                }.exceptionOrNull() as LicenseHttpClient.LicenseHttpException
                assertEquals(status, laterFailure.responseCode)
                assertTrue(later.all { it.sent.size() == 0 })
                assertNoSecrets(error)
            }
        }
    }

    @Test
    fun cancelledRetryLeavesItsDeadlineInEffectForTheOtherEngine() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val first = mutableListOf<Connection>()
        var cancelled = false
        val firstClient = client(first, statuses = listOf(429, 200), clock = clock, pacer = pacer,
            onSleep = { cancelled = true })
        val before = LicenseRequestMetrics.snapshot()
        val failure = runCatching {
            firstClient.post(LICENSE, REQUEST, { cancelled }, ::headers)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, first.count { it.sent.size() > 0 })
        assertTrue(first.all { it.disconnected })
        val second = mutableListOf<Connection>()
        client(second, clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
        assertEquals(10_000L, second.single().sentAtMs)
        val after = LicenseRequestMetrics.snapshot()
        assertEquals(2L, after.attempts - before.attempts)
        assertEquals(1L, after.successes - before.successes)
        assertEquals(1L, after.rateLimited - before.rateLimited)
        assertEquals(1L, after.cancelled - before.cancelled)
    }

    @Test
    fun slowAuthenticationCannotReserveAnEarlierSendSlot() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val first = mutableListOf<Connection>()
        val second = mutableListOf<Connection>()
        val slow = client(first, clock = clock, pacer = pacer)
        val fast = client(second, clock = clock, pacer = pacer)
        slow.post(LICENSE, REQUEST) { refresh ->
            clock.now = 5_000
            fast.post(LICENSE, REQUEST, ::headers)
            clock.now = 5_100
            headers(refresh)
        }
        assertEquals(5_000L, second.single().sentAtMs)
        assertEquals(9_000L, first.single().sentAtMs)
    }

    @Test
    fun concurrentEngineCannotSendBeforeTheFirstBusyResponsePublishesItsDeadline() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val inResponse = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val waitingForGate = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var cancelled = false
        try {
            val first = LicenseHttpClient({ uri ->
                Connection(uri, 429, RESPONSE, false, mapOf("Retry-After" to "10"), { clock.now }).apply {
                    beforeResponse = {
                        inResponse.countDown()
                        assertTrue(releaseResponse.await(5, TimeUnit.SECONDS))
                        cancelled = true
                    }
                }
            }, { clock.now += it }, pacer)
            val firstResult = executor.submit<Throwable?> {
                try { runCatching { first.post(LICENSE, REQUEST, { cancelled }, ::headers) }.exceptionOrNull() }
                finally { firstFinished.countDown() }
            }
            assertTrue(inResponse.await(5, TimeUnit.SECONDS))
            val secondConnections = mutableListOf<Connection>()
            val second = LicenseHttpClient({ uri ->
                Connection(uri, 200, RESPONSE, false, nowMs = { clock.now }).also { secondConnections += it }
            }, {
                waitingForGate.countDown()
                assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
                clock.now += it
            }, pacer)
            val secondResult = executor.submit<ByteArray> { second.post(LICENSE, REQUEST, ::headers) }
            assertTrue(waitingForGate.await(5, TimeUnit.SECONDS))
            releaseResponse.countDown()
            assertTrue(firstResult.get(5, TimeUnit.SECONDS) is CancellationException)
            assertArrayEquals(RESPONSE, secondResult.get(5, TimeUnit.SECONDS))
            assertEquals(10_000L, secondConnections.single().sentAtMs)
        } finally {
            releaseResponse.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeAndDuringAuthenticationNeverOpensAConnection() {
        val connections = mutableListOf<Connection>()
        val client = client(connections)
        var authCalls = 0
        assertTrue(runCatching {
            client.post(LICENSE, REQUEST, { true }) { refresh -> authCalls++; headers(refresh) }
        }.exceptionOrNull() is CancellationException)
        assertEquals(0, authCalls)
        var cancelled = false
        assertTrue(runCatching {
            client.post(LICENSE, REQUEST, { cancelled }) { refresh -> cancelled = true; headers(refresh) }
        }.exceptionOrNull() is CancellationException)
        assertTrue(connections.isEmpty())
    }

    @Test
    fun cancellationDuringPacingDoesNotReserveARequestOrRetryAsNetworkFailure() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        client(mutableListOf(), clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
        var cancelled = false
        val discarded = mutableListOf<Connection>()
        val result = runCatching {
            client(discarded, clock = clock, pacer = pacer, onSleep = { cancelled = true })
                .post(LICENSE, REQUEST, { cancelled }, ::headers)
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, discarded.size)
        assertEquals(0, discarded.single().sent.size())
        assertTrue(discarded.single().disconnected)
        val next = mutableListOf<Connection>()
        client(next, clock = clock, pacer = pacer).post(LICENSE, REQUEST, ::headers)
        assertEquals(4_000L, next.single().sentAtMs)
    }

    @Test
    fun disconnectFailureCannotMaskCancellationOrReplayASuccess() {
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val connections = mutableListOf<Connection>()
        var cancelled = false
        val client = LicenseHttpClient({ uri ->
            Connection(uri, 200, RESPONSE, false).also {
                it.disconnectFailure = true
                connections += it
            }
        }, { clock.now += it; cancelled = true }, pacer)
        assertArrayEquals(RESPONSE, client.post(LICENSE, REQUEST, ::headers))
        assertEquals(1, connections.size)
        assertTrue(runCatching {
            client.post(LICENSE, REQUEST, { cancelled }, ::headers)
        }.exceptionOrNull() is CancellationException)
        assertEquals(2, connections.size)
        assertEquals(0, connections.last().sent.size())
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
        val clock = FakeClock()
        val pacer = LicensePacer { clock.now }
        val connections = mutableListOf<Connection>()
        val attempts = mutableListOf<Boolean>()
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val client = LicenseHttpClient({ uri ->
            if (calls++ == 0) throw java.io.IOException("synthetic transport cut")
            Connection(uri, 200, RESPONSE, false).also { connections += it }
        }, { sleeps.add(it); clock.now += it }, pacer)
        val response = client.post(LICENSE, REQUEST) { refresh -> attempts += refresh; headers(refresh) }
        assertArrayEquals(RESPONSE, response)
        assertEquals(listOf(false, false), attempts)
        assertTrue(sleeps.isEmpty())
        assertEquals(1, connections.size)
        val failing = LicenseHttpClient({ throw java.io.IOException("synthetic outage") },
            { sleeps.add(it); clock.now += it }, pacer)
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
        val failing = LicenseHttpClient(openConnection = { throw IOException("$ACCESS $CLIENT_TOKEN $BODY_SECRET") })
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
        clock: FakeClock = FakeClock(), pacer: LicensePacer = LicensePacer { clock.now },
        onSleep: (() -> Unit)? = null): LicenseHttpClient {
        return LicenseHttpClient({ uri ->
            val status = statuses.getOrElse(connections.size) { statuses.last() }
            Connection(uri, status, reply, gzip, headers.getOrElse(connections.size) { emptyMap() }, { clock.now })
                .also { connections += it }
        }, { sleeps?.add(it); clock.now += it; onSleep?.invoke() }, pacer, { 0L })
    }

    private class Connection(uri: URI, private val status: Int, private val reply: ByteArray,
        private val gzip: Boolean, private val headers: Map<String, String> = emptyMap(),
        private val nowMs: () -> Long = { 0L }) : HttpURLConnection(uri.toURL()) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var errorReads = 0
        var sentAtMs: Long? = null
        var disconnectFailure = false
        var beforeResponse: () -> Unit = { }
        override fun getOutputStream(): ByteArrayOutputStream {
            sentAtMs = nowMs()
            return sent
        }
        override fun getResponseCode(): Int { beforeResponse(); return status }
        override fun getInputStream() = ByteArrayInputStream(reply)
        override fun getErrorStream(): ByteArrayInputStream {
            errorReads++
            return ByteArrayInputStream("$ACCESS $CLIENT_TOKEN $BODY_SECRET".toByteArray())
        }
        override fun getContentEncoding(): String? = if (gzip) "gzip" else null
        override fun getHeaderField(name: String): String? = headers[name]
            ?: if (name.equals("Location", true)) "https://outside.invalid/$ACCESS" else null
        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
            if (disconnectFailure) throw IOException("synthetic cleanup failure")
        }
        override fun usingProxy() = false
    }

    private class FakeClock(@Volatile var now: Long = 0L)

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
