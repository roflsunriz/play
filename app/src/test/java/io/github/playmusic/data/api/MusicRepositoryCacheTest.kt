package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections

class MusicRepositoryCacheTest {
    @Test
    fun searchResolvesEncodedOwnerProfilesAndSavedSongsNavigationThroughTheRepository() = runBlocking {
        val server = Server().apply { searchRegressions = true }
        val playlists = server.repository.search("トリッカル", io.github.playmusic.data.model.SearchFilter.PLAYLISTS)
        assertEquals("音楽+%20", playlists.single().ownerUsername)
        assertEquals("Display", playlists.single().ownerName)
        assertEquals(1, server.requests.count { it.url.path.startsWith("/user-profile-view/") })
        val genres = server.repository.search("インターネット", io.github.playmusic.data.model.SearchFilter.GENRES)
        val savedSongs = genres.single(MusicRepository::isLikedSongs)
        val detail = server.repository.detail(savedSongs)
        assertEquals(listOf("spotify:track:track"), detail.tracks.map { it.uri })
        assertEquals(ContentKind.PLAYLIST, detail.content.kind)
        server.repository.clearCache()
        server.wrongProfile = true
        assertTrue(runCatching { server.repository.search("トリッカル", io.github.playmusic.data.model.SearchFilter.PLAYLISTS) }.isFailure)
    }

