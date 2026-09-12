package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class SpotifyRepositoryTest {
    @Test
    fun anEmptyItemDoesNotShiftTitlesOntoTheNextPlaylist() = runTest {
        val field = io.github.playmusic.data.auth.ProtoWire
        val contents = field.fieldBytes(3, field.fieldString(1, "")) +
            field.fieldBytes(3, field.fieldString(1, "spotify:playlist:valid")) +
            field.fieldBytes(4, field.fieldBytes(2, field.fieldString(1, "Unused entry"))) +
            field.fieldBytes(4, field.fieldBytes(2, field.fieldString(1, "Correct title")))
        val backend = Backend { Reply(bytes = field.fieldBytes(5, contents)) }
        val item = backend.repository.library(ContentKind.PLAYLIST).single()
        assertEquals("valid", item.id)
        assertEquals("Correct title", item.title)
    }

    @Test
    fun decoratedPlaylistTitlesAreUsedAndUntitledPlaylistsRemainVisible() = runTest {
        val backend = Backend { request ->
            assertTrue(request.url.path.endsWith("/rootlist"))
            Reply(bytes = LibraryFixtures.decoratedRootlist())
        }
        val items = backend.repository.library(ContentKind.PLAYLIST)
        assertEquals(listOf("named", "untitled"), items.map { it.id })
        assertEquals(listOf("Named playlist", ""), items.map { it.title })
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun libraryLoadsPlaylistsBeyondTheFirstPageAndOldFiftyItemLimit() = runTest {
        val backend = Backend { request ->
            if (request.url.path.endsWith("/rootlist")) {
                val second = request.url.query.contains("from=50")
                Reply(bytes = LibraryFixtures.rootlist(
                    (if (second) 50..52 else 0..49).map { "spotify:playlist:p$it" },
                    offset = if (second) 50 else 0,
                    truncated = !second,
                ))
            } else Reply(bytes = LibraryFixtures.playlist(request.url.path.substringAfterLast('/')))
        }
        val items = backend.repository.library(ContentKind.PLAYLIST)
        assertEquals(53, items.size)
        assertEquals("p0", items.first().title)
        assertEquals("p52", items.last().title)
        assertEquals(2, backend.requests.count { it.url.path.endsWith("/rootlist") })
    }

    @Test
    fun collectionFollowsNextTokenAndRemovesDeletedEntriesBeforeMetadata() = runTest {
        var page = 0
        val backend = Backend { request ->
            if (request.url.path == "/collection/v2/paging") {
                assertEquals("application/vnd.collection-v2.spotify.proto", request.getRequestProperty("Accept"))
                if (page++ == 0) Reply(bytes = LibraryFixtures.collection(listOf("spotify:track:first"), "next-page"))
                else {
                    assertTrue(request.sentBody.toString(Charsets.UTF_8).contains("next-page"))
                    Reply(bytes = LibraryFixtures.collection(listOf("spotify:track:second")) +
                        LibraryFixtures.collectionItem("spotify:track:first", removed = true))
                }
            } else {
                assertTrue(request.sentBody.toString(Charsets.UTF_8).contains("spotify:track:second"))
                Reply(bytes = """{"data":{"tracks":[{"uri":"spotify:track:second","name":"Second track"}]}}""".toByteArray())
            }
        }
        val items = backend.repository.library(ContentKind.TRACK)
        assertEquals(listOf("Second track"), items.map { it.title })
        assertEquals(2, backend.requests.count { it.url.path == "/collection/v2/paging" })
        assertEquals(1, backend.requests.count { it.url.path == "/pathfinder/v2/query" })
    }

    @Test
    fun albumMetadataComesFromTheObservedCatalogContract() = runTest {
        val backend = Backend { request ->
            Reply(bytes = if (request.url.path == "/collection/v2/paging") {
                LibraryFixtures.collection(listOf("spotify:album:first"))
            } else """{"data":{"albumUnion":{"uri":"spotify:album:first","name":"Album",
                "artists":{"items":[{"profile":{"name":"Artist"}}]}}}}""".toByteArray())
        }
        val item = backend.repository.library(ContentKind.ALBUM).single()
        assertEquals("spotify:album:first", item.uri)
        assertEquals("Album", item.title)
        assertEquals("Artist", item.subtitle)
    }

    @Test
    fun metadataFailureIsReportedInsteadOfReturningAnEmptyLibrary() = runTest {
        val backend = Backend { request ->
            if (request.url.path == "/collection/v2/paging") {
                Reply(bytes = LibraryFixtures.collection(listOf("spotify:track:first")))
            } else Reply(status = 503)
        }
        val error = runCatching { backend.repository.library(ContentKind.TRACK) }.exceptionOrNull()
        assertTrue(error is SpotifyApiException)
        assertEquals(503, (error as SpotifyApiException).status)
    }

    @Test
    fun embeddedMetadataFailureIsReportedAndCancellationIsPreserved() = runTest {
        for (cancel in listOf(false, true)) {
            val backend = Backend { request ->
                if (request.url.path == "/collection/v2/paging") {
                    Reply(bytes = LibraryFixtures.collection(listOf("spotify:track:first")))
                } else if (cancel) throw CancellationException("cancelled")
                else Reply(bytes = """{"errors":[{"message":"Metadata unavailable"}]}""".toByteArray())
            }
            val error = runCatching { backend.repository.library(ContentKind.TRACK) }.exceptionOrNull()
            assertTrue(if (cancel) error is CancellationException else error is SpotifyApiException)
        }
    }

    @Test
    fun repeatedCollectionTokenFailsWithoutAnInfiniteLoop() = runTest {
        val backend = Backend { Reply(bytes = LibraryFixtures.collection(emptyList(), "same-token")) }
        val error = runCatching { backend.repository.library(ContentKind.TRACK) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(2, backend.requests.size)
    }

    @Test
    fun unauthorizedRequestRenewsTokensOnceAndReplaysTheBody() = runTest {
        var attempt = 0
        val backend = Backend { if (attempt++ == 0) Reply(status = 401) else Reply() }
        backend.api.postProto("/collection/v2/paging", byteArrayOf(8, 42))
        assertEquals(1, backend.tokens.accessRefreshes)
        assertEquals(0, backend.tokens.clientRefreshes)
        assertEquals("Win32_x86_64", backend.requests.last().getRequestProperty("App-Platform"))
        assertTrue(backend.requests.all { it.sentBody.contentEquals(byteArrayOf(8, 42)) })
        assertEquals("Bearer refreshed-access", backend.requests.last().getRequestProperty("Authorization"))
    }

    private data class Reply(val status: Int = 200, val bytes: ByteArray = ByteArray(0))

    private class Tokens : SessionTokens {
        var accessRefreshes = 0
        var clientRefreshes = 0
        override suspend fun username() = "synthetic-user"
        override suspend fun accessToken(forceRefresh: Boolean): String {
            if (forceRefresh) accessRefreshes++
            return if (forceRefresh) "refreshed-access" else "initial-access"
        }
        override suspend fun clientToken(forceRefresh: Boolean): String {
            if (forceRefresh) clientRefreshes++
            return "synthetic-client-token"
        }
        override suspend fun usesBrowserAuthorization() = true
    }

    private class Backend(reply: (Connection) -> Reply) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Connection>())
        val tokens = Tokens()
        private val connection: (URI) -> HttpURLConnection = { uri -> Connection(uri, reply).also { requests += it } }
        val api = SpotifyApiClient(tokens, connection)
        val repository = SpotifyRepository(api, tokens, CatalogApiClient(tokens, connection))
    }

    private class Connection(uri: URI, private val reply: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val response by lazy { reply(this) }
        val sentBody: ByteArray get() = output.toByteArray()
        override fun getOutputStream() = output
        override fun getResponseCode() = response.status
        override fun getInputStream() = ByteArrayInputStream(response.bytes)
        override fun getErrorStream() = ByteArrayInputStream(response.bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
