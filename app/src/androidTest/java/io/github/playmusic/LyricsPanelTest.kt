package io.github.playmusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.playmusic.data.api.LyricsSource
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.LyricsLine
import io.github.playmusic.data.model.LyricsSyncType
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.TrackLyrics
import io.github.playmusic.ui.LyricsPanel
import io.github.playmusic.ui.LyricsRoute
import io.github.playmusic.ui.LyricsState
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LyricsPanelTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val track = SpotifyContent("1", "spotify:track:0000000000000000000001", "Synthetic", "", null, ContentKind.TRACK)
    private val lyrics = TrackLyrics(track.uri, LyricsSyncType.LINE_SYNCED,
        (0..29).map { LyricsLine("Synthetic line $it", it * 2_000L) }, "Synthetic provider")

    @Test fun autoScrollFollowsForwardBackwardSeekAndPausePosition() {
        var position by mutableStateOf(0L)
        render { LyricsPanel(track.uri, LyricsState.Loaded(lyrics), track.uri, position, {}, {}) }
        composeRule.onNodeWithTag("lyrics-line-0").assertIsSelected()
        composeRule.runOnIdle { position = 30_000 }
        composeRule.onNodeWithTag("lyrics-line-15").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
        // Position is stationary while paused; no wall clock extrapolation may move the lyrics.
        composeRule.mainClock.advanceTimeBy(8_000)
        composeRule.onNodeWithTag("lyrics-line-15").assertIsSelected()
        composeRule.runOnIdle { position = 2_000 }
        composeRule.onNodeWithTag("lyrics-line-1").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
        captureScreen(composeRule.onRoot(), "lyrics-synchronized")
    }

    @Test fun manualScrollSuspendsFollowingUntilSyncOrLineTap() {
        var position by mutableStateOf(0L)
        var sought: Long? = null
        render { LyricsPanel(track.uri, LyricsState.Loaded(lyrics), track.uri, position, { sought = it; position = it }, {}) }
        composeRule.onNodeWithTag("lyrics-lines").performTouchInput { swipeUp(durationMillis = 1_000) }
        composeRule.onNodeWithTag("lyrics-sync").assertIsDisplayed()
        composeRule.runOnIdle { position = 56_000 }
        composeRule.onNodeWithTag("lyrics-line-28").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-sync").performClick()
        composeRule.onNodeWithTag("lyrics-line-28").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-lines").performScrollToIndex(2)
        composeRule.onNodeWithTag("lyrics-line-2").performClick()
        composeRule.runOnIdle { assertEquals(4_000L, sought) }
        composeRule.onNodeWithTag("lyrics-line-2").assertIsSelected()
        captureScreen(composeRule.onRoot(), "lyrics-after-seek")
    }

    @Test fun tapOnDifferentTracksLyricsRequestsThatTimestampWithoutFalseHighlight() {
        var sought: Long? = null
        render { LyricsPanel(track.uri, LyricsState.Loaded(lyrics), "different", 50_000, { sought = it }, {}) }
        composeRule.onNodeWithTag("lyrics-line-1").performClick()
        composeRule.runOnIdle { assertEquals(2_000L, sought) }
    }

    @Test fun accessibilityScrollingSuspendsFollowingAndSyncRestoresIt() {
        var position by mutableStateOf(0L)
        render { LyricsPanel(track.uri, LyricsState.Loaded(lyrics), track.uri, position, {}, {}) }
        composeRule.onNodeWithTag("lyrics-lines").performSemanticsAction(SemanticsActions.ScrollBy) { action ->
            org.junit.Assert.assertTrue(action(0f, 250f))
        }
        composeRule.onNodeWithTag("lyrics-sync").assertIsDisplayed()
        composeRule.runOnIdle { position = 56_000 }
        composeRule.onNodeWithTag("lyrics-line-28").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-sync").performClick()
        composeRule.onNodeWithTag("lyrics-line-28").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-lines").performScrollToIndex(3)
        composeRule.onNodeWithTag("lyrics-sync").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-line-3").assertIsDisplayed()
        composeRule.runOnIdle { position = 54_000 }
        composeRule.onNodeWithTag("lyrics-line-27").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-sync").performClick()
        composeRule.onNodeWithTag("lyrics-line-27").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
    }

    @Test fun gestureInterruptsAutoAnimationAndChangingTrackResetsFollowing() {
        var displayed by mutableStateOf(lyrics)
        var position by mutableStateOf(0L)
        render { LyricsPanel(displayed.trackUri, LyricsState.Loaded(displayed), displayed.trackUri, position, {}, {}) }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { position = 20_000 }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("lyrics-lines").performTouchInput { swipeUp() }
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("lyrics-sync").assertIsDisplayed()
        composeRule.runOnIdle { displayed = lyrics.copy(trackUri = "spotify:track:0000000000000000000002"); position = 4_000 }
        composeRule.onNodeWithTag("lyrics-sync").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-line-2").assertIsDisplayed().assertIsSelected()
    }

    @Test fun unavailableFailureEmptyAndUnsyncedAreDistinctAndRetryIsConnected() {
        var state by mutableStateOf<LyricsState>(LyricsState.Unavailable)
        var retries = 0
        render { LyricsPanel(track.uri, state, track.uri, 0, { error("Unsynced lyrics cannot seek") }, { retries++ }) }
        composeRule.onNodeWithTag("lyrics-unavailable").assertIsDisplayed()
        composeRule.runOnIdle { state = LyricsState.Failed }
        composeRule.onNodeWithTag("lyrics-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries); state = LyricsState.Loaded(lyrics.copy(lines = emptyList())) }
        composeRule.onNodeWithTag("lyrics-empty").assertIsDisplayed()
        composeRule.runOnIdle { state = LyricsState.Loaded(lyrics.copy(syncType = LyricsSyncType.UNSYNCED, lines = listOf(LyricsLine("Synthetic plain line")))) }
        composeRule.onNodeWithTag("lyrics-unsynced").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-line-0").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("lyrics-provider").assertIsDisplayed()
        composeRule.runOnIdle { state = LyricsState.Loaded(lyrics.copy(syncType = LyricsSyncType.SYLLABLE_SYNCED,
            lines = listOf(LyricsLine("Synthetic limited line", 0)), isCapped = true)) }
        composeRule.onNodeWithTag("lyrics-limited").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-line-0").assertIsSelected()
        composeRule.onNodeWithTag("lyrics-unsynced").assertDoesNotExist()
    }

    @Test fun routeCancelsOldTrackAndRetryDoesNotKeepOldFailure() {
        val oldResponse = CompletableDeferred<TrackLyrics?>()
        val second = track.copy(uri = "spotify:track:0000000000000000000002")
        var current by mutableStateOf(track)
        var secondAttempts = 0
        val source = LyricsSource { uri ->
            if (uri == track.uri) oldResponse.await() else {
                secondAttempts++
                if (secondAttempts == 1) error("Synthetic transport failure")
                lyrics.copy(trackUri = second.uri, lines = listOf(LyricsLine("Synthetic new response", 0)))
            }
        }
        render { LyricsRoute(current, Playback(), source, {}) }
        composeRule.onNodeWithTag("lyrics-loading").assertIsDisplayed()
        composeRule.runOnIdle { current = second }
        composeRule.waitUntil(5_000) { secondAttempts == 1 }
        composeRule.onNodeWithTag("lyrics-failed").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-retry").performClick()
        composeRule.waitUntil(5_000) { secondAttempts == 2 }
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Synthetic new response")
        composeRule.runOnIdle { oldResponse.complete(lyrics) }
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Synthetic new response")
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            PlayTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) { content() } } }
        }
    }
}