    @Test
    fun ownerProfilesAreSharedWithDetailsAndExplicitRefreshUpdatesTheirNames() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.repository.detail(item)
        assertEquals(1, server.requests.count { it.url.path.startsWith("/user-profile-view/") })
        server.profileLabel = "Changed"
        assertEquals("Display account-a", server.repository.library(ContentKind.PLAYLIST).single().ownerName)
        val refreshed = server.repository.library(ContentKind.PLAYLIST, forceRefresh = true).single()
        assertEquals("Changed account-a", refreshed.ownerName)
        assertEquals("account-a", refreshed.ownerUsername)
        assertEquals("Changed account-a", server.repository.detail(refreshed).content.ownerName)
        assertEquals(2, server.requests.count { it.url.path.startsWith("/user-profile-view/") })
    }

    @Test
    fun librariesAreCachedAndAlbumAndTrackReadsShareCollectionPaging() = runBlocking {
        val server = Server()
        val kinds = listOf(ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.TRACK)
        val loaded = kinds.map { kind -> async { server.repository.library(kind) } }.awaitAll()
        assertEquals(listOf(1, 1, 1), loaded.map { it.size })
        assertEquals(1, server.requests.count { it.url.path == "/collection/v2/paging" })
        assertEquals(1, server.requests.count { it.url.path.endsWith("/rootlist") })
        val requests = server.requests.size
        kinds.forEachIndexed { index, kind ->
            assertEquals(loaded[index], server.repository.peekLibrary(kind))
            assertEquals(loaded[index], server.repository.library(kind))
        }
        assertEquals(requests, server.requests.size)
        val playlist = loaded[0].single()
        assertEquals("Display account-a", playlist.ownerName)
        assertEquals("account-a", playlist.ownerUsername)
        assertEquals("Display account-a", playlist.subtitle)
        assertEquals("Description", playlist.description)
        assertEquals(0, playlist.trackCount)
        val album = loaded[1].single()
        assertEquals("Artist", album.subtitle)
        assertEquals(0, album.trackCount)
        assertEquals("2026-09-12", album.releaseDate)
    }

    @Test
    fun emptyLibrariesAreRetainedAsLoadedAndForceRefreshRequeriesCollection() = runBlocking {
        val server = Server().apply { hasPlaylist = false; hasCollection = false }
        for (kind in listOf(ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.TRACK)) {
            assertNull(server.repository.peekLibrary(kind))
            assertTrue(server.repository.library(kind).isEmpty())
            assertEquals(emptyList<MusicContent>(), server.repository.peekLibrary(kind))
        }
        assertEquals(1, server.requests.count { it.url.path == "/collection/v2/paging" })
        server.hasCollection = true
        assertTrue(server.repository.library(ContentKind.ALBUM).isEmpty())
        assertEquals(1, server.repository.library(ContentKind.ALBUM, forceRefresh = true).size)
        assertEquals(2, server.requests.count { it.url.path == "/collection/v2/paging" })
    }

    @Test
    fun playlistDetailTracksKeepTheirAddedDatesInItemOrder() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.playlistItems = listOf("spotify:track:second" to 2_000L, "spotify:track:first" to 1_000L,
            "spotify:track:second" to 3_000L, "spotify:track:unstamped" to null)
        val detail = server.repository.detail(item, forceRefresh = true)
        assertEquals(listOf("spotify:track:second", "spotify:track:first",
            "spotify:track:second", "spotify:track:unstamped"), detail.tracks.map { it.uri })
        assertEquals(listOf(2_000L, 1_000L, 3_000L, null), detail.tracks.map { it.addedAtMs })
    }

    @Test
    fun detailIsCachedAndFailedForceRefreshKeepsTheVisibleValueUntilRetrySucceeds() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        val old = server.repository.detail(item)
        assertEquals("Original", old.content.title)
        assertEquals("Description", old.content.description)
        assertEquals("Display account-a", old.content.ownerName)
        assertEquals(0, old.content.trackCount)
        assertTrue(checkNotNull(old.playlistMetadata).canEdit)
        val count = server.requests.size
        assertEquals(old, server.repository.detail(item))
        assertEquals(count, server.requests.size)
        server.title = "External change"
        server.failReads = true
        val error = runCatching { server.repository.detail(item, forceRefresh = true) }.exceptionOrNull()
        assertTrue(error is ServiceApiException)
        assertEquals(old, server.repository.peekDetail(item))
        server.failReads = false
        assertEquals("External change", server.repository.detail(item, forceRefresh = true).content.title)
        assertEquals("External change", server.repository.peekDetail(item)?.content?.title)
    }

    @Test
    fun aSuccessfulLibraryRefreshDiscardsItsOldDetailsAndFailurePreservesThem() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.repository.detail(item)
        val album = server.repository.library(ContentKind.ALBUM).single()
        server.repository.detail(album)
        server.title = "External change"
        server.failReads = true
        assertTrue(runCatching { server.repository.library(ContentKind.PLAYLIST, forceRefresh = true) }.isFailure)
        assertEquals("Original", server.repository.peekLibrary(ContentKind.PLAYLIST)?.single()?.title)
        assertEquals("Original", server.repository.peekDetail(item)?.content?.title)
        server.failReads = false
        val updated = server.repository.library(ContentKind.PLAYLIST, forceRefresh = true).single()
        assertEquals("External change", updated.title)
        assertNull(server.repository.peekDetail(item))
        assertNotNull(server.repository.peekDetail(album))
        assertEquals("External change", server.repository.detail(updated).content.title)
    }

    @Test
    fun accountSwitchAndLogoutCannotExposeThePreviousAccountsCachedValues() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.repository.detail(item)
        server.repository.library(ContentKind.TRACK)
        server.tokens.account = "account-b"
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
        assertNull(server.repository.peekDetail(item))
        assertNull(server.repository.peekLibrary(ContentKind.TRACK))
        assertEquals("Display account-b", server.repository.library(ContentKind.PLAYLIST).single().ownerName)
        server.repository.library(ContentKind.TRACK)
        assertEquals(2, server.requests.count { it.url.path == "/collection/v2/paging" })
        server.tokens.account = null
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
        server.tokens.account = "account-b"
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
        server.repository.library(ContentKind.PLAYLIST)
        server.repository.clearCache()
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
    }

    @Test
    fun playlistWritesInvalidateOnlyTheAffectedDetailsAndPlaylistLibrary() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.repository.detail(item)
        val albums = server.repository.library(ContentKind.ALBUM)
        val albumDetail = server.repository.detail(albums.single())
        val updated = server.repository.updatePlaylistMetadata(item, "Saved name", "Saved description")
        assertEquals("Saved name", updated.title)
        assertEquals("Saved description", updated.description)
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
        assertNull(server.repository.peekDetail(item))
        assertEquals(albums, server.repository.peekLibrary(ContentKind.ALBUM))
        assertEquals(albumDetail, server.repository.peekDetail(albums.single()))
        assertEquals("Saved name", server.repository.library(ContentKind.PLAYLIST).single().title)
        assertEquals("Saved description", server.repository.detail(item).content.description)
    }

    @Test
    fun uncertainWriteFailuresAlsoInvalidatePotentiallyChangedValues() = runBlocking {
        val server = Server()
        val item = server.repository.library(ContentKind.PLAYLIST).single()
        server.repository.detail(item)
        server.failChanges = true
        assertTrue(runCatching { server.repository.updatePlaylistMetadata(item, "Saved name", "Saved description") }.isFailure)
        assertNull(server.repository.peekLibrary(ContentKind.PLAYLIST))
        assertNull(server.repository.peekDetail(item))
    }

    @Test
    fun searchSkipsKnownUnavailableUnionsButStillReportsTransportFailures() = runBlocking {
        val server = Server()
        val items = server.repository.search("Synthetic query")
        assertEquals(ContentKind.entries.toSet(), items.map { it.kind }.toSet())
        assertEquals(ContentKind.entries.size, items.size)
        server.failReads = true
        assertTrue(runCatching { server.repository.search("Synthetic query") }.exceptionOrNull() is ServiceApiException)
    }

    @Test
    fun peekWithoutAnAccountReaderIsConservativeAndDoesNotReturnPrivateState() = runBlocking {
        val server = Server()
        val repository = MusicRepository(server.api, server.tokens, CatalogApiClient(server.tokens, server.connection))
        val item = repository.library(ContentKind.PLAYLIST).single()
        repository.detail(item)
        assertNull(repository.peekLibrary(ContentKind.PLAYLIST))
        assertNull(repository.peekDetail(item))
    }

    private class Tokens : SessionTokens {
        @Volatile var account: String? = "account-a"
        override suspend fun username(): String = account ?: throw CancellationException("Synthetic signed out state")
        override suspend fun accessToken(forceRefresh: Boolean) = "synthetic-access"
        override suspend fun clientToken(forceRefresh: Boolean) = "synthetic-client"
        override suspend fun usesBrowserAuthorization() = true
    }

    private data class Reply(val status: Int = 200, val bytes: ByteArray)

    private class Server {
        val tokens = Tokens()
        val requests = Collections.synchronizedList(mutableListOf<Connection>())
        @Volatile var title = "Original"
        @Volatile var profileLabel = "Display"
        @Volatile var description = "Description"
        @Volatile var hasPlaylist = true
        @Volatile var hasCollection = true
        @Volatile var failReads = false
        @Volatile var failChanges = false
        var searchRegressions = false
        var wrongProfile = false
        var playlistItems: List<Pair<String, Long?>> = emptyList()
        val connection: (URI) -> HttpURLConnection = { uri -> Connection(uri, ::respond).also { requests += it } }
        val api = ServiceApiClient(tokens, connection)
        val repository = MusicRepository(api, tokens, CatalogApiClient(tokens, connection), { tokens.account })

        private fun respond(request: Connection): Reply {
            if (request.url.path.endsWith("/changes")) {
                title = "Saved name"
                description = "Saved description"
                return Reply(if (failChanges) 503 else 200, ByteArray(0))
            }
            if (failReads) return Reply(503, ByteArray(0))
            val body = when {
                request.url.path.startsWith("/user-profile-view/v3/profile/") -> {
                    if (searchRegressions) {
                        assertEquals("/user-profile-view/v3/profile/%E9%9F%B3%E6%A5%BD%2B%2520", request.url.path)
                        fieldString(1, if (wrongProfile) "spotify:user:other" else "spotify:user:%E9%9F%B3%E6%A5%BD%2B%2520") + fieldString(2, "Display")
                    } else {
                        val username = request.url.path.substringAfterLast('/')
                        fieldString(1, "spotify:user:$username") + fieldString(2, "$profileLabel $username")
                    }
                }
                request.url.path.endsWith("/rootlist") -> rootlist()
                request.url.path.startsWith("/playlist/v2/playlist/") -> playlist()
                request.url.path == "/collection/v2/paging" -> LibraryFixtures.collection(
                    if (hasCollection) listOf("spotify:album:album", "spotify:track:track") else emptyList())
                request.url.path == "/pathfinder/v2/query" -> catalog(request)
                else -> error("Unexpected synthetic endpoint")
            }
            return Reply(bytes = body)
        }

        private fun attributes() = fieldString(1, title) + fieldString(2, description)
        private fun rootlist(): ByteArray = if (!hasPlaylist) LibraryFixtures.rootlist(emptyList()) else
            fieldBytes(1, byteArrayOf(1)) + fieldVarint(2, 1) + fieldBytes(5,
                fieldBytes(3, fieldString(1, PLAYLIST_URI)) + fieldBytes(4,
                    fieldBytes(2, attributes()) + fieldVarint(3, 0) + fieldString(5, checkNotNull(tokens.account)) + fieldVarint(9, 400)))

        private fun playlist(): ByteArray {
            val items = playlistItems.fold(ByteArray(0)) { bytes, (uri, stamp) ->
                val item = fieldString(1, uri) +
                    (stamp?.let { fieldBytes(2, fieldVarint(2, it)) } ?: byteArrayOf())
                bytes + fieldBytes(3, item)
            }
            return fieldBytes(1, byteArrayOf(1)) + fieldVarint(2, 0) +
                fieldBytes(3, attributes()) + fieldString(16, checkNotNull(tokens.account)) + fieldBytes(5, items)
        }

        private fun catalog(request: Connection): ByteArray {
            val operation = JSONObject(request.sentBody.toString(Charsets.UTF_8)).getString("operationName")
            val album = JSONObject().put("uri", "spotify:album:album").put("name", "Album")
                .put("date", JSONObject().put("isoString", "2026-09-12T00:00:00Z"))
                .put("artists", JSONObject().put("items", JSONArray().put(JSONObject().put("profile", JSONObject().put("name", "Artist")))))
                .put("tracksV2", JSONObject().put("totalCount", 0).put("items", JSONArray()))
            val track = JSONObject().put("uri", "spotify:track:track").put("name", "Track")
            val data = when (operation) {
                "getAlbum" -> JSONObject().put("albumUnion", album)
                "decorateContextTracks" -> {
                    val uris = JSONObject(request.sentBody.toString(Charsets.UTF_8)).getJSONObject("variables").getJSONArray("uris")
                    JSONObject().put("tracks", JSONArray((0 until uris.length()).map { uris.getString(it) }
                        .map { JSONObject().put("uri", it).put("name", "Track") }))
                }
                "getTrack" -> JSONObject().put("trackUnion", track)
                "searchAlbums", "searchTracks", "searchPlaylists", "searchArtists", "searchPodcasts", "searchEpisodes", "searchGenres" -> {
                    val (key, entity) = when (operation) {
                        "searchAlbums" -> "albumsV2" to album
                        "searchTracks" -> "tracksV2" to track
                        "searchPlaylists" -> "playlists" to JSONObject().put("uri", PLAYLIST_URI).put("name", title)
                        "searchArtists" -> "artists" to JSONObject().put("uri", "spotify:artist:artist").put("profile", JSONObject().put("name", "Artist"))
                        "searchPodcasts" -> "podcasts" to JSONObject().put("uri", "spotify:show:show").put("name", "Show")
                        "searchEpisodes" -> "episodes" to JSONObject().put("uri", "spotify:episode:episode").put("name", "Episode")
                        else -> "genres" to JSONObject().put("uri", "genre").put("name", "Genre")
                    }
                    if (searchRegressions && operation == "searchPlaylists") entity.put("ownerV2",
                        JSONObject().put("data", JSONObject().put("uri", "spotify:user:%E9%9F%B3%E6%A5%BD%2B%2520")))
                    if (searchRegressions && operation == "searchGenres") entity.put("__typename", "Genre")
                        .put("uri", "spotify:user:@:collection").put("name", "お気に入りの曲")
                    JSONObject().put("searchV2", JSONObject().put(key, JSONObject().put("items", JSONArray()
                        .put(JSONObject().put("data", JSONObject().put("__typename", "NotFound"))).put(entity))))
                }
                else -> error("Unexpected synthetic catalog operation")
            }
            return JSONObject().put("data", data).toString().toByteArray()
        }
    }

    private class Connection(uri: URI, reply: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
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

    private companion object { const val PLAYLIST_URI = "spotify:playlist:0123456789ABCDEFGHIJKL" }
}
