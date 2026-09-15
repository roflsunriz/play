package io.github.playmusic.data.playback

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.api.SessionTokens
import io.github.playmusic.data.api.SpotifyApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

class StreamingApiClientTest {
    @Test fun resolvesRelinkedAudioUsingTheExplicitOriginalIdentity(): Unit = runBlocking {
        val response = relinkedManifest()
        // An unrelated item may precede the matching replacement in a response.
        response.getJSONObject("media").put(OTHER, JSONObject(manifest()).getJSONObject("media").getJSONObject(TRACK))
        val calls = mutableListOf<Connection>()
        val api = StreamingApiClient(Tokens(), openConnection = { uri ->
            Connection(uri, 200, if (uri.path.startsWith("/track-playback/")) response.toString() else storage())
                .also { calls += it }
        })
        val audio = api.resolve(TRACK)
        assertEquals(FILE, audio.fileId)
        assertEquals(listOf(CDN), audio.urls)
        assertEquals(audio, api.resolve(TRACK))
        assertEquals(2, calls.size)
        assertTrue(calls.first().url.path.endsWith(TRACK))
        assertTrue(calls.last().url.path.endsWith("/10/$FILE"))
    }

    @Test fun rejectsUnrelatedMalformedAndAmbiguousRelinks() {
        val missingLink = relinkedManifest().apply { replacementMetadata(this).remove("linked_from_uri") }
        val wrongLink = relinkedManifest().apply { replacementMetadata(this).put("linked_from_uri", OTHER) }
        val wrongIdentity = relinkedManifest().apply { replacementMetadata(this).put("uri", OTHER) }
        val nullLink = relinkedManifest().apply { replacementMetadata(this).put("linked_from_uri", JSONObject.NULL) }
        val ambiguous = relinkedManifest().apply {
            val second = JSONObject(getJSONObject("media").getJSONObject(REPLACEMENT).toString())
            second.getJSONObject("item").getJSONObject("metadata").put("uri", OTHER)
            getJSONObject("media").put(OTHER, second)
        }
        val wrongKind = relinkedManifest().toString().replace(REPLACEMENT, "spotify:album:0000000000000000000002")
        for (body in listOf(missingLink, wrongLink, wrongIdentity, nullLink, ambiguous).map(JSONObject::toString) + wrongKind) {
            var calls = 0
            val api = StreamingApiClient(Tokens(), openConnection = { uri ->
                calls++
                Connection(uri, 200, body)
            })
            assertThrows(IllegalStateException::class.java) { runBlocking { api.resolve(TRACK) } }
            assertEquals(1, calls)
        }
    }

    @Test fun resolvesFullAudioAndExpiresTheAccountScopedCache(): Unit = runBlocking {
        val tokens = Tokens()
        val calls = mutableListOf<Connection>()
        var time = 0L
        val api = StreamingApiClient(tokens, openConnection = { uri ->
            Connection(uri, 200, if (uri.path.startsWith("/track-playback/")) manifest() else storage())
                .also { calls += it }
        }, now = { time })
        val audio = api.resolve(TRACK)
        assertEquals(FILE, audio.fileId)
        assertEquals(listOf(CDN), audio.urls)
        assertEquals("manifestFileFormat=file_ids_mp4", calls[0].url.query)
        assertTrue(calls[1].url.path.endsWith("/10/$FILE"))
        assertEquals("Bearer synthetic-access", calls[0].getRequestProperty("Authorization"))
        assertTrue(calls.all { it.disconnected && !it.instanceFollowRedirects })
        assertEquals(audio, api.resolve(TRACK))
        assertEquals(2, calls.size)
        time = 300_000
        api.resolve(TRACK)
        assertEquals(4, calls.size)
        tokens.owner = "second-user"
        api.resolve(TRACK)
        assertEquals(6, calls.size)
    }

    @Test fun refreshesOnceAfterUnauthorizedAndDoesNotRetryForbidden() {
        for (status in listOf(401, 403)) {
            val tokens = Tokens()
            var calls = 0
            val api = StreamingApiClient(tokens, openConnection = { uri ->
                calls++
                Connection(uri, status, "{}")
            })
            val error = assertThrows(SpotifyApiException::class.java) { runBlocking { api.resolve(TRACK) } }
            assertEquals(status, error.status)
            assertEquals(if (status == 401) 2 else 1, calls)
            assertEquals(if (status == 401) 1 else 0, tokens.refreshes)
        }
    }

    @Test fun rejectsMissingOrWrongManifestAndUntrustedDownloadLocations() {
        for (response in listOf("{}", manifest().replace("10", "11"), manifest().replace(FILE, "not-a-file"),
            manifest().replace(TRACK, "spotify:track:0000000000000000000002"))) {
            val api = StreamingApiClient(Tokens(), openConnection = { uri -> Connection(uri, 200, response) })
            assertThrows(Exception::class.java) { runBlocking { api.resolve(TRACK) } }
        }
        for (url in listOf("http://audio.scdn.co/audio", "https://scdn.co.example.org/audio",
            "https://user@audio.scdn.co/audio", "https://audio.scdn.co:444/audio", "file:///audio")) {
            assertThrows(IllegalArgumentException::class.java) { StreamingApiClient.validateAudioUrl(url) }
        }
        StreamingApiClient.validateAudioUrl(CDN)
    }

    @Test fun oldSessionsAndNonTrackItemsDoNotSendRequests() {
        val tokens = Tokens().apply { browser = false }
        var requested = false
        val api = StreamingApiClient(tokens, openConnection = { requested = true; error("Unexpected request") })
        assertThrows(BrowserAuthorizationRequiredException::class.java) { runBlocking { api.resolve(TRACK) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.resolve("spotify:album:invalid") } }
        assertFalse(requested)
    }

    private class Tokens : SessionTokens {
        var owner = "first-user"
        var browser = true
        var refreshes = 0
        override suspend fun username() = owner
        override suspend fun usesBrowserAuthorization() = browser
        override suspend fun accessToken(forceRefresh: Boolean): String {
            if (forceRefresh) refreshes++
            return "synthetic-access"
        }
        override suspend fun clientToken(forceRefresh: Boolean): String = error("Browser sessions do not use a client token")
    }

    private class Connection(uri: URI, private val status: Int, private val body: String) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = inputStream
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

    private fun manifest() = """{"media":{"$TRACK":{"item":{"manifest":{"file_ids_mp4":[
        {"format":"11","file_id":"0000000000000000000000000000000000000000"},
        {"format":"10","file_id":"$FILE"}]}}}}}"""
    private fun storage() = """{"result":"CDN","fileid":"$FILE","cdnurl":["$CDN"],"ttl":86400}"""

    private fun relinkedManifest(): JSONObject {
        val entry = JSONObject(manifest()).getJSONObject("media").getJSONObject(TRACK)
        entry.getJSONObject("item").put("metadata", JSONObject().put("uri", REPLACEMENT).put("linked_from_uri", TRACK))
        return JSONObject().put("media", JSONObject().put(REPLACEMENT, entry))
    }

    private fun replacementMetadata(response: JSONObject) = response.getJSONObject("media")
        .getJSONObject(REPLACEMENT).getJSONObject("item").getJSONObject("metadata")

    companion object {
        private const val TRACK = "spotify:track:0000000000000000000001"
        private const val REPLACEMENT = "spotify:track:0000000000000000000002"
        private const val OTHER = "spotify:track:0000000000000000000003"
        private const val FILE = "0123456789abcdef0123456789abcdef01234567"
        private const val CDN = "https://audio.scdn.co/audio?synthetic=capability"
    }
}
