package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogJsonTest {
    @Test
    fun genreSavedSongsCardKeepsItsLibraryDestinationAlongsideActualGenres() {
        val card = JSONObject().put("__typename", "Genre").put("uri", "spotify:user:@:collection")
            .put("name", "お気に入りの曲").put("image", JSONObject.NULL)
        val genre = JSONObject().put("__typename", "Genre").put("uri", "spotify:genre:jazz").put("name", "Jazz")
        val results = CatalogJson.search(search(ContentKind.GENRE, JSONArray().put(genre)
            .put(JSONObject().put("__typename", "GenreResponseWrapper").put("data", card)).put(genre)), ContentKind.GENRE)
        assertEquals(listOf("spotify:genre:jazz", SpotifyRepository.LIKED_SONGS_URI, "spotify:genre:jazz"), results.map { it.uri })
        assertEquals(SpotifyRepository.likedSongsContent("お気に入りの曲"), results[1])
        for (uri in listOf("spotify:user:other:collection", "spotify:unknown:one", "https://example.com")) {
            assertTrue(runCatching { CatalogJson.content(JSONObject(card.toString()).put("uri", uri), ContentKind.GENRE) }.isFailure)
        }
        assertTrue(runCatching { CatalogJson.content(card, ContentKind.PLAYLIST) }.isFailure)
    }

    @Test
    fun playlistOwnerUriIsDecodedOnceAndExplicitUsernameIsAlreadyDecoded() {
        val owner = JSONObject().put("uri", "spotify:user:%E9%9F%B3%E6%A5%BD%2B%2520")
        val playlist = JSONObject().put("uri", "spotify:playlist:one").put("name", "Playlist")
            .put("ownerV2", JSONObject().put("data", owner))
        assertEquals("音楽+%20", CatalogJson.content(playlist, ContentKind.PLAYLIST).ownerUsername)
        owner.put("username", "raw%20+username")
        assertEquals("raw%20+username", CatalogJson.content(playlist, ContentKind.PLAYLIST).ownerUsername)
    }

    @Test
    fun artistPodcastEpisodeAndGenreUseTheirObservedFields() {
        val avatar = JSONObject().put("sources", JSONArray().put(JSONObject().put("url", "https://images.example/avatar").put("width", 300)))
        val artist = JSONObject().put("uri", "spotify:artist:one").put("profile", JSONObject().put("name", "Artist"))
            .put("visuals", JSONObject().put("avatarImage", avatar))
        val parsedArtist = CatalogJson.search(search(ContentKind.ARTIST, JSONArray().put(JSONObject().put("data", artist))), ContentKind.ARTIST).single()
        assertEquals("Artist", parsedArtist.title)
        assertEquals("https://images.example/avatar", parsedArtist.imageUrl)
        val podcast = JSONObject().put("uri", "spotify:show:one").put("name", "Show")
            .put("publisher", JSONObject().put("name", "Publisher"))
        val book = JSONObject().put("uri", "spotify:show:book").put("name", "Book").put("__typename", "Audiobook")
        assertEquals("Publisher", CatalogJson.search(search(ContentKind.SHOW, JSONArray().put(podcast).put(book)), ContentKind.SHOW).single().subtitle)
        val genre = JSONObject().put("uri", "jazz").put("name", "Jazz").put("image", avatar)
        val parsedGenre = CatalogJson.search(search(ContentKind.GENRE, JSONArray().put(genre)), ContentKind.GENRE).single()
        assertEquals("spotify:genre:jazz", parsedGenre.uri)
        assertEquals("https://images.example/avatar", parsedGenre.imageUrl)
        val episode = JSONObject().put("uri", "spotify:episode:one").put("name", "Episode")
            .put("duration", JSONObject().put("totalMilliseconds", 123000))
        assertEquals(123000L, CatalogJson.search(search(ContentKind.EPISODE, JSONArray().put(episode)), ContentKind.EPISODE).single().durationMs)
    }

    @Test
    fun trackArtistLinksPreserveAllContributorsAndDoNotInventIdsFromNames() {
        val artists = JSONArray()
            .put(JSONObject().put("uri", "spotify:artist:first").put("profile", JSONObject().put("name", "First")))
            .put(JSONObject().put("data", JSONObject().put("uri", "spotify:artist:second").put("profile", JSONObject().put("name", "Second"))))
            .put(JSONObject().put("profile", JSONObject().put("name", "Unlinked")))
        val parsed = CatalogJson.content(track("one").put("artists", JSONObject().put("items", artists)), ContentKind.TRACK)
        assertEquals("First, Second, Unlinked", parsed.subtitle)
        assertEquals(listOf("spotify:artist:first", "spotify:artist:second"), parsed.artists.map { it.uri })
    }
    @Test
    fun unavailableSearchUnionsAndNullSlotsDoNotDiscardValidResults() {
        val available = track("available")
        val notPlayable = track("restricted-track").put("playability", JSONObject().put("playable", false))
        val items = JSONArray().put(JSONObject().put("item", JSONObject().put("data", available)))
            .put(JSONObject.NULL).put(JSONObject().put("item", JSONObject.NULL))
            .put(JSONObject().put("data", JSONObject().put("__typename", "NotFound")))
            .put(JSONObject().put("item", JSONObject().put("data", JSONObject().put("__typename", "RestrictedContent"))))
            .put(JSONObject().put("item", JSONObject.NULL).put("data", notPlayable))
        val results = CatalogJson.search(search(ContentKind.TRACK, items), ContentKind.TRACK)
        assertEquals(listOf("available", "restricted-track"), results.map { it.id })
        assertFalse(checkNotNull(results.last().isPlayable))
    }

    @Test
    fun unavailableAlbumsAndPlaylistsAreHandledLikeTracks() {
        for (kind in listOf(ContentKind.ALBUM, ContentKind.PLAYLIST)) {
            val item = JSONObject().put("uri", "spotify:${kind.name.lowercase()}:valid").put("name", "Title")
            val items = JSONArray().put(JSONObject().put("data", JSONObject.NULL)).put(item)
            assertEquals(listOf("valid"), CatalogJson.search(search(kind, items), kind).map { it.id })
        }
    }

    @Test
    fun unknownErrorsMalformedItemsAndWrongKindsStillFail() {
        val malformed = listOf(
            JSONObject().put("data", JSONObject().put("__typename", "GenericError")),
            JSONObject().put("data", JSONObject().put("__typename", "UnknownUnion")),
            JSONObject().put("item", "invalid-wrapper"),
            JSONObject().put("uri", "spotify:album:wrong-kind").put("name", "Album"),
            "invalid-item",
        )
        for (item in malformed) {
            val error = runCatching {
                CatalogJson.search(search(ContentKind.TRACK, JSONArray().put(track("valid")).put(item)), ContentKind.TRACK)
            }.exceptionOrNull()
            assertTrue("Malformed item must remain distinguishable from an unavailable union", error != null)
        }
        assertTrue(runCatching {
            CatalogJson.content(JSONObject().put("data", JSONObject().put("__typename", "NotFound")), ContentKind.TRACK)
        }.isFailure)
    }

    @Test
    fun playlistOwnerDescriptionAndEmptyNameUseActualMetadata() {
        val playlist = JSONObject().put("uri", "spotify:playlist:one").put("name", "")
            .put("description", "Description")
            .put("ownerV2", JSONObject().put("data", JSONObject().put("displayName", "Display name").put("username", "owner-id")))
            .put("tracksV2", JSONObject().put("totalCount", 0))
        val parsed = CatalogJson.content(playlist, ContentKind.PLAYLIST)
        assertEquals("", parsed.title)
        assertEquals("Display name", parsed.ownerName)
        assertEquals("Display name", parsed.subtitle)
        assertEquals("Description", parsed.description)
        assertEquals(0, parsed.trackCount)
        playlist.getJSONObject("ownerV2").getJSONObject("data").remove("displayName")
        val unresolved = CatalogJson.content(playlist, ContentKind.PLAYLIST)
        assertEquals("owner-id", unresolved.ownerUsername)
        assertEquals(null, unresolved.ownerName)
    }

    @Test
    fun albumCountsReleaseDateAndArtistNamesArePreservedWithoutInventingMissingValues() {
        val album = JSONObject().put("uri", "spotify:album:one").put("name", "Album")
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject().put("profile", JSONObject().put("name", "Artist")))))
            .put("date", JSONObject().put("isoString", "2026-09-12T00:00:00Z"))
            .put("tracksV2", JSONObject().put("totalCount", 12))
        val parsed = CatalogJson.content(album, ContentKind.ALBUM)
        assertEquals(12, parsed.trackCount)
        assertEquals("2026-09-12", parsed.releaseDate)
        assertEquals("Artist", parsed.subtitle)
        val child = CatalogJson.content(track("child"), ContentKind.TRACK, parsed)
        assertEquals(parsed.uri, child.albumUri)
        assertEquals("Album", child.albumTitle)
        assertEquals("2026-09-12", child.releaseDate)
        assertNull(child.trackCount)
        assertNull(child.description)
        assertNull(child.ownerName)
        album.remove("tracksV2")
        album.remove("date")
        assertNull(CatalogJson.content(album, ContentKind.ALBUM).trackCount)
        assertNull(CatalogJson.content(album, ContentKind.ALBUM).releaseDate)
    }

    @Test
    fun anInvalidTrackCountIsNotTurnedIntoZeroOrAnOverflowedNumber() {
        for (count in listOf(-1, 0.5, Int.MAX_VALUE.toLong() + 1, "12")) {
            val item = JSONObject().put("uri", "spotify:album:one").put("name", "Album")
                .put("tracksV2", JSONObject().put("totalCount", count))
            assertTrue(runCatching { CatalogJson.content(item, ContentKind.ALBUM) }.isFailure)
        }
    }

    private fun track(id: String) = JSONObject().put("uri", "spotify:track:$id").put("name", "Track $id")

    private fun search(kind: ContentKind, items: JSONArray): JSONObject {
        val key = when (kind) {
            ContentKind.ALBUM -> "albumsV2"
            ContentKind.PLAYLIST -> "playlists"
            ContentKind.TRACK -> "tracksV2"
            ContentKind.ARTIST -> "artists"
            ContentKind.SHOW -> "podcasts"
            ContentKind.EPISODE -> "episodes"
            ContentKind.GENRE -> "genres"
        }
        return JSONObject().put("searchV2", JSONObject().put(key, JSONObject().put("items", items)))
    }
}
