package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class CatalogNavigationTest {
    @Test
    fun eachFilterRequestsOnlyItsCategoriesIncludingBothPodcastKinds() = runBlocking {
        for (filter in SearchFilter.entries) {
            val backend = Backend { connection ->
                val operation = connection.body.getString("operationName")
                val key = searchKeys.getValue(operation)
                assertEquals("トリッカル", connection.body.getJSONObject("variables").getString("searchTerm"))
                Reply(data("searchV2", JSONObject().put(key, JSONObject().put("items", JSONArray()))))
            }
            assertEquals(emptyList<SpotifyContent>(), backend.client.search("トリッカル", filter))
            val expected = filter.kinds.map { "search" + when (it) {
                ContentKind.SHOW -> "Podcasts"
                else -> it.name.lowercase().replaceFirstChar(Char::uppercaseChar) + "s"
            } }.toSet()
            assertEquals(expected, backend.requests.map { it.body.getString("operationName") }.toSet())
            assertEquals(filter.kinds.size, backend.requests.size)
        }
    }

    @Test
    fun artistOverviewAndPagedTrackUrisAreDecoratedAndMatched() = runBlocking {
        val backend = Backend { request ->
            when (request.body.getString("operationName")) {
                "queryArtistOverview" -> Reply(data("artistUnion", entity("artist", ARTIST_ID)
                    .put("__typename", "Artist").put("saved", false).put("stats", JSONObject())
                    .put("relatedContent", JSONObject().put("appearsOn", JSONObject().put("items", JSONArray()))
                        .put("featuringV2", JSONObject().put("items", JSONArray()))
                        .put("discoveredOnV2", JSONObject().put("items", JSONArray()))
                        .put("relatedArtists", JSONObject().put("items", JSONArray())))
                    .put("profile", JSONObject().put("name", "Artist"))))
                "queryArtistDiscographyAll" -> Reply(data("artistUnion", JSONObject().put("__typename", "Artist")
                    .put("discography", JSONObject().put("all", JSONObject().put("items", JSONArray()).put("totalCount", 0)))))
                "getArtistNameAndTracks" -> {
                    val offset = request.body.getJSONObject("variables").getInt("offset")
                    assertTrue(offset == 0 || offset == 1)
                    Reply(data("artistUnion", JSONObject().put("__typename", "Artist").put("profile", JSONObject().put("name", "Artist"))
                        .put("discography", JSONObject().put("topTracks", page(JSONArray().put(JSONObject().put("track",
                            JSONObject().put("uri", "spotify:track:${if (offset == 0) TRACK_ID else SECOND_TRACK_ID}"))), if (offset == 0) 1 else null)))))
                }
                "decorateContextTracks" -> Reply(data("tracks", JSONArray().put(entity("track", SECOND_TRACK_ID)).put(entity("track", TRACK_ID))))
                else -> error("Unexpected operation")
            }
        }
        val detail = backend.client.detail(content(ContentKind.ARTIST, ARTIST_ID))
        assertEquals("Artist", detail.content.title)
        assertEquals(listOf(TRACK_ID, SECOND_TRACK_ID), detail.tracks.map { it.id })
    }

    @Test
    fun radioUsesTheServicePlaylistAndRetriesUnauthorizedOnlyOnce() = runBlocking {
        val backend = Backend { request ->
            assertEquals("GET", request.requestMethod)
            assertEquals("/inspiredby-mix/v2/seed_to_playlist/spotify:track:$TRACK_ID", request.url.path)
            assertEquals("response-format=json", request.url.query)
            if (request.getRequestProperty("Authorization") == "Bearer original") Reply("{}", 401)
            else Reply(JSONObject().put("mediaItems", JSONArray().put(JSONObject().put("uri", "spotify:playlist:$PLAYLIST_ID"))).toString())
        }
        val radio = backend.client.radio(content(ContentKind.TRACK, TRACK_ID))
        assertEquals("spotify:playlist:$PLAYLIST_ID", radio.uri)
        assertEquals(ContentKind.PLAYLIST, radio.kind)
        assertEquals(1, backend.refreshes)
        assertEquals(2, backend.requests.size)
    }

    @Test
    fun wrongRadioKindsAndUnrelatedResponseUrisFail() = runBlocking {
        for (uri in listOf("spotify:track:$TRACK_ID", "https://other.example/playlist", "spotify:playlist:invalid")) {
            val backend = Backend { Reply(JSONObject().put("mediaItems", JSONArray().put(JSONObject().put("uri", uri))).toString()) }
            assertTrue(runCatching { backend.client.radio(content(ContentKind.TRACK, TRACK_ID)) }.isFailure)
        }
        val backend = Backend { error("Invalid input must never be sent") }
        assertTrue(runCatching { backend.client.radio(content(ContentKind.ALBUM, TRACK_ID)) }.isFailure)
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun showPagesKeepEpisodesAndTheirDetailsOpen() = runBlocking {
        val backend = Backend { request ->
            when (request.body.getString("operationName")) {
                "queryShowMetadataV2" -> Reply(data("podcastUnionV2", entity("show", SHOW_ID)))
                "queryPodcastEpisodes" -> {
                    val offset = request.body.getJSONObject("variables").getInt("offset")
                    val episode = JSONObject().put("entity", JSONObject().put("data", entity("episode", if (offset == 0) TRACK_ID else SECOND_TRACK_ID)))
                    Reply(data("podcastUnionV2", JSONObject().put("__typename", "Podcast").put("episodesV2",
                        page(JSONArray().put(episode), if (offset == 0) 1 else null).put("__typename", "ContextEpisodePage"))))
                }
                "getEpisodeOrChapter" -> Reply(data("episodeUnionV2", entity("episode", TRACK_ID)))
                else -> error("Unexpected operation")
            }
        }
        val detail = backend.client.detail(content(ContentKind.SHOW, SHOW_ID))
        assertEquals(listOf(TRACK_ID, SECOND_TRACK_ID), detail.relatedContent.map { it.id })
        assertEquals(detail.relatedContent.first().uri, backend.client.detail(detail.relatedContent.first()).content.uri)
    }

    @Test
    fun genreFollowsSectionPaginationAndDoesNotTurnTransportFailuresIntoEmptyContent() = runBlocking {
        val backend = Backend { request ->
            val item = JSONObject().put("content", JSONObject().put("__typename", "PlaylistResponseWrapper")
                .put("data", entity("playlist", PLAYLIST_ID).put("__typename", "Playlist")))
            when (request.body.getString("operationName")) {
                "browsePage" -> {
                    assertEquals("spotify:genre:jazz", request.body.getJSONObject("variables").getString("uri"))
                    Reply(data("browse", JSONObject().put("__typename", "BrowseSectionContainer")
                        .put("sections", page(JSONArray().put(JSONObject().put("uri", "spotify:section:one")
                            .put("sectionItems", page(JSONArray().put(item), 1))), null))))
                }
                "browseSection" -> Reply("{}", 503)
                else -> error("Unexpected operation")
            }
        }
        val error = runCatching { backend.client.detail(content(ContentKind.GENRE, "jazz")) }.exceptionOrNull()
        assertTrue(error is SpotifyApiException)
        assertEquals(503, (error as SpotifyApiException).status)
    }

    @Test
    fun genreLoadsEverySectionPageAndUsesZeroAsTheSectionEndMarker() = runBlocking {
        val backend = Backend { request ->
            val first = request.body.getString("operationName") == "browsePage"
            val item = JSONObject().put("content", JSONObject().put("__typename", "PlaylistResponseWrapper")
                .put("data", entity("playlist", if (first) PLAYLIST_ID else SECOND_TRACK_ID).put("__typename", "Playlist")))
            val section = JSONObject().put("__typename", "BrowseSection").put("uri", "spotify:section:one")
                .put("sectionItems", page(JSONArray().put(item), if (first) 1 else 0))
            if (first) {
                assertEquals("spotify:page:$TRACK_ID", request.body.getJSONObject("variables").getString("uri"))
                Reply(data("browse", JSONObject().put("__typename", "BrowseSectionContainer")
                    .put("header", JSONObject().put("title", JSONObject().put("transformedLabel", "Genre title")))
                    .put("sections", page(JSONArray().put(section), null))))
            } else {
                assertEquals(1, request.body.getJSONObject("variables").getJSONObject("pagination").getInt("offset"))
                Reply(data("browseSection", section))
            }
        }
        val detail = backend.client.detail(content(ContentKind.GENRE, TRACK_ID))
        assertEquals("Genre title", detail.content.title)
        assertEquals(listOf(PLAYLIST_ID, SECOND_TRACK_ID), detail.relatedContent.map { it.id })
        assertEquals(2, backend.requests.size)
    }

    @Test
    fun genrePagePreviewWithoutPagingInfoLoadsTheFullSectionAndKeepsAllItems() = runBlocking {
        val backend = Backend { request ->
            fun item(id: String) = JSONObject().put("content", JSONObject().put("__typename", "PlaylistResponseWrapper")
                .put("data", entity("playlist", id).put("__typename", "Playlist")))
            if (request.body.getString("operationName") == "browsePage") {
                val preview = JSONObject().put("items", JSONArray().put(item(PLAYLIST_ID))).put("totalCount", 2)
                val section = JSONObject().put("uri", "spotify:section:one").put("sectionItems", preview)
                Reply(data("browse", JSONObject().put("__typename", "BrowseSectionContainer")
                    .put("sections", page(JSONArray().put(section), null))))
            } else {
                assertEquals("browseSection", request.body.getString("operationName"))
                assertEquals(0, request.body.getJSONObject("variables").getJSONObject("pagination").getInt("offset"))
                Reply(data("browseSection", JSONObject().put("__typename", "BrowseSection").put("sectionItems",
                    page(JSONArray().put(item(PLAYLIST_ID)).put(item(SECOND_TRACK_ID)), null))))
            }
        }
        val detail = backend.client.detail(content(ContentKind.GENRE, "jazz"))
        assertEquals(listOf(PLAYLIST_ID, SECOND_TRACK_ID), detail.relatedContent.map { it.id })
        assertEquals(2, backend.requests.size)
    }

    private data class Reply(val json: String, val status: Int = 200)
    private class Backend(reply: (Connection) -> Reply) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Connection>())
        var refreshes = 0
        private val tokens = object : SessionTokens {
            override suspend fun username() = "test-account"
            override suspend fun accessToken(forceRefresh: Boolean): String {
                if (forceRefresh) refreshes++
                return if (forceRefresh) "renewed" else "original"
            }
            override suspend fun clientToken(forceRefresh: Boolean) = "unused"
            override suspend fun usesBrowserAuthorization() = true
        }
        val client = CatalogApiClient(tokens) { Connection(it, reply).also(requests::add) }
    }
    private class Connection(uri: URI, reply: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        val body get() = JSONObject(output.toString(Charsets.UTF_8))
        private val response by lazy { reply(this) }
        override fun getOutputStream() = output
        override fun getResponseCode() = response.status
        override fun getInputStream() = ByteArrayInputStream(response.json.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(response.json.toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    private companion object {
        const val TRACK_ID = "1111111111111111111111"
        const val SECOND_TRACK_ID = "2222222222222222222222"
        const val ARTIST_ID = "3333333333333333333333"
        const val PLAYLIST_ID = "4444444444444444444444"
        const val SHOW_ID = "5555555555555555555555"
        fun entity(kind: String, id: String) = JSONObject().put("uri", "spotify:$kind:$id").put("name", "Title")
        fun content(kind: ContentKind, id: String) = SpotifyContent(id, "spotify:${kind.name.lowercase()}:$id", "Title", "", null, kind)
        fun page(items: JSONArray, next: Int?) = JSONObject().put("items", items)
            .put("pagingInfo", JSONObject().put("nextOffset", next ?: JSONObject.NULL))
        fun data(key: String, value: Any) = JSONObject().put("data", JSONObject().put(key, value)).toString()
        val searchKeys = mapOf("searchAlbums" to "albumsV2", "searchTracks" to "tracksV2", "searchPlaylists" to "playlists",
            "searchArtists" to "artists", "searchPodcasts" to "podcasts", "searchEpisodes" to "episodes", "searchGenres" to "genres")
    }
}
