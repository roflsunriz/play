package io.github.playmusic.data.api

import io.github.playmusic.data.model.ArtistReleaseType
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class ArtistCatalogTest {
    @Test
    fun allSectionsKeepTheirTypesMetadataAndNavigableUris() = runBlocking {
        val backend = Backend { request ->
            when (request.operation) {
                "queryArtistOverview" -> artist().put("stats", JSONObject().put("monthlyListeners", 12345678901L)
                    .put("followers", 300L).put("worldRank", JSONObject.NULL))
                "getArtistNameAndTracks" -> artist().put("discography", JSONObject().put("topTracks", JSONObject()
                    .put("items", JSONArray().put(JSONObject().put("track", entity("track", TRACK))))
                    .put("pagingInfo", JSONObject().put("nextOffset", JSONObject.NULL))))
                "decorateContextTracks" -> return@Backend JSONObject().put("tracks", JSONArray().put(entity("track", TRACK)))
                "queryArtistDiscographyAll" -> {
                    val offset = request.variables.getInt("offset")
                    assertEquals("DATE_DESC", request.variables.getString("order"))
                    artist().put("discography", JSONObject().put("all", page(if (offset == 0) "ALBUM" else "EP",
                        if (offset == 0) ALBUM else EP).put("totalCount", 2)))
                }
                else -> error("Unexpected operation")
            }.let { JSONObject().put("artistUnion", it) }
        }
        val result = backend.client.detail(content())
        val page = checkNotNull(result.artistPage)
        assertEquals(12345678901L, page.monthlyListeners)
        assertNull(page.worldRank)
        assertFalse(page.isFollowed)
        assertEquals("Service biography", page.biography)
        assertEquals("BIOGRAPHY", page.biographySource)
        assertEquals(listOf(ArtistReleaseType.ALBUM, ArtistReleaseType.EP), page.discography.map { it.type })
        assertEquals(listOf(ALBUM, EP), page.discography.map { it.content.id })
        assertEquals("https://images.example/cover", page.discography.first().content.imageUrl)
        assertEquals(ContentKind.ALBUM, page.appearsOn.single().kind)
        assertEquals(ContentKind.PLAYLIST, page.featuringPlaylists.single().kind)
        assertEquals(ContentKind.PLAYLIST, page.discoveredOnPlaylists.single().kind)
        assertEquals(ContentKind.ARTIST, page.suggestedArtists.single().kind)
        assertEquals(result.tracks, page.songRadioSeeds)
    }

    @Test
    fun followAndUnfollowAreConfirmedByFreshServiceState() = runBlocking {
        for (followed in listOf(true, false)) {
            val operations = mutableListOf<String>()
            val backend = Backend { request ->
                operations += request.operation
                when (request.operation) {
                    "addToLibrary", "removeFromLibrary" -> {
                        assertEquals(listOf("spotify:artist:$ARTIST"), request.variables.getJSONArray("libraryItemUris").let {
                            (0 until it.length()).map(it::getString)
                        })
                        JSONObject()
                    }
                    "queryArtistOverview" -> JSONObject().put("artistUnion", artist().put("saved", followed))
                    else -> error("Unexpected request")
                }
            }
            assertEquals(followed, backend.client.setArtistFollowed("spotify:artist:$ARTIST", followed))
            assertEquals(listOf(if (followed) "addToLibrary" else "removeFromLibrary", "queryArtistOverview"), operations)
        }
    }

    @Test
    fun invalidFollowTargetsUnconfirmedWritesAndMalformedStatisticsFail() = runBlocking {
        val backend = Backend { request ->
            if (request.operation == "queryArtistOverview") JSONObject().put("artistUnion", artist()) else JSONObject()
        }
        assertTrue(runCatching { backend.client.setArtistFollowed("spotify:album:$ALBUM", true) }.isFailure)
        assertTrue(runCatching { backend.client.setArtistFollowed("spotify:artist:$ARTIST", true) }.isFailure)
        for (value in listOf(-1, 0.5, "100")) {
            assertTrue(runCatching { ArtistCatalogJson.overview(artist().put("stats", JSONObject().put("monthlyListeners", value)),
                emptyList(), emptyList()) }.isFailure)
        }
        assertTrue(runCatching { ArtistCatalogJson.overview(artist().apply { remove("relatedContent") }, emptyList(), emptyList()) }.isFailure)
    }

    @Test
    fun groupedEditionsUseServiceRepresentativeAndUnknownEntitiesFail() {
        val group = page("ALBUM", ALBUM)
        group.getJSONArray("items").getJSONObject(0).getJSONObject("releases").getJSONArray("items")
            .put(entity("album", EP).put("type", "ALBUM"))
        assertEquals(ALBUM, ArtistCatalogJson.releaseGroups(group).single().content.id)
        assertTrue(runCatching { ArtistCatalogJson.releaseGroups(page("NEW_UNKNOWN_TYPE", ALBUM)) }.isFailure)
        assertTrue(runCatching {
            ArtistCatalogJson.overview(artist().also { it.getJSONObject("relatedContent").getJSONObject("featuringV2")
                .getJSONArray("items").getJSONObject(0).getJSONObject("data").put("__typename", "UnknownPlaylistResult") }, emptyList(), emptyList())
        }.isFailure)
    }

    @Test
    fun individualRelatedPlaylistFailuresKeepValidItemsAndExposeTheirCount() {
        val artist = artist()
        val items = artist.getJSONObject("relatedContent").getJSONObject("featuringV2").getJSONArray("items")
        for (type in listOf("GenericError", "NotFound", "RestrictedContent")) {
            items.put(JSONObject().put("data", JSONObject().put("__typename", type)))
        }
        val result = ArtistCatalogJson.overview(artist, emptyList(), emptyList())
        assertEquals(listOf(ALBUM), result.featuringPlaylists.map { it.id })
        assertEquals(listOf(ALBUM), result.discoveredOnPlaylists.map { it.id })
        assertEquals(3, result.unavailableRelatedItems)
        assertEquals("Service biography", result.biography)
        assertEquals(1, result.suggestedArtists.size)
    }

    @Test
    fun entirelyFailedRelatedPlaylistShelvesRemainAnExplicitPartialFailure() {
        val artist = artist()
        for (key in listOf("featuringV2", "discoveredOnV2")) {
            artist.getJSONObject("relatedContent").getJSONObject(key).put("items", JSONArray()
                .put(JSONObject().put("data", JSONObject().put("__typename", "GenericError"))))
        }
        val result = ArtistCatalogJson.overview(artist, emptyList(), emptyList())
        assertTrue(result.featuringPlaylists.isEmpty())
        assertTrue(result.discoveredOnPlaylists.isEmpty())
        assertEquals(2, result.unavailableRelatedItems)
        assertEquals(1, result.appearsOn.size)
        assertEquals(1, result.suggestedArtists.size)
    }

    private class Backend(private val response: (Connection) -> JSONObject) {
        private val session = object : SessionTokens {
            override suspend fun username() = "test-account"
            override suspend fun accessToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clientToken(forceRefresh: Boolean) = "unused"
            override suspend fun usesBrowserAuthorization() = true
        }
        val client = CatalogApiClient(session) { Connection(it, response) }
    }

    private class Connection(uri: URI, private val response: (Connection) -> JSONObject) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val body get() = JSONObject(output.toString(Charsets.UTF_8))
        val operation get() = body.getString("operationName")
        val variables get() = body.getJSONObject("variables")
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(JSONObject().put("data", response(this)).toString().toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private companion object {
        const val ARTIST = "1111111111111111111111"
        const val TRACK = "2222222222222222222222"
        const val ALBUM = "3333333333333333333333"
        const val EP = "4444444444444444444444"
        fun content() = SpotifyContent(ARTIST, "spotify:artist:$ARTIST", "Artist", "", null, ContentKind.ARTIST)
        fun entity(kind: String, id: String) = JSONObject().put("uri", "spotify:$kind:$id").put("name", "Title")
        fun page(type: String, id: String) = JSONObject().put("items", JSONArray().put(JSONObject().put("releases",
            JSONObject().put("items", JSONArray().put(entity("album", id).put("type", type).put("coverArt", JSONObject()
                .put("sources", JSONArray().put(JSONObject().put("url", "https://images.example/cover")))))))))
        fun artist(): JSONObject {
            val playlist = JSONObject().put("items", JSONArray().put(JSONObject().put("data", entity("playlist", ALBUM)
                .put("__typename", "Playlist"))))
            return entity("artist", ARTIST).put("__typename", "Artist").put("saved", false).put("stats", JSONObject())
                .put("profile", JSONObject().put("name", "Artist").put("biography", JSONObject().put("text", "Service biography").put("type", "BIOGRAPHY")))
                .put("relatedContent", JSONObject().put("appearsOn", page("ALBUM", ALBUM)).put("featuringV2", playlist)
                    .put("discoveredOnV2", JSONObject(playlist.toString())).put("relatedArtists", JSONObject().put("items", JSONArray()
                        .put(entity("artist", EP).put("profile", JSONObject().put("name", "Suggested artist"))))))
        }
    }
}
