package io.github.playmusic.data.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class ClientTokenCacheTest {
    @Test
    fun concurrentCallersShareOneGrantedToken() = runTest {
        val requests = AtomicInteger()
        val client = client(requests, { 0 })
        val tokens = List(8) { async { client.acquire("client", "device").token } }.awaitAll()
        assertEquals(setOf("sdk-1"), tokens.toSet())
        assertEquals(1, requests.get())
    }

    @Test
    fun refreshTimeForceAndDeviceChangesInvalidateTheCachedToken() = runTest {
        val requests = AtomicInteger()
        var now = 0L
        val client = client(requests, { now })
        assertEquals("sdk-1", client.acquire("client", "device").token)
        now = 59_999
        assertEquals("sdk-1", client.acquire("client", "device").token)
        now = 60_000
        assertEquals("sdk-2", client.acquire("client", "device").token)
        assertEquals("sdk-3", client.acquire("client", "device", forceRefresh = true).token)
        assertEquals("sdk-4", client.acquire("client", "other-device").token)
        assertEquals("sdk-5", client.acquire("other-client", "other-device").token)
        assertEquals(5, requests.get())
    }

    @Test
    fun expiryWinsWhenItIsEarlierThanTheSuggestedRefresh() = runTest {
        val requests = AtomicInteger()
        var now = 0L
        val client = client(requests, { now }, expires = 30)
        client.acquire("client", "device")
        now = 30_000
        assertEquals("sdk-2", client.acquire("client", "device").token)
    }

    private fun client(requests: AtomicInteger, clock: () -> Long, expires: Int = 120) = ClientTokenClient(
        clock = clock,
        openConnection = { uri -> Connection(uri) {
            val number = requests.incrementAndGet()
            ProtoWire.fieldVarint(1, 1) + ProtoWire.fieldBytes(2,
                ProtoWire.fieldString(1, "sdk-$number") + ProtoWire.fieldVarint(2, expires) + ProtoWire.fieldVarint(3, 60))
        } },
    )

    private class Connection(uri: URI, private val reply: () -> ByteArray) : HttpURLConnection(uri.toURL()) {
        private val body by lazy(reply)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
