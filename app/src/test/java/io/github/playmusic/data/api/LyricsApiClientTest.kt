package io.github.playmusic.data.api

import io.github.playmusic.data.model.LyricsSyncType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI

class LyricsApiClientTest {
    @Test fun timestampsDriveForwardBackwardAndDuplicateLineBoundaries() {
        val lyrics = LyricsJson.parse(TRACK_URI, payload(listOf(1_000L, 2_000L, 2_000L, 5_000L)))
        assertEquals(LyricsSyncType.LINE_SYNCED, lyrics.syncType)
        assertEquals("Synthetic provider", lyrics.providerDisplayName)
        assertEquals("provider-id", lyrics.providerLyricsId)
        assertEquals(listOf(-1, -1, 0, 0, 2, 2, 3, 0),
            listOf(-1L, 999, 1_000, 1_999, 2_000, 4_999, 5_000, 1_001).map(lyrics::activeLine))
    }

    @Test fun lineBoundariesUseNextStartEvenForSyllableSyncedResponses() {
        val json = JSONObject(payload(listOf(1_000, 5_000)))
        json.getJSONObject("lyrics").getJSONArray("lines").getJSONObject(0).put("endTimeMs", "3000")
        json.getJSONObject("lyrics").put("syncType", "SYLLABLE_SYNCED").put("capStatus", "CAPPED")
        val lyrics = LyricsJson.parse(TRACK_URI, json.toString())
        assertEquals(0, lyrics.activeLine(2_999))
        assertEquals(0, lyrics.activeLine(3_000))
        assertEquals(1, lyrics.activeLine(5_000))
        assertTrue(lyrics.isTimeSynced)
        assertTrue(lyrics.isCapped)
    }

    @Test fun unsyncedAndEmptyAreDistinctFromUnavailable() = runBlocking {
        val json = JSONObject(payload(listOf(0, 0), "UNSYNCED"))
        json.getJSONObject("lyrics").getJSONArray("lines").getJSONObject(0).remove("startTimeMs")
        val lyrics = LyricsJson.parse(TRACK_URI, json.toString())
        assertEquals(-1, lyrics.activeLine(99_000))
        assertTrue(lyrics.lines.all { it.startTimeMs == null })
        assertTrue(LyricsJson.parse(TRACK_URI, payload(emptyList())).lines.isEmpty())
        val backend = Backend { Reply(404, "private server error") }
        assertNull(backend.client.lyrics(TRACK_URI))
        assertEquals(0, backend.refreshes)
    }

    @Test fun validatesTimesOrderWordsAndShapeWithoutLeakingResponse() {
        val bodies = mutableListOf("private response text", "{}", payload(listOf(5_000, 1_000)), payload(listOf(0), "UNKNOWN"))
        for (invalid in listOf("-1", "1.5", "9223372036854775808", "not-a-time")) {
            val json = JSONObject(payload(listOf(1_000)))
            json.getJSONObject("lyrics").getJSONArray("lines").getJSONObject(0).put("startTimeMs", invalid)
            bodies += json.toString()
        }
        val json = JSONObject(payload(listOf(1_000)))
        json.getJSONObject("lyrics").getJSONArray("lines").getJSONObject(0).put("words", 123)
        bodies += json.toString()
        for (body in bodies) {
            val error = runCatching { LyricsJson.parse(TRACK_URI, body) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("Lyrics response is invalid or uses unsupported timing", error?.message)
            assertNull(error?.cause)
        }
    }

    @Test fun authenticatesOnlyToFixedEndpointAndRefreshes401Once() = runBlocking {
        val backend = Backend { request ->
            assertEquals("https", request.url.protocol)
            assertEquals("spclient.wg.spotify.com", request.url.host)
            assertEquals("/color-lyrics/v2/track/${TRACK_URI.substringAfterLast(':')}", request.url.path)
            assertEquals("format=json&vocalRemoval=false&market=from_token", request.url.query)
            assertEquals("WebPlayer", request.getRequestProperty("App-Platform"))
            assertEquals("GET", request.requestMethod)
            assertFalse(request.instanceFollowRedirects)
            if (request.getRequestProperty("Authorization") == "Bearer old") Reply(401, "private") else Reply(200, payload(listOf(0)))
        }
        assertNotNull(backend.client.lyrics(TRACK_URI))
        assertEquals(1, backend.refreshes)
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.all { it.disconnected })
    }

    @Test fun persistentUnauthorizedAndOtherHttpFailuresAreNotMissingLyrics() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 302)) {
            val backend = Backend { Reply(status, "private account details") }
            val error = runCatching { backend.client.lyrics(TRACK_URI) }.exceptionOrNull()
            assertTrue(error is ServiceApiException)
            assertFalse(error?.message.orEmpty().contains("private"))
            assertEquals(if (status == 401) 2 else 1, backend.requests.size)
        }
    }

    @Test fun malformedTrackUrisNeverSendTokens() = runBlocking {
        val backend = Backend { error("Must not send a request") }
        for (uri in listOf("https://example.invalid", TRACK_URI.replace("track", "album"), "$TRACK_URI?next=1")) {
            assertTrue(runCatching { backend.client.lyrics(uri) }.isFailure)
        }
        assertTrue(backend.requests.isEmpty())
    }

    private class Backend(val respond: (Connection) -> Reply) {
        var refreshes = 0
        val requests = mutableListOf<Connection>()
        val session = object : SessionTokens {
            override suspend fun username() = "synthetic"
            override suspend fun accessToken(forceRefresh: Boolean): String {
                if (forceRefresh) refreshes++
                return if (forceRefresh) "new" else "old"
            }
            override suspend fun clientToken(forceRefresh: Boolean) = error("No client token needed")
            override suspend fun usesBrowserAuthorization() = true
        }
        val client = LyricsApiClient(session) { uri -> Connection(uri, respond).also(requests::add) }
    }

    private data class Reply(val status: Int, val body: String)
    private class Connection(uri: URI, respond: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val reply by lazy { respond(this) }
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = reply.status
        override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray())
        override fun getErrorStream() = error("Error body must not be read")
    }

    companion object {
        private const val TRACK_URI = "spotify:track:0000000000000000000001"
        private fun payload(times: List<Long>, sync: String = "LINE_SYNCED"): String = JSONObject().put("lyrics", JSONObject()
            .put("syncType", sync).put("providerDisplayName", "Synthetic provider").put("providerLyricsId", "provider-id")
            .put("language", "en").put("lines", JSONArray(times.mapIndexed { index, time -> JSONObject()
                .put("startTimeMs", time.toString()).put("endTimeMs", "0").put("words", "Synthetic line $index") }))).toString()
    }
}
