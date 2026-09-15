package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.CatalogApiClient
import io.github.playmusic.data.api.SpotifyRepository
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** Read-only catalog checks; opt in explicitly with liveCatalog=true on a signed-in device. */
class CatalogNavigationAccountTest {
    @Test
    fun reportedQueriesAndEveryCategoryReturnValidResults(): Unit = runBlocking {
        for (query in listOf("jazz", "lo-fi", "トリッカル", "インターネット")) {
            for (filter in SearchFilter.entries) {
                val items = app.repository.search(query, filter)
                assertTrue("Category must match its results or the service's saved-songs navigation card",
                    items.all { it.kind in filter.kinds || (ContentKind.GENRE in filter.kinds && SpotifyRepository.isLikedSongs(it)) })
                assertTrue("Results must contain titles", items.all { it.kind == ContentKind.PLAYLIST || it.title.isNotBlank() })
                if (filter == SearchFilter.ALL) assertTrue("Reported search must return results", items.isNotEmpty())
                Log.i(TAG, "search category=$filter count=${items.size}")
            }
        }
    }

    @Test
    fun reportedSearchRegressions(): Unit = runBlocking {
        val failures = mutableListOf<String>()
        for ((query, filters) in listOf("トリッカル" to listOf(SearchFilter.ALL, SearchFilter.PLAYLISTS),
            "インターネット" to listOf(SearchFilter.ALL, SearchFilter.GENRES))) {
            for (filter in filters) {
                runCatching {
                    val items = app.repository.search(query, filter)
                    assertTrue(items.isNotEmpty())
                    assertTrue(items.all { it.kind in filter.kinds || (ContentKind.GENRE in filter.kinds && SpotifyRepository.isLikedSongs(it)) })
                    if (query == "インターネット" && filter == SearchFilter.GENRES) {
                        val savedSongs = items.single(SpotifyRepository::isLikedSongs)
                        val detail = app.repository.detail(savedSongs)
                        assertEquals(savedSongs.uri, detail.content.uri)
                        assertTrue(detail.tracks.all { it.kind == ContentKind.TRACK })
                    }
                    Log.i(TAG, "regression query=$query category=$filter count=${items.size}")
                }.onFailure { failures += "$query/$filter: ${it.message}" }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun trackDetailsArtistAndRadioOpenUsingSavedLogin(): Unit = runBlocking {
        val track = catalog.search("jazz", SearchFilter.TRACKS).first { it.isPlayable != false }
        val detail = catalog.detail(track)
        assertEquals(track.uri, detail.content.uri)
        assertTrue("Detailed track must have artist links", detail.content.artists.isNotEmpty())
        val artist = detail.content.artists.first()
        val artistContent = io.github.playmusic.data.model.SpotifyContent(artist.uri.substringAfterLast(':'), artist.uri,
            artist.name, "", null, ContentKind.ARTIST)
        val artistDetail = catalog.detail(artistContent)
        assertEquals(artist.uri, artistDetail.content.uri)
        assertTrue("Artist must have popular tracks", artistDetail.tracks.isNotEmpty())
        assertTrue(artistDetail.tracks.all { it.kind == ContentKind.TRACK && it.title.isNotBlank() })
        val radio = catalog.radio(detail.content)
        val radioDetail = app.repository.detail(radio, forceRefresh = true)
        assertEquals(ContentKind.PLAYLIST, radioDetail.content.kind)
        assertTrue("Resolved radio must include playable content", radioDetail.tracks.isNotEmpty())
        Log.i(TAG, "track detail, artist popular tracks, and radio playlist verified")
    }

    @Test
    fun podcastEpisodeAndGenreResultsOpenDetails(): Unit = runBlocking {
        val podcasts = catalog.search("jazz", SearchFilter.PODCASTS)
        val show = podcasts.first { it.kind == ContentKind.SHOW }
        val showDetail = catalog.detail(show)
        assertEquals(show.uri, showDetail.content.uri)
        assertTrue("Show must expose its episodes", showDetail.relatedContent.isNotEmpty())
        val episode = showDetail.relatedContent.first()
        val episodeDetail = catalog.detail(episode)
        assertEquals(episode.uri, episodeDetail.content.uri)
        val genre = catalog.search("jazz", SearchFilter.GENRES).first()
        val genreDetail = catalog.detail(genre)
        assertTrue("Genre must expose its music content", genreDetail.relatedContent.isNotEmpty())
        Log.i(TAG, "show episodes, episode details, and genre contents verified")
    }

    companion object {
        private const val TAG = "PlayCatalogCheck"
        private lateinit var app: AppContainer
        private lateinit var catalog: CatalogApiClient

        @BeforeClass
        @JvmStatic
        fun requireSignedInAccount() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            app = (context.applicationContext as PlayApplication).container
            check(app.sessionStore.loadSession() != null) { "Sign in on the target device first" }
            catalog = CatalogApiClient(app.sessionManager)
        }
    }
}
