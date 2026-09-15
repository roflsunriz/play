package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class SavedLibraryActionsTest {
    @Test
    fun savedTracksAndAlbumsUseTheNativeCollectionAndInvalidateCachedMembership() = runTest {
        for (item in listOf(track(), album())) {
            val server = Server()
            assertFalse(server.repository.isSaved(item))
            server.repository.setSaved(item, true)
            assertTrue(server.repository.isSaved(item))
            server.repository.setSaved(item, false)
            assertFalse(server.repository.isSaved(item))
            assertEquals(2, server.writes.size)
            val add = server.writes[0]
            assertEquals("/collection/v2/write", add.url.path)
            assertEquals("application/vnd.collection-v2.spotify.proto", add.getRequestProperty("Content-Type"))
            val expected = ProtoWire.fieldString(1, USER) + ProtoWire.fieldString(2, "collection") +
                ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, item.uri))
            assertArrayEquals(expected, add.body)
            val remove = fields(server.writes[1].body).single { it.number == 3 }.bytes!!
            assertEquals(1L, fields(remove).single { it.number == 3 }.integer)
        }
    }

    @Test
    fun aWriteIgnoredByTheServerDoesNotReportSuccess() = runTest {
        val server = Server().apply { ignoreWrites = true }
        assertTrue(runCatching { server.repository.setSaved(track(), true) }.isFailure)
        assertFalse(server.repository.isSaved(track()))
    }

    @Test
    fun emptyReadCannotConfirmRemoval() = runTest {
        val server = Server().apply { emptyCollectionResponse = true }
        assertTrue(runCatching { server.repository.setSaved(track(), false) }.isFailure)
    }

    @Test
    fun likedSongsDetailsFollowCollectionChangesAndNeverRequestAPlaylistNamedTracks() = runTest {
        val server = Server()
        val liked = SpotifyRepository.likedSongsContent("お気に入りの曲")
        assertTrue(SpotifyRepository.isLikedSongs(liked))
        assertTrue(server.repository.detail(liked).tracks.isEmpty())
        server.repository.setSaved(track(), true)
        assertEquals(listOf(TRACK), server.repository.detail(liked).tracks.map { it.uri })
        server.repository.setSaved(track(), false)
        assertTrue(server.repository.detail(liked).tracks.isEmpty())
        assertFalse(server.requests.any { it.url.path == "/playlist/v2/playlist/tracks" })
    }

    @Test
    fun partialAlbumAdditionOnlyAddsMissingTracksAndRepeatedAdditionIsIdempotent() = runTest {
        val server = Server().apply { playlistTracks += TRACK }
        server.repository.setPlaylistMembership(playlist(), album(), true)
        server.repository.setPlaylistMembership(playlist(), album(), true)
        assertEquals(listOf(TRACK, SECOND_TRACK), server.playlistTracks)
        assertEquals(1, server.playlistWrites)
    }

    @Test
    fun removalAlsoRemovesPreexistingDuplicatesWithoutTouchingOtherTracks() = runTest {
        val server = Server().apply { playlistTracks += listOf(TRACK, SECOND_TRACK, TRACK); removeOneOccurrence = true }
        server.repository.setPlaylistMembership(playlist(), track(), false)
        assertEquals(listOf(SECOND_TRACK), server.playlistTracks)
        assertEquals(2, server.playlistWrites)
    }

    @Test
    fun stalledRemovalFailsWithoutRetryingForever() = runTest {
        val server = Server().apply { playlistTracks += TRACK; ignoreWrites = true }
        assertTrue(runCatching { server.repository.setPlaylistMembership(playlist(), track(), false) }.isFailure)
        assertEquals(1, server.playlistWrites)
    }

    @Test
    fun otherOwnersAndDeniedItemCapabilitiesNeverReceiveMutations() = runTest {
        for (otherOwner in listOf(true, false)) {
            val server = Server().apply { if (otherOwner) owner = "another-user" else canEditItems = false }
            assertTrue(runCatching { server.repository.setPlaylistMembership(playlist(), track(), true) }.isFailure)
            assertEquals(0, server.playlistWrites)
        }
    }

    @Test
    fun rawPlaylistMembershipReadsBeyondTheFirstPage() = runTest {
        val server = Server().apply { playlistTracks += List(120) { SECOND_TRACK } + TRACK }
        server.repository.setPlaylistMembership(playlist(), track(), true)
        assertEquals(0, server.playlistWrites)
        assertTrue(server.requests.any { it.url.query?.contains("from=120") == true })
    }

    @Test
    fun membershipShowsAlbumChecksOnlyWhenAllTracksArePresent() = runTest {
        val server = Server().apply { playlistTracks += TRACK }
        assertTrue(server.repository.playlistMembership(track()).single().containsAll)
        assertFalse(server.repository.playlistMembership(album()).single().containsAll)
        server.repository.setPlaylistMembership(playlist(), album(), true)
        assertTrue(server.repository.playlistMembership(album()).single().containsAll)
    }

    @Test
    fun playlistPickerExcludesItemEditsDeniedByTheService() = runTest {
        val server = Server().apply { canEditItems = false }
        assertTrue(server.repository.playlistMembership(track()).isEmpty())
        assertEquals(0, server.playlistWrites)
    }

    private class Server {
        val requests = mutableListOf<Connection>()
        val writes get() = requests.filter { it.url.path == "/collection/v2/write" }
        val saved = linkedSetOf<String>()
        val playlistTracks = mutableListOf<String>()
        var ignoreWrites = false
        var emptyCollectionResponse = false
        var playlistWrites = 0
        var removeOneOccurrence = false
        var owner = USER
        var canEditItems = true
        private val tokens = object : SessionTokens {
            override suspend fun username() = USER
            override suspend fun accessToken(forceRefresh: Boolean) = "synthetic-access"
            override suspend fun clientToken(forceRefresh: Boolean) = "synthetic-client"
            override suspend fun usesBrowserAuthorization() = true
        }
        private val connection: (URI) -> HttpURLConnection = { uri -> Connection(uri, ::respond).also { requests += it } }
        val repository = SpotifyRepository(SpotifyApiClient(tokens, connection), tokens, CatalogApiClient(tokens, connection))

        private fun respond(request: Connection): ByteArray = when (request.url.path) {
            "/playlist/v2/user/$USER/rootlist" -> ProtoWire.fieldBytes(1, byteArrayOf(1)) +
                ProtoWire.fieldBytes(5, ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, playlist().uri)) +
                    ProtoWire.fieldBytes(4, ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "Playlist")) +
                        ProtoWire.fieldString(5, owner)))
            "/user-profile-view/v3/profile/$USER" -> ProtoWire.fieldString(1, "spotify:user:$USER") +
                ProtoWire.fieldString(2, "Listener")
            "/collection/v2/paging" -> if (emptyCollectionResponse) byteArrayOf() else LibraryFixtures.collection(saved.toList())
            "/collection/v2/write" -> {
                val item = fields(fields(request.body).single { it.number == 3 }.bytes!!)
                val uri = item.single { it.number == 1 }.bytes!!.toString(Charsets.UTF_8)
                if (!ignoreWrites) {
                    if (item.any { it.number == 3 && it.integer == 1L }) saved.remove(uri) else saved.add(uri)
                }
                byteArrayOf()
            }
            "/playlist/v2/playlist/$PLAYLIST_ID" -> {
                val query = request.url.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val offset = query.getValue("from").toInt()
                val page = playlistTracks.drop(offset).take(query.getValue("length").toInt())
                LibraryFixtures.rootlist(page, offset, offset + page.size < playlistTracks.size) +
                    ProtoWire.fieldVarint(2, playlistTracks.size) + ProtoWire.fieldString(16, owner) +
                    ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, "Playlist")) +
                    ProtoWire.fieldBytes(18, ProtoWire.fieldVarint(5, if (canEditItems) 1 else 0))
            }
            "/playlist/v2/playlist/$PLAYLIST_ID/changes" -> {
                playlistWrites++
                val operation = fields(fields(request.body).single { it.number == 2 }.bytes!!).single { it.number == 2 }.bytes!!
                val fields = fields(operation)
                val adding = fields.single { it.number == 1 }.integer == 2L
                val entries = fields(fields.single { it.number == if (adding) 2 else 3 }.bytes!!)
                    .filter { it.number == if (adding) 2 else 3 }
                    .map { fields(it.bytes!!).single { it.number == 1 }.bytes!!.toString(Charsets.UTF_8) }
                if (!ignoreWrites) {
                    if (adding) playlistTracks.addAll(entries)
                    else if (removeOneOccurrence) entries.forEach { playlistTracks.remove(it) }
                    else playlistTracks.removeAll(entries.toSet())
                }
                byteArrayOf()
            }
            "/pathfinder/v2/query" -> {
                val body = JSONObject(request.body.toString(Charsets.UTF_8))
                val result = if (body.getString("operationName") == "getAlbum") JSONObject().put("albumUnion",
                    JSONObject().put("uri", ALBUM).put("name", "Album").put("tracksV2", JSONObject().put("totalCount", 2)
                        .put("items", JSONArray(listOf(TRACK, SECOND_TRACK).map { JSONObject().put("track", trackJson(it)) }))))
                else JSONObject().put("tracks", JSONArray((0 until body.getJSONObject("variables").getJSONArray("uris").length()).map {
                    trackJson(body.getJSONObject("variables").getJSONArray("uris").getString(it))
                }))
                JSONObject().put("data", result).toString().toByteArray()
            }
            else -> error("Unexpected test request: ${request.url.path}")
        }
    }

    private class Connection(uri: URI, private val respond: (Connection) -> ByteArray) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val response by lazy { respond(this) }
        val body get() = output.toByteArray()
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private data class Field(val number: Int, val bytes: ByteArray? = null, val integer: Long? = null)

    companion object {
        private const val USER = "synthetic-user"
        private const val TRACK = "spotify:track:0123456789ABCDEFGHIJKL"
        private const val SECOND_TRACK = "spotify:track:abcdefghijklmnopqrstuv"
        private const val ALBUM = "spotify:album:0123456789ABCDEFGHIJKL"
        private const val PLAYLIST_ID = "0123456789ABCDEFGHIJKL"
        private fun track() = SpotifyContent(TRACK.substringAfterLast(':'), TRACK, "Track", "", null, ContentKind.TRACK)
        private fun album() = SpotifyContent(ALBUM.substringAfterLast(':'), ALBUM, "Album", "", null, ContentKind.ALBUM)
        private fun playlist() = SpotifyContent(PLAYLIST_ID, "spotify:playlist:$PLAYLIST_ID", "Playlist", "", null, ContentKind.PLAYLIST)
        private fun trackJson(uri: String) = JSONObject().put("uri", uri).put("name", "Track")
        private fun fields(bytes: ByteArray): List<Field> {
            val reader = ProtoWire.Reader(bytes)
            val fields = mutableListOf<Field>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.wireType(tag)) {
                    0 -> fields += Field(reader.fieldNumber(tag), integer = reader.readVarint())
                    2 -> fields += Field(reader.fieldNumber(tag), bytes = reader.readBytes())
                    else -> error("Unexpected fixture wire type")
                }
            }
            return fields
        }
    }
}
