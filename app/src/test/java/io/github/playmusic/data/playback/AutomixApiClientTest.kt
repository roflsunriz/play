package io.github.playmusic.data.playback

import io.github.playmusic.data.api.SessionTokens
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.playback.AutomixMetadataTest.Companion.CONTEXT
import io.github.playmusic.data.playback.AutomixMetadataTest.Companion.TRACK1
import io.github.playmusic.data.playback.AutomixMetadataTest.Companion.TRACK2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class AutomixApiClientTest {
    @Test fun serviceEligibilityMembershipAndAccountScopePrecedeTheOrderedPair(): Unit = runBlocking {
        val session = Tokens()
        var now = 0L
        var settings = PlaybackTransitionSettings(crossfadeSeconds = 5)
        val calls = mutableListOf<Connection>()
        val api = SpotifyApiClient(session) { uri -> Connection(uri,
            if (uri.path.startsWith("/playlist/")) playlist() else AutomixMetadataTest.fixture()).also { calls += it } }
        val client = AutomixApiClient(session, { settings }, api, { now })
        val mix = checkNotNull(client.resolve(CONTEXT, TRACK1, TRACK2))
        assertEquals(128f / 123, mix.incomingSpeed, .00001f)
        assertEquals(3, calls.size)
        assertTrue(calls[0].posted.toByteArray().contentEquals(AutomixMetadata.request(listOf(AutomixMetadata.Key(CONTEXT, 27)))))
        assertTrue(calls[2].posted.toByteArray().contentEquals(AutomixMetadata.request(listOf(AutomixMetadata.Key(TRACK1, 28), AutomixMetadata.Key(TRACK2, 28)))))
        settings = settings.copy(crossfadeSeconds = 1)
        assertEquals(1_000L, checkNotNull(client.resolve(CONTEXT, TRACK1, TRACK2)).durationMs)
        assertEquals(3, calls.size)
        assertNull(client.resolve(CONTEXT, TRACK1, "spotify:track:0000000000000000000003"))
        assertEquals(3, calls.size)
        assertNull(client.resolve("spotify:album:0000000000000000000001", TRACK1, TRACK2))
        assertEquals(3, calls.size)
        now = 300_001
        assertNotNull(client.resolve(CONTEXT, TRACK1, TRACK2))
        assertEquals(6, calls.size)
        session.owner = "another-account"
        assertNotNull(client.resolve(CONTEXT, TRACK1, TRACK2))
        assertEquals(9, calls.size)
        settings = settings.copy(automixEnabled = false)
        assertNull(client.resolve(CONTEXT, TRACK1, TRACK2))
        assertEquals(9, calls.size)
    }
    private fun playlist(): ByteArray = ProtoWire.fieldBytes(1, byteArrayOf(1, 2, 3)) +
        ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, "Test context")) +
        ProtoWire.fieldMessage(5, ProtoWire.fieldVarint(1, 0) + listOf(TRACK1, TRACK2).fold(byteArrayOf()) { bytes, uri ->
            bytes + ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, uri)) })

    private class Tokens : SessionTokens {
        var owner = "synthetic-account"
        override suspend fun username() = owner
        override suspend fun accessToken(forceRefresh: Boolean) = "synthetic-access"
        override suspend fun clientToken(forceRefresh: Boolean) = error("No client token for this session")
        override suspend fun usesBrowserAuthorization() = true
    }
    private class Connection(uri: URI, private val body: ByteArray) : HttpURLConnection(uri.toURL()) {
        val posted = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getOutputStream() = posted
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
