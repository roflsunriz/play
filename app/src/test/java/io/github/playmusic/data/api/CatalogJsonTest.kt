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
            else -> "tracksV2"
        }
        return JSONObject().put("searchV2", JSONObject().put(key, JSONObject().put("items", items)))
    }
}
