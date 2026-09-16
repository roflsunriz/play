package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentArtist
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.LyricsSyncType
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class LrclibApiClientTest {
    @Test fun syncedLyricsParseTimestampsMetadataAndOffsets() {
        val response = JSONObject().put("id", 7).put("trackName", "Song").put("artistName", "Singer")
            .put("syncedLyrics", "[ti:Title]\n[ar:Singer]\n[00:01.00]First\n[00:02.5][00:03.05]Second\n[offset:+500]\n[00:04]Third")
        val lyrics = LrclibLyrics.parse(TRACK, response)
        checkNotNull(lyrics)
        assertEquals(LyricsSyncType.LINE_SYNCED, lyrics.syncType)
        assertEquals("LRCLIB", lyrics.providerDisplayName)
        assertEquals("7", lyrics.providerLyricsId)
        assertEquals(listOf(1_000L, 2_500L, 3_050L, 4_500L), lyrics.lines.map { it.startTimeMs })
        assertEquals(listOf("First", "Second", "Second", "Third"), lyrics.lines.map { it.words })
        assertEquals(1, lyrics.activeLine(2_999))
        assertEquals(3, lyrics.activeLine(4_500))
    }

    @Test fun plainLyricsBecomeUnsyncedAndInstrumentalOrEmptyMeansUnavailable() {
        val plain = LrclibLyrics.parse(TRACK, JSONObject().put("plainLyrics", "Line one\n\nLine two\n"))
        checkNotNull(plain)
        assertEquals(LyricsSyncType.UNSYNCED, plain.syncType)
        assertEquals(listOf("Line one", "Line two"), plain.lines.map { it.words })
        assertEquals(-1, plain.activeLine(60_000))
        assertNull(LrclibLyrics.parse(TRACK, JSONObject().put("instrumental", true).put("plainLyrics", "x")))
        assertNull(LrclibLyrics.parse(TRACK, JSONObject().put("plainLyrics", "  \n ")))
        assertNull(LrclibLyrics.parse(TRACK, JSONObject()))
    }

    @Test fun mismatchedRecordsNeverBecomeLyrics() {
        val content = track().copy(albumTitle = "Album", durationMs = 200_000)
        fun response(track: String, artist: String, album: String = "Album", duration: Int = 200) =
            JSONObject().put("trackName", track).put("artistName", artist).put("albumName", album)
                .put("duration", duration).put("plainLyrics", "Words")
        assertTrue(matchLrclib(content, listOf("Singer"), response("Song", "Singer")))
        assertTrue(matchLrclib(track().copy(subtitle = "Lead, Singer"), emptyList(), response("Song", "Singer")))
        assertTrue(matchLrclib(content, listOf("Singer"), response("Song", "Singer", duration = 205)))
        assertTrue(!matchLrclib(content, listOf("Singer"), response("Other", "Singer")))
        assertTrue(!matchLrclib(content, listOf("Singer"), response("Song", "Stranger")))
        assertTrue(!matchLrclib(content, listOf("Singer"), response("Song", "Singer", album = "Different")))
        assertTrue(!matchLrclib(content, listOf("Singer"), response("Song", "Singer", duration = 300)))
    }

    @Test fun notFoundMeansUnavailableWhileOtherFailuresThrow() = runTest {
        val missing = client(statuses = listOf(404))
        assertNull(missing.lyrics(track()))
        val broken = client(statuses = listOf(400))
        assertTrue(runCatching { broken.lyrics(track()) }.isFailure)
        val gone = client(statuses = listOf(500, 500, 500))
        assertTrue(runCatching { gone.lyrics(track()) }.isFailure)
    }

    @Test fun rateLimitingHonorsRetryAfterAndReplaysTheQuery() = runTest {
        val connections = mutableListOf<Connection>()
        val sleeps = mutableListOf<Long>()
        val client = client(connections, statuses = listOf(429, 200),
            headers = listOf(mapOf("Retry-After" to "2")), sleeps = sleeps)
        val lyrics = client.lyrics(track())
        checkNotNull(lyrics)
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(2, connections.size)
        assertTrue(connections.all { it.url.toString().contains("track_name=Song") })
        val limited = client(mutableListOf(), statuses = listOf(429, 429, 429), sleeps = mutableListOf())
        assertTrue(runCatching { limited.lyrics(track()) }.isFailure)
    }

    @Test fun responsesWithoutTheRequestedIdentityAreRejected() = runTest {
        val other = JSONObject().put("trackName", "Other").put("artistName", "Singer").put("plainLyrics", "Words")
        val client = client(mutableListOf(), body = other.toString())
        assertNull(client.lyrics(track()))
    }

    private fun track() = SpotifyContent("track", TRACK, "Song", "Singer", null, ContentKind.TRACK,
        durationMs = 200_000, albumTitle = "Album", artists = listOf(ContentArtist("spotify:artist:singer", "Singer")))

    private fun client(connections: MutableList<Connection> = mutableListOf(), statuses: List<Int> = listOf(200),
        headers: List<Map<String, String>> = emptyList(), sleeps: MutableList<Long> = mutableListOf(),
        body: String = JSONObject().put("trackName", "Song").put("artistName", "Singer")
            .put("plainLyrics", "Words").toString()): LrclibApiClient =
        LrclibApiClient({ uri ->
            val status = statuses.getOrElse(connections.size) { statuses.last() }
            Connection(uri, status, body, headers.getOrElse(connections.size) { emptyMap() })
                .also { connections += it }
        }, { sleeps.add(it) })

    private class Connection(uri: URI, private val status: Int, private val reply: String,
        private val headers: Map<String, String>) : HttpURLConnection(uri.toURL()) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(reply.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getContentType(): String? = if (status == 200) "application/json" else null
        override fun getHeaderField(name: String): String? = headers[name]
            ?: if (status == 200 && name.equals("Content-Type", true)) "application/json" else null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private companion object {
        const val TRACK = "spotify:track:0000000000000000000001"
    }
}
