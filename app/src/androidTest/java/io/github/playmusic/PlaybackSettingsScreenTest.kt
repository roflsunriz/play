package io.github.playmusic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.playmusic.data.model.*
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.PlaybackTransitionState
import io.github.playmusic.ui.*
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackSettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test fun menuOpensSettingsAndEveryFadeKeepsItsIndependentOneToTwelveSecondValue() {
        var state by mutableStateOf(PlaybackTransitionState(isReady = true))
        var open by mutableStateOf(false)
        compose.setContent { PlayTheme {
            if (open) PlaybackSettingsScreen(state, { state = state.copy(settings = it) }, {}, { open = false })
            else HomeScreen(PlayUiState(isLoggedIn = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onPlaybackSettings = { open = true })
        } }
        compose.onNodeWithTag("settings-button").performClick()
        compose.onNodeWithTag("playback-settings-menu-item").performClick()
        for (tag in listOf("fade-in", "fade-out", "crossfade")) {
            compose.onNodeWithTag("playback-settings-list").performScrollToKey(tag)
            compose.onNodeWithTag("$tag-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
            compose.onNodeWithTag("$tag-seconds").assertTextEquals(compose.activity.getString(R.string.playback_fade_seconds, 12))
            compose.onNodeWithTag("playback-settings-list").performScrollToKey(tag)
            val toggle = compose.onNodeWithTag("$tag-switch").performScrollTo()
            val full = toggle.getUnclippedBoundsInRoot()
            val visible = toggle.getBoundsInRoot()
            assertEquals(full.right - full.left, visible.right - visible.left)
            assertEquals(full.bottom - full.top, visible.bottom - visible.top)
            captureScreen(compose.onRoot(), "playback-$tag-before-toggle")
            toggle.performClick().assertIsOff()
            compose.onNodeWithTag("$tag-slider").assertIsNotEnabled()
            compose.onNodeWithTag("$tag-switch").performScrollTo().performClick().assertIsOn()
        }
        compose.runOnIdle {
            assertEquals(12, state.settings.fadeInSeconds); assertEquals(12, state.settings.fadeOutSeconds)
            assertEquals(12, state.settings.crossfadeSeconds)
        }
        compose.onNodeWithTag("playback-settings-list").performScrollToKey("fade-in")
        compose.onNodeWithTag("fade-in-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("playback-settings-list").performScrollToKey("normalizer")
        compose.onNodeWithTag("peak-normalizer-switch").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("playback-settings-list").performScrollToKey("automix")
        compose.onNodeWithTag("automix-switch").performScrollTo().performClick().assertIsOff()
        captureScreen(compose.onRoot(), "playback-settings")
        compose.onNodeWithTag("playback-settings-back").performClick()
        compose.onNodeWithTag("settings-button").performClick()
        compose.onNodeWithTag("playback-settings-menu-item").performClick()
        compose.runOnIdle {
            assertEquals(1, state.settings.fadeInSeconds); assertEquals(12, state.settings.fadeOutSeconds)
            assertEquals(12, state.settings.crossfadeSeconds); assertTrue(state.settings.peakNormalizationEnabled)
            assertFalse(state.settings.automixEnabled)
        }
    }

    @Test fun loadingDisablesEditsAndSavingFailureKeepsCurrentValuesAndRetry() {
        var state by mutableStateOf(PlaybackTransitionState())
        var retries = 0
        compose.setContent { PlayTheme { PlaybackSettingsScreen(state, { state = state.copy(settings = it) }, { retries++ }, {}) } }
        compose.onNodeWithTag("fade-in-switch").assertIsNotEnabled()
        compose.onNodeWithTag("fade-in-slider").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(isReady = true, storageFailed = true,
            settings = PlaybackTransitionSettings(fadeInSeconds = 7)) }
        compose.onNodeWithTag("playback-settings-retry").performScrollTo().performClick()
        compose.onNodeWithTag("fade-in-seconds").performScrollTo().assertTextEquals(compose.activity.getString(R.string.playback_fade_seconds, 7))
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun seekCrossfadeToggleAndSliderStayIndependentFromTrackCrossfade() {
        var state by mutableStateOf(PlaybackTransitionState(isReady = true))
        var changed: PlaybackTransitionSettings? = null
        compose.setContent { PlayTheme {
            PlaybackSettingsScreen(state, { changed = it; state = state.copy(settings = it) }, {}, {})
        } }
        compose.onNodeWithTag("playback-settings-list").performScrollToKey("seek-crossfade")
        compose.onNodeWithTag("seek-crossfade-switch").assertIsOff().performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(true, changed?.seekCrossfadeEnabled) }
        compose.onNodeWithTag("seek-crossfade-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.onNodeWithTag("seek-crossfade-seconds").assertTextEquals(compose.activity.getString(R.string.playback_fade_seconds, 7))
        compose.runOnIdle {
            assertEquals(7, changed?.seekCrossfadeSeconds)
            assertTrue(changed?.crossfadeEnabled == true)
            assertEquals(5, changed?.crossfadeSeconds)
        }
    }

    @Test fun expandedPlayerStopUsesItsOwnTransportAction() {
        val track = SpotifyContent("track", "spotify:track:track", "Synthetic song", "", null, ContentKind.TRACK)
        var stops = 0
        var toggles = 0
        compose.setContent { PlayTheme { ExpandedPlayerScreen(Playback(item = track, isPlaying = true, durationMs = 100_000),
            { toggles++ }, {}, {}, {}, {}, {}, {}, onStop = { stops++ }) } }
        compose.onNodeWithTag("expanded-player-stop").assertContentDescriptionEquals(compose.activity.getString(R.string.playback_stop)).performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, toggles) }
    }

    @Test fun expandedPlayerConnectsThePlayingItemsLyricsSlot() {
        val track = SpotifyContent("track", "spotify:track:track", "Synthetic song", "", null, ContentKind.TRACK)
        var supplied: SpotifyContent? = null
        compose.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) {
            ExpandedPlayerScreen(Playback(item = track, isPlaying = true, durationMs = 100_000), {}, {}, {}, {}, {}, {}, {},
                lyricsContent = { supplied = it; Text("Synthetic lyrics slot", Modifier.testTag("synthetic-lyrics")) })
        } } }
        compose.onNodeWithTag("synthetic-lyrics").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(track.uri, supplied?.uri) }
    }
}
