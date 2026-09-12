package io.github.playmusic

import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.CatalogJson
import io.github.playmusic.data.model.ContentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogJsonTest {
    @Test fun readsAlbumArtworkAndEveryTrackInTheObservedAlbumResponse() {
        val data = fixture("album-detail.json").getJSONObject("albumUnion")
        val album = CatalogJson.content(data, ContentKind.ALBUM)
        assertEquals("Nevermind (Remastered)", album.title)
        assertEquals("ニルヴァーナ", album.subtitle)
        assertTrue(album.imageUrl?.endsWith("ab67616d0000b273fbc71c99f9c1296c56dd51b6") == true)
        val tracks = CatalogJson.albumTracks(data, album)
        assertEquals(13, tracks.size)
        assertEquals("Smells Like Teen Spirit", tracks.first().title)
        assertEquals(301_920L, tracks.first().durationMs)
        assertEquals(album.uri, tracks.first().albumUri)
        assertEquals(album.imageUrl, tracks.first().imageUrl)
        assertEquals("1991-09-26", CatalogJson.releaseDate(data))
    }

    @Test fun parsesEachObservedSearchWrapper() {
        for ((file, kind) in listOf("search-albums.json" to ContentKind.ALBUM,
            "search-tracks.json" to ContentKind.TRACK, "search-playlists.json" to ContentKind.PLAYLIST)) {
            val results = CatalogJson.search(fixture(file), kind)
            assertEquals(2, results.size)
            assertTrue(results.all { it.kind == kind && it.title.isNotBlank() })
            assertTrue("Artwork is required in the observed $file results", results.all { it.imageUrl != null })
        }
    }

    @Test fun batchAndDetailPreserveDurationAndArtistNames() {
        val batch = CatalogJson.tracks(fixture("tracks-batch.json"))
        val detail = CatalogJson.content(fixture("track-detail.json").getJSONObject("trackUnion"), ContentKind.TRACK)
        assertEquals(detail.title, batch.first().title)
        assertEquals(detail.subtitle, batch.first().subtitle)
        assertEquals(detail.durationMs, batch.first().durationMs)
        assertNotNull(detail.albumUri)
        assertEquals(true, detail.isPlayable)
    }

    @Test fun invalidMetadataDoesNotTurnIntoAnEmptySuccessfulResult() {
        assertThrows(Exception::class.java) { CatalogJson.search(JSONObject(), ContentKind.TRACK) }
        assertThrows(Exception::class.java) {
            CatalogJson.content(JSONObject("""{"uri":"spotify:track:synthetic","name":""}"""), ContentKind.TRACK)
        }
    }

    private fun fixture(name: String): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("catalog/$name").bufferedReader().use { JSONObject(it.readText()).getJSONObject("data") }
}
