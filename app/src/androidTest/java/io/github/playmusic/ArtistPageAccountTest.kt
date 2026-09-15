package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.CatalogApiClient
import io.github.playmusic.data.model.ArtistReleaseType
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only integration check. It never changes the account's artist follows. */
class ArtistPageAccountTest {
    @Test
    fun artistDetailsContainServiceSectionsAndTheirTargetsOpen(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveArtist") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        check(app.sessionStore.loadSession() != null) { "Sign in on the target device first" }
        val catalog = CatalogApiClient(app.sessionManager)
        var suppliedBiographies = 0
        // Public artist pages identify these fixtures; a name match can select a different namesake artist.
        for ((name, id) in listOf("Queen" to "1dfeR4HaWDbWqFHLkxsg1d", "Nirvana" to "6olE6TJLqED3rqDCT0FyPh")) {
            val artist = catalog.search(name, SearchFilter.ARTISTS).first { it.id == id }
            val detail = app.repository.detail(artist, forceRefresh = true)
            val page = checkNotNull(detail.artistPage)
            assertEquals(artist.uri, detail.content.uri)
            assertTrue(detail.tracks.isNotEmpty())
            assertTrue(checkNotNull(page.monthlyListeners) > 0)
            val rawProfile = catalog.artistOverview(artist.uri).getJSONObject("profile")
            val rawBiography = rawProfile.opt("biography")
            check(rawBiography == null || rawBiography === JSONObject.NULL || rawBiography is JSONObject) {
                "Service biography wrapper has an unexpected type"
            }
            val rawText = (rawBiography as? JSONObject)?.opt("text")
            check(rawText == null || rawText === JSONObject.NULL || rawText is String) {
                "Service biography text has an unexpected type"
            }
            val suppliedText = (rawText as? String)?.takeIf { it.isNotBlank() }
            val rawSource = (rawBiography as? JSONObject)?.opt("type")
            check(rawSource == null || rawSource === JSONObject.NULL || rawSource is String) {
                "Service biography source has an unexpected type"
            }
            val suppliedSource = (rawSource as? String)?.takeIf { it.isNotBlank() }
            // Boolean assertion prevents the service biography from being included in failure output.
            assertTrue("Artist model must retain exactly the supplied biography or its unavailable state", page.biography == suppliedText)
            assertTrue("Artist model must retain the supplied biography source", page.biographySource == suppliedSource)
            if (suppliedText != null) suppliedBiographies++
            assertTrue(page.discography.any { it.type == ArtistReleaseType.ALBUM })
            assertTrue(page.discography.any { it.type == ArtistReleaseType.SINGLE || it.type == ArtistReleaseType.EP })
            assertTrue(page.suggestedArtists.isNotEmpty())
            assertEquals(detail.tracks.map { it.uri }, page.songRadioSeeds.map { it.uri })
            val album = page.discography.first().content
            val albumDetail = app.repository.detail(album)
            assertEquals(album.uri, albumDetail.content.uri)
            assertTrue(albumDetail.tracks.isNotEmpty())
            val suggested = app.repository.detail(page.suggestedArtists.first())
            assertNotNull(suggested.artistPage)
            val radio = app.repository.detail(catalog.radio(page.songRadioSeeds.first()))
            assertEquals(ContentKind.PLAYLIST, radio.content.kind)
            assertTrue(radio.tracks.isNotEmpty())
            for (item in (page.appearsOn.take(1) + page.featuringPlaylists.take(1) + page.discoveredOnPlaylists.take(1))) {
                assertEquals(item.uri, app.repository.detail(item).content.uri)
            }
            Log.i("PlayArtistCheck", "artist sections verified releases=${page.discography.size} appearsOn=${page.appearsOn.size} " +
                "featuring=${page.featuringPlaylists.size} discoveredOn=${page.discoveredOnPlaylists.size} related=${page.suggestedArtists.size}")
        }
        assertTrue("At least one fixture must provide a nonempty biography retained by the display model", suppliedBiographies > 0)
    }
}
