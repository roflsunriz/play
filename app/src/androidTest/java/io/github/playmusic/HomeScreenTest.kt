package io.github.playmusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

        composeRule.onNodeWithTag("content-track-track-id").performClick()
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
        var selected: LibrarySection? = null

        composeRule.setContent {
            PlayTheme {
                HomeScreen(
                    state = state,
                    onSectionSelected = { selected = it },
                    onSearchChanged = { state = state.copy(searchQuery = it) },
                    onSearch = { searched = true },
                    onRefresh = {},
                    onLogout = {},
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
        composeRule.onNodeWithTag("search-button").performClick()
        composeRule.onNodeWithTag("section-playlists").performClick()

        composeRule.runOnIdle {
            assertTrue(searched)
            assertEquals(LibrarySection.PLAYLISTS, selected)
        }
    }
}
