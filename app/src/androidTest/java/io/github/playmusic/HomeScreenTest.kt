package io.github.playmusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    val composeRule = createComposeRule()

    @Test
    fun untitledPlaylistHasALocalizedTitleAndRemainsClickable() {
        val item = SpotifyContent("untitled", "spotify:playlist:untitled", "", "", null, ContentKind.PLAYLIST)
        var played = false
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.untitled_playlist)
        composeRule.setContent {
            PlayTheme {
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
            PlayTheme {
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
            PlayTheme {
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
        composeRule.onNodeWithTag("logout-button").performClick()

        composeRule.runOnIdle {
            assertTrue(searched)
            assertTrue(refreshed)
            assertTrue(loggedOut)
            assertEquals(LibrarySection.PLAYLISTS, selected)
        }
    }
}
