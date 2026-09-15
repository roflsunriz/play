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

    @Test fun albumTracksExposeDiscOrderAndPlaysWithoutRequiringThem() {
        val data = fixture("album-detail.json").getJSONObject("albumUnion")
        val tracks = CatalogJson.albumTracks(data, CatalogJson.content(data, ContentKind.ALBUM))
        assertEquals((1..13).toList(), tracks.map { it.trackNumber })
        assertTrue(tracks.all { it.discNumber == 1 })
        assertTrue(tracks.all { it.playcount == null })
    }

    @Test fun playsAndPositionsAcceptOnlyValidValues() {
        fun track(extra: String) = CatalogJson.content(JSONObject(
            """{"uri":"spotify:track:synthetic","name":"Synthetic",$extra}"""), ContentKind.TRACK)
        val full = track(""""playcount":"123456789","trackNumber":2,"discNumber":1""")
        assertEquals(123456789L, full.playcount)
        assertEquals(2, full.trackNumber)
        assertEquals(1, full.discNumber)
        val missing = track(""""playcount":null""")
        assertEquals(null, missing.playcount)
        assertEquals(null, missing.trackNumber)
        assertEquals(null, missing.discNumber)
        for (extra in listOf(""""playcount":"10 plays"""", """"playcount":""""", """"playcount":-1""",
            """"trackNumber":0""", """"trackNumber":"two"""", """"discNumber":-1""")) {
            val parsed = track(extra)
            assertEquals(null, parsed.playcount)
            assertEquals(null, parsed.trackNumber)
            assertEquals(null, parsed.discNumber)
        }
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
