package io.github.playmusic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.ui.ArtistPicker
import io.github.playmusic.ui.ContentActionsDialog
import io.github.playmusic.ui.ContentActionsState
import io.github.playmusic.ui.ContentAddButton
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.PlaylistChoice
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentActionsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val track = item(ContentKind.TRACK, "track-one", "Track")
    private val album = item(ContentKind.ALBUM, "album-one", "Album")
    private val firstPlaylist = item(ContentKind.PLAYLIST, "first", "Quiet evening")
    private val secondPlaylist = item(ContentKind.PLAYLIST, "second", "Driving music")

    @Test fun favoriteButtonChangesItsAccessibleActionAndPassesTheExactContent() {
        var saved by mutableStateOf(false)
        var selected: SpotifyContent? = null
        render { ContentAddButton(track, saved) { selected = it } }
        val button = composeRule.onNodeWithTag("add-track-track-one")
        button.assertContentDescriptionEquals(text(R.string.add_favorite)).performClick()
        composeRule.runOnIdle { assertEquals(track, selected); saved = true; selected = null }
        button.assertContentDescriptionEquals(text(R.string.remove_favorite)).performClick()
        composeRule.runOnIdle { assertEquals(track, selected) }
    }

    @Test fun favoriteMenuShowsBothIconStatesAndUsesTheCorrectActionText() {
        var state by mutableStateOf(ContentActionsState(track, saved = false, loading = false))
        val savedStates = mutableListOf<Boolean>()
        render { Actions(state, onFavorite = {
            savedStates += checkNotNull(state.saved)
            state = state.copy(saved = state.saved != true)
        }) }
        composeRule.onNodeWithTag("favorite-toggle").assertTextEquals(text(R.string.add_favorite)).performClick()
        composeRule.onNodeWithTag("favorite-icon-saved", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("favorite-icon-unsaved", useUnmergedTree = true).assertDoesNotExist()
        captureDialog("content-actions-saved")
        composeRule.onNodeWithTag("favorite-toggle").assertTextEquals(text(R.string.remove_favorite)).performClick()
        composeRule.onNodeWithTag("favorite-icon-unsaved", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("favorite-icon-saved", useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(false, true), savedStates) }
        captureDialog("content-actions-unsaved")
    }

    @Test fun trackMenusDispatchRadioArtistAndPlaylistActionsAndAlbumOmitsRadio() {
        var state by mutableStateOf(ContentActionsState(track, saved = true, loading = false))
        val calls = mutableListOf<String>()
        render { Actions(state, onPlaylists = { calls += "playlists" }, onRadio = { calls += "radio" },
            onArtists = { calls += "artists" }) }
        composeRule.onNodeWithTag("choose-playlists").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("open-song-radio").assertTextEquals(text(R.string.go_to_song_radio)).performClick()
        composeRule.onNodeWithTag("open-artists").performScrollTo().assertTextEquals(text(R.string.go_to_artist))
        assertFullyVisible("open-artists")
        captureDialog("content-actions-artist-scrolled")
        composeRule.onNodeWithTag("open-artists").performClick()
        composeRule.runOnIdle { assertEquals(listOf("playlists", "radio", "artists"), calls); state = state.copy(content = album) }
        composeRule.onNodeWithTag("open-song-radio").assertDoesNotExist()
        composeRule.onNodeWithTag("open-artists").assertIsEnabled()
        composeRule.onNodeWithTag("choose-playlists").assertIsEnabled()
    }

    @Test fun loadingAndBusyPreventConflictingActionsAndBusyPreventsDismissal() {
        var state by mutableStateOf(ContentActionsState(track))
        var dismissed = 0
        render { Actions(state, onDismiss = { dismissed++ }) }
        composeRule.onNodeWithTag("content-actions-loading").assertIsDisplayed()
        for (tag in listOf("favorite-toggle", "choose-playlists", "open-song-radio", "open-artists"))
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(saved = true, loading = false, busy = true) }
        for (tag in listOf("favorite-toggle", "choose-playlists", "open-song-radio", "open-artists", "content-actions-close"))
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, dismissed); state = state.copy(busy = false) }
        composeRule.onNodeWithTag("content-actions-loading").assertDoesNotExist()
        composeRule.onNodeWithTag("content-actions-close").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun unknownFavoriteFailureCanRetryAndCloseWithoutGuessingSavedState() {
        var state by mutableStateOf(ContentActionsState(track, loading = false, failure = true))
        var retries = 0
        var visible by mutableStateOf(true)
        render { if (visible) Actions(state, onRetry = { retries++; state = state.copy(loading = true, failure = false) },
            onDismiss = { visible = false }) }
        composeRule.onNodeWithTag("content-actions-error").assertTextEquals(text(R.string.request_failed))
        composeRule.onNodeWithTag("favorite-toggle").assertIsNotEnabled()
        captureDialog("content-actions-error")
        composeRule.onNodeWithTag("content-actions-retry").performClick()
        composeRule.onNodeWithTag("content-actions-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("content-actions-error").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, retries); state = state.copy(saved = true, loading = false) }
        composeRule.onNodeWithTag("favorite-toggle").assertIsEnabled()
        composeRule.onNodeWithTag("content-actions-close").performClick()
        composeRule.onNodeWithTag("content-actions-dialog").assertDoesNotExist()
    }

    @Test fun playlistSearchKeepsMembershipChecksAndCheckedChoiceDispatchesRemoval() {
        var state by mutableStateOf(ContentActionsState(track, saved = true, loading = false, choosingPlaylist = true,
            playlists = listOf(PlaylistChoice(firstPlaylist, true), PlaylistChoice(secondPlaylist, false))))
        val calls = mutableListOf<PlaylistChoice>()
        render { Actions(state, onPlaylist = { selected ->
            calls += selected
            state = state.copy(playlists = state.playlists.map { if (it.playlist.uri == selected.playlist.uri)
                it.copy(containsAll = !it.containsAll) else it })
        }) }
        composeRule.onNodeWithTag("playlist-member-first", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-member-second", useUnmergedTree = true).assertDoesNotExist()
        captureDialog("playlist-picker-membership")
        composeRule.onNodeWithTag("playlist-picker-search").performTextInput("QUIET")
        composeRule.onNodeWithTag("playlist-choice-second").assertDoesNotExist()
        assertFullyVisible("playlist-choice-first")
        assertFullyVisible("content-actions-close")
        captureDialog("playlist-picker-filtered")
        composeRule.onNodeWithTag("playlist-choice-first").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("playlist-member-first", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("playlist-picker-search").performTextReplacement("driving")
        composeRule.onNodeWithTag("playlist-choice-first").assertDoesNotExist()
        composeRule.onNodeWithTag("playlist-choice-second").performClick()
        composeRule.onNodeWithTag("playlist-member-second", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(PlaylistChoice(firstPlaylist, true), PlaylistChoice(secondPlaylist, false)), calls)
        }
    }

    @Test fun pickerEmptyLoadingFailureRetryAndBusyStatesRemainDistinct() {
        var state by mutableStateOf(ContentActionsState(track, saved = true, loading = true, choosingPlaylist = true))
        var retries = 0
        var dismissed = 0
        render { Actions(state, onRetry = { retries++; state = state.copy(loading = true, failure = false) },
            onDismiss = { dismissed++ }) }
        composeRule.onNodeWithTag("content-actions-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-picker-empty").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(loading = false) }
        composeRule.onNodeWithTag("playlist-picker-empty").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(failure = true) }
        composeRule.onNodeWithTag("content-actions-error").assertIsDisplayed()
        captureDialog("playlist-picker-error")
        composeRule.onNodeWithTag("content-actions-retry").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("content-actions-loading").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, retries); state = state.copy(loading = false,
            playlists = listOf(PlaylistChoice(firstPlaylist, true)), busy = true) }
        composeRule.onNodeWithTag("playlist-choice-first").assertIsNotEnabled()
        composeRule.onNodeWithTag("content-actions-close").assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(busy = false) }
        composeRule.onNodeWithTag("playlist-choice-first").assertIsEnabled()
        composeRule.onNodeWithTag("playlist-picker-search").performTextInput("no-matching-playlist")
        composeRule.onNodeWithTag("playlist-picker-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-choice-first").assertDoesNotExist()
        captureDialog("playlist-picker-empty")
        composeRule.onNodeWithTag("content-actions-close").performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun homeLibraryAndDetailPassTheSelectedTrackOrAlbumWithoutStartingPlayback() {
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.TRACKS,
            items = listOf(track), libraries = mapOf(LibrarySection.TRACKS to listOf(track))))
        val actions = mutableListOf<SpotifyContent>()
        var played = 0
        render { Home(state, onActions = { actions += it }, onPlay = { played++ }) }
        composeRule.onNodeWithTag("add-track-track-one")
            .assertContentDescriptionEquals(text(R.string.remove_favorite)).performClick()
        composeRule.runOnIdle { state = state.copy(selectedSection = LibrarySection.ALBUMS, items = listOf(album)) }
        composeRule.onNodeWithTag("add-album-album-one")
            .assertContentDescriptionEquals(text(R.string.add_favorite)).performClick()
        composeRule.runOnIdle { state = state.copy(selectedContent = album, detail = ContentDetail(album, listOf(track))) }
        composeRule.onNodeWithTag("content-detail").performScrollToKey("actions")
        composeRule.onNodeWithTag("add-album-album-one").performClick()
        composeRule.onNodeWithTag("content-detail").performScrollToKey("0:${track.uri}")
        composeRule.onNodeWithTag("add-track-track-one").performClick()
        captureScreen(composeRule.onRoot(useUnmergedTree = true), "content-actions-album-detail")
        composeRule.runOnIdle { assertEquals(listOf(track, album, album, track), actions); assertEquals(0, played) }
    }

    @Test fun expandedPlayerPassesTheCurrentlyPlayingTrackToContentActions() {
        val calls = mutableListOf<SpotifyContent>()
        render { Home(PlayUiState(isLoggedIn = true, playback = Playback(track, durationMs = 180_000),
            libraries = mapOf(LibrarySection.TRACKS to listOf(track))), onActions = { calls += it }) }
        composeRule.onNodeWithTag("mini-player-info", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("expanded-player-screen").assertIsDisplayed()
        captureScreen(composeRule.onNodeWithTag("expanded-player-screen"), "content-actions-expanded-player")
        composeRule.onNodeWithTag("add-track-track-one")
            .assertContentDescriptionEquals(text(R.string.remove_favorite)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(track), calls) }
        composeRule.onNodeWithTag("expanded-player-back").performClick()
        composeRule.onNodeWithTag("expanded-player-screen").assertDoesNotExist()
    }

    @Test fun genreFilterKeepsTheReturnedSavedSongsCardAndOtherKindsStayFiltered() {
        val savedSongs = io.github.playmusic.data.api.SpotifyRepository.likedSongsContent("Saved songs")
        val genre = item(ContentKind.GENRE, "jazz", "Jazz")
        val state = PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.SEARCH,
            searchQuery = "music", searchFilter = SearchFilter.GENRES, items = listOf(savedSongs, genre, track))
        render { Home(state, onActions = {}) }
        composeRule.onNodeWithTag("content-playlist-tracks").assertIsDisplayed()
        composeRule.onNodeWithTag("content-genre-jazz").assertIsDisplayed()
        composeRule.onNodeWithTag("content-track-${track.id}").assertDoesNotExist()
    }

    @Test fun allSevenSearchFiltersDispatchAndShowOnlyTheirContentKinds() {
        val contents = ContentKind.entries.map { item(it, it.name.lowercase(), it.name) }
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.SEARCH,
            searchQuery = "music", items = contents))
        val filters = mutableListOf<SearchFilter>()
        val actions = mutableListOf<SpotifyContent>()
        render { Home(state, onActions = { actions += it }, onFilter = {
            filters += it; state = state.copy(searchFilter = it)
        }) }
        assertEquals(7, SearchFilter.entries.size)
        for (filter in SearchFilter.entries) {
            composeRule.onNodeWithTag("search-filter-${filter.name.lowercase()}").performScrollTo().performClick().assertIsSelected()
            val expected = contents.filter { it.kind in filter.kinds }
            for (content in expected) {
                composeRule.onNodeWithTag("content-list").performScrollToKey(content.uri)
                composeRule.onNodeWithTag("content-${content.kind.name.lowercase()}-${content.id}").assertIsDisplayed()
            }
            for (content in contents.filter { it.kind !in filter.kinds })
                composeRule.onNodeWithTag("content-${content.kind.name.lowercase()}-${content.id}").assertDoesNotExist()
            if (filter in listOf(SearchFilter.ALL, SearchFilter.PODCASTS, SearchFilter.GENRES))
                captureScreen(composeRule.onRoot(useUnmergedTree = true), "search-filter-${filter.name.lowercase()}")
        }
        composeRule.onNodeWithTag("search-filter-tracks").performScrollTo().performClick()
        composeRule.onNodeWithTag("add-track-track").performClick()
        composeRule.runOnIdle {
            assertEquals(SearchFilter.entries.toList(), filters.take(7))
            assertEquals(listOf(contents.single { it.kind == ContentKind.TRACK }), actions)
        }
    }

    @Test fun artistPickerDispatchesTheChosenArtistAndCanBeDismissed() {
        val artists = listOf(item(ContentKind.ARTIST, "one", "First artist"), item(ContentKind.ARTIST, "two", "Second artist"))
        var selected: SpotifyContent? = null
        var dismissed = false
        render { ArtistPicker(artists, { selected = it }, { dismissed = true }) }
        captureDialog("artist-picker")
        composeRule.onNodeWithTag("artist-choice-two").performClick()
        composeRule.onNodeWithTag("artist-picker-close").performClick()
        composeRule.runOnIdle { assertEquals(artists[1], selected); assertTrue(dismissed) }
    }

    @Composable private fun Actions(state: ContentActionsState, onFavorite: () -> Unit = {}, onPlaylists: () -> Unit = {},
        onPlaylist: (PlaylistChoice) -> Unit = {}, onRadio: () -> Unit = {}, onArtists: () -> Unit = {},
        onRetry: () -> Unit = {}, onDismiss: () -> Unit = {}) =
        ContentActionsDialog(state, onFavorite, onPlaylists, onPlaylist, onRadio, onArtists, onRetry, onDismiss)

    @Composable private fun Home(state: PlayUiState, onActions: (SpotifyContent) -> Unit,
        onPlay: (SpotifyContent) -> Unit = {}, onFilter: (SearchFilter) -> Unit = {}) =
        HomeScreen(state, onSectionSelected = {}, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {},
            onPlay = onPlay, onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {},
            onContentActions = onActions, onSearchFilter = onFilter)

    private fun text(id: Int) = composeRule.activity.getString(id)
    private fun captureDialog(name: String) = captureScreen(composeRule.onNode(isDialog()), name)
    private fun assertFullyVisible(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        assertTrue(full.right > full.left && full.bottom > full.top)
        assertEquals(full.right - full.left, visible.right - visible.left)
        assertEquals(full.bottom - full.top, visible.bottom - visible.top)
    }
    private fun render(content: @Composable () -> Unit) = composeRule.setContent {
        PlayTheme { Surface(Modifier.fillMaxSize()) { content() } }
    }
    private fun item(kind: ContentKind, id: String, title: String) =
        SpotifyContent(id, "spotify:${kind.name.lowercase()}:$id", title, "", null, kind)
}
