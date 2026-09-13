package io.github.playmusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import io.github.playmusic.ui.LibrarySort
import io.github.playmusic.ui.presentLibrary
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test
    fun untitledPlaylistHasALocalizedTitleAndRemainsClickable() {
        val item = SpotifyContent("untitled", "spotify:playlist:untitled", "", "", null, ContentKind.PLAYLIST)
        var played = false
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.untitled_playlist)
        composeRule.setContent {
            TestTheme {
                HomeScreen(
                    state = PlayUiState(isLoggedIn = true, items = listOf(item)),
                    onSectionSelected = {}, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {},
                    onPlay = { played = true }, onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {},
                    onShuffle = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithTag("title-playlist-untitled", useUnmergedTree = true).assertTextEquals(title)
        captureScreen(composeRule.onRoot(), "untitled-playlist")
        composeRule.onNodeWithTag("content-playlist-untitled").performClick()
        composeRule.runOnIdle { assertTrue(played) }
    }

    @Test
    fun contentAndPlaybackControlsDispatchActions() {
        val track = SpotifyContent("track-id", "spotify:track:track-id", "Track", "Artist", null, ContentKind.TRACK)
        var played = false
        var toggled = false
        var next = false
        var previous = false
        var shuffled = false
        var repeated = false

        composeRule.setContent {
            TestTheme {
                HomeScreen(
                    state = PlayUiState(
                        isLoggedIn = true,
                        selectedSection = LibrarySection.TRACKS,
                        items = listOf(track),
                        playback = Playback(item = track, durationMs = 180_000),
                    ),
                    onSectionSelected = {},
                    onSearchChanged = {},
                    onSearch = {},
                    onRefresh = {},
                    onLogout = {},
                    onPlay = { played = true },
                    onPlayPause = { toggled = true },
                    onNext = { next = true },
                    onPrevious = { previous = true },
                    onSeek = {},
                    onShuffle = { shuffled = true },
                    onRepeat = { repeated = true },
                )
            }
        }

        captureScreen(composeRule.onRoot(), "playback")
        val title = composeRule.onNodeWithTag("title-track-track-id", useUnmergedTree = true)
        val fullTitle = title.getUnclippedBoundsInRoot()
        val visibleTitle = title.getBoundsInRoot()
        assertEquals(fullTitle.bottom - fullTitle.top, visibleTitle.bottom - visibleTitle.top)
        composeRule.onNodeWithTag("content-track-track-id").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("play-pause-button").performClick()
        composeRule.onNodeWithTag("next-button").performClick()
        composeRule.onNodeWithTag("previous-button").performClick()
        composeRule.onNodeWithTag("shuffle-button").performClick()
        composeRule.onNodeWithTag("repeat-button").performClick()

        composeRule.runOnIdle {
            assertTrue(played)
            assertTrue(toggled)
            assertTrue(next)
            assertTrue(previous)
            assertTrue(shuffled)
            assertTrue(repeated)
        }
    }

    @Test
    fun searchInputAndNavigationRemainOperable() {
        var state by mutableStateOf(
            PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.SEARCH),
        )
        var searched = false
        var refreshed = false
        var loggedOut = false
        var selected: LibrarySection? = null

        composeRule.setContent {
            TestTheme {
                HomeScreen(
                    state = state,
                    onSectionSelected = { selected = it },
                    onSearchChanged = { state = state.copy(searchQuery = it) },
                    onSearch = { searched = true },
                    onRefresh = { refreshed = true },
                    onLogout = { loggedOut = true },
                    onPlay = {},
                    onPlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    onSeek = {},
                    onShuffle = {},
                    onRepeat = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-input").assertIsDisplayed().performTextInput("music")
        captureScreen(composeRule.onRoot(), "search")
        composeRule.onNodeWithTag("search-button").performClick()
        composeRule.onNodeWithTag("section-playlists").performClick()
        composeRule.onNodeWithTag("refresh-button").performClick()
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("account-login-button").assertDoesNotExist()
        composeRule.onNodeWithTag("logout-button").performClick()

        composeRule.runOnIdle {
            assertTrue(searched)
            assertTrue(refreshed)
            assertTrue(loggedOut)
            assertEquals(LibrarySection.PLAYLISTS, selected)
        }
    }

    @Test
    fun metadataFilterSortAndClearRemainUsable() {
        val a = SpotifyContent("a", "spotify:playlist:a", "Alpha", "", null, ContentKind.PLAYLIST,
            ownerName = "Alice", description = "Quiet &amp; calm evening", trackCount = 12)
        val b = SpotifyContent("b", "spotify:playlist:b", "Beta", "", null, ContentKind.PLAYLIST,
            ownerName = "Bob", description = "Running", trackCount = 30)
        val source = listOf(b, a)
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, items = source))
        composeRule.setContent { TestTheme {
            HomeScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onLibraryQueryChanged = { query -> state = state.copy(libraryQuery = query,
                    items = presentLibrary(source, query, state.playlistSort)) },
                onLibrarySortChanged = { sort -> state = state.copy(playlistSort = sort,
                    items = presentLibrary(source, state.libraryQuery, sort)) })
        } }
        composeRule.onNodeWithTag("playlist-filter-input").performTextInput("Alice quiet")
        composeRule.onNodeWithTag("content-playlist-b").assertDoesNotExist()
        composeRule.onNodeWithTag("content-playlist-a").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet & calm evening", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clear-library-filter").performClick()
        composeRule.onNodeWithTag("library-sort-button").performClick()
        composeRule.onNodeWithTag("sort-title").performClick()
        composeRule.runOnIdle { assertEquals(listOf(a, b), state.items) }
        captureScreen(composeRule.onRoot(), "playlist-filter-sort")
    }

    @Test
    fun albumMetadataFilterUsesThePlaylistLayoutAndSorting() = verifyExtraLibraryFilter(LibrarySection.ALBUMS)

    @Test
    fun trackMetadataFilterUsesThePlaylistLayoutAndSorting() = verifyExtraLibraryFilter(LibrarySection.TRACKS)

    private fun verifyExtraLibraryFilter(section: LibrarySection) {
        val kind = checkNotNull(section.kind)
        val a = SpotifyContent("a", "spotify:${kind.name.lowercase()}:a", "Alpha", "Artist one", null, kind,
            albumTitle = "Parent album", releaseDate = "1991", durationMs = 185_000)
        val b = a.copy(id = "b", uri = "spotify:${kind.name.lowercase()}:b", title = "Beta", subtitle = "Artist two", releaseDate = "2002")
        val source = listOf(b, a)
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, selectedSection = section, items = source))
        var query = ""
        var sort = LibrarySort.LIBRARY_ORDER
        composeRule.setContent { TestTheme {
            HomeScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onLibraryQueryChanged = {
                    query = it
                    state = if (section == LibrarySection.ALBUMS) state.copy(albumQuery = query, items = presentLibrary(source, query, sort))
                        else state.copy(trackQuery = query, items = presentLibrary(source, query, sort))
                },
                onLibrarySortChanged = {
                    sort = it
                    state = if (section == LibrarySection.ALBUMS) state.copy(albumSort = sort, items = presentLibrary(source, query, sort))
                        else state.copy(trackSort = sort, items = presentLibrary(source, query, sort))
                })
        } }
        val input = composeRule.onNodeWithTag(if (section == LibrarySection.ALBUMS) "album-filter-input" else "track-filter-input")
        val sortButton = composeRule.onNodeWithTag("library-sort-button")
        val inputBounds = input.getBoundsInRoot()
        val sortBounds = sortButton.getBoundsInRoot()
        assertTrue(sortBounds.top >= inputBounds.top && sortBounds.bottom <= inputBounds.bottom)
        input.performTextInput(if (section == LibrarySection.ALBUMS) "one 1991" else "one parent")
        composeRule.onNodeWithTag("content-${kind.name.lowercase()}-a").assertIsDisplayed()
        composeRule.onNodeWithTag("content-${kind.name.lowercase()}-b").assertDoesNotExist()
        composeRule.onNodeWithTag("clear-library-filter").performClick()
        sortButton.performClick()
        composeRule.onNodeWithTag("sort-title").performClick()
        composeRule.runOnIdle { assertEquals(listOf(a, b), state.items) }
        captureScreen(composeRule.onRoot(), "${kind.name.lowercase()}-metadata-filter")
        val title = composeRule.onNodeWithTag("title-${kind.name.lowercase()}-a", useUnmergedTree = true)
        val fullTitle = title.getUnclippedBoundsInRoot()
        val visibleTitle = title.getBoundsInRoot()
        assertEquals(fullTitle.bottom - fullTitle.top, visibleTitle.bottom - visibleTitle.top)
    }

    @Test
    fun miniPlayerOpensArtworkControllerAndReturnsToTheLibrary() {
        val track = SpotifyContent("now", "spotify:track:now", "Now playing", "Artist", null, ContentKind.TRACK,
            durationMs = 180_000, albumTitle = "Album")
        val items = (0..30).map { track.copy(id = "row$it", uri = "spotify:track:row$it", title = "Track $it") }
        composeRule.setContent { TestTheme {
            HomeScreen(PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.TRACKS, items = items,
                playback = Playback(item = track, durationMs = 180_000)), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        composeRule.onNodeWithTag("content-list").performScrollToKey("spotify:track:row15")
        composeRule.onNodeWithTag("mini-player-info", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("expanded-player-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("expanded-player-back").performClick()
        composeRule.onNodeWithTag("content-track-row15").assertIsDisplayed()
    }

    @Test
    fun searchShowsSuggestedContentBeforeTypingAndMatchingCandidatesAfterTyping() {
        val suggested = SpotifyContent("suggested", "spotify:album:suggested", "Suggested album", "Artist", null, ContentKind.ALBUM)
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.SEARCH,
            suggestedItems = listOf(suggested)))
        composeRule.setContent { TestTheme {
            HomeScreen(state, {}, { state = state.copy(searchQuery = it, searchSuggestions = listOf(suggested)) },
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        composeRule.onNodeWithTag("suggested-heading").assertIsDisplayed()
        composeRule.onNodeWithTag("content-album-suggested").assertIsDisplayed()
        composeRule.onNodeWithTag("search-input").performTextInput("Suggested")
        composeRule.onNodeWithTag("matching-suggestions-heading").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "search-suggestions")
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        PlayTheme { Surface(Modifier.fillMaxSize()) { content() } }
    }
}
