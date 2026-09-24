package io.github.playmusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val track = MusicContent("synthetic", "spotify:track:0000000000000000000001",
        "Synthetic track", "Artist", null, ContentKind.TRACK, durationMs = 180_000)

    @Test fun dragRetainsItsPositionWhileProgressUpdatesAndTapSeeks() {
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, items = listOf(track),
            playback = Playback(track, progressMs = 20_000, durationMs = 180_000, isPlaying = true)))
        val seeks = mutableListOf<Long>()
        composeRule.setContent {
            PlayTheme {
                HomeScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, { seeks += it }, {}, {})
            }
        }
        val slider = composeRule.onNodeWithTag("seek-slider")
        slider.performTouchInput {
            down(Offset(width * .2f, centerY))
            moveTo(Offset(width * .7f, centerY))
        }
        val dragged = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        composeRule.runOnIdle {
            assertTrue(seeks.isEmpty())
            state = state.copy(playback = state.playback.copy(progressMs = 70_000))
        }
        val afterProgress = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(dragged, afterProgress, 1f)
        slider.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(1, seeks.size)
            assertEquals(dragged.toLong(), seeks.single())
        }
        slider.performTouchInput { down(center); up() }
        composeRule.runOnIdle {
            assertEquals(2, seeks.size)
            assertTrue(seeks.last() in 80_000..100_000)
        }
    }

    @Test fun openingDetailsAndReturningDoesNotStartPlayback() {
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, items = listOf(track)))
        var played = false
        composeRule.setContent {
            PlayTheme {
                HomeScreen(state, {}, {}, {}, {}, {}, { played = true }, {}, {}, {}, {}, {}, {},
                    onOpenContent = { state = state.copy(selectedContent = it, detail = ContentDetail(it)) },
                    onBack = { state = state.copy(selectedContent = null, detail = null) })
            }
        }
        composeRule.onNodeWithTag("content-track-synthetic").performClick()
        composeRule.onNodeWithTag("detail-back-button").performClick()
        composeRule.onNodeWithTag("content-track-synthetic").assertExists()
        composeRule.runOnIdle { assertFalse(played) }
    }
}
