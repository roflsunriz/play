package io.github.playmusic

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Existing library is read-only; starts and pauses one existing track in the app. */
class LiveBrowsingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun cachedTabsLiveSearchDetailsAndExpandedPlayerWorkTogether() {
        val app = (composeRule.activity.application as PlayApplication).container
        composeRule.waitUntil(90_000) { listOf(ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.TRACK)
            .all { app.repository.peekLibrary(it) != null } }
        captureScreen(composeRule.onRoot(), "live-playlist-metadata")
        val album = checkNotNull(app.repository.peekLibrary(ContentKind.ALBUM)).first { it.subtitle.isNotBlank() }
        composeRule.onNodeWithTag("section-albums").performClick()
        composeRule.onNodeWithTag("album-filter-input").performTextInput(album.subtitle)
        composeRule.onNodeWithTag("content-album-${album.id}").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "live-album-filter")
        composeRule.onNodeWithTag("library-sort-button").performClick()
        composeRule.onNodeWithTag("sort-title").performClick()
        val savedTrack = checkNotNull(app.repository.peekLibrary(ContentKind.TRACK)).first { !it.albumTitle.isNullOrBlank() }
        composeRule.onNodeWithTag("section-tracks").performClick()
        composeRule.onNodeWithTag("track-filter-input").performTextInput(checkNotNull(savedTrack.albumTitle))
        composeRule.onNodeWithTag("content-track-${savedTrack.id}").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "live-track-filter")
        composeRule.onNodeWithTag("section-albums").performClick()
        composeRule.onNodeWithTag("album-filter-input").assertTextEquals(album.subtitle)
        composeRule.onNodeWithTag("clear-library-filter").performClick()
        composeRule.onNodeWithTag("section-tracks").performClick()
        composeRule.onNodeWithTag("track-filter-input").assertTextEquals(checkNotNull(savedTrack.albumTitle))
        composeRule.onNodeWithTag("clear-library-filter").performClick()
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("account-login-button").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        composeRule.onNodeWithTag("section-search").performClick()
        composeRule.onNodeWithTag("suggested-heading").assertIsDisplayed()
        composeRule.onNodeWithTag("search-input").performTextInput("Nirvana")
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("search-results-heading").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("error-dismiss-button").assertDoesNotExist()
        captureScreen(composeRule.onRoot(), "live-search-results")
        val albums = SemanticsMatcher("album result") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("content-album-") == true
        }
        composeRule.onAllNodes(albums).onFirst().performClick()
        composeRule.waitUntil(30_000) { composeRule.onAllNodesWithTag("loading-indicator").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("error-dismiss-button").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-title").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "live-album-metadata")
        composeRule.onNodeWithTag("section-tracks").performClick()
        val track = checkNotNull(app.repository.peekLibrary(ContentKind.TRACK)).first { it.isPlayable != false }
        composeRule.onNodeWithTag("content-list").performScrollToKey(track.uri)
        composeRule.onNodeWithTag("content-track-${track.id}").performClick()
        composeRule.waitUntil(30_000) { composeRule.onAllNodesWithTag("loading-indicator").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("content-detail").performScrollToKey("actions")
        composeRule.onNodeWithTag("detail-play-button").performClick()
        try {
            composeRule.waitUntil(30_000) { composeRule.onAllNodesWithTag("mini-player-info", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("mini-player-info", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("expanded-artwork").assertIsDisplayed()
            composeRule.onNodeWithTag("expanded-title").assertIsDisplayed()
            captureScreen(composeRule.onRoot(), "live-expanded-player")
            composeRule.onNodeWithTag("expanded-play-pause").performClick()
            composeRule.onNodeWithTag("expanded-player-back").performClick()
            composeRule.onNodeWithTag("content-detail").assertIsDisplayed()
        } finally {
            kotlinx.coroutines.runBlocking { app.localPlayback.pause() }
        }
    }

    companion object {
        @BeforeClass @JvmStatic fun requireExplicitLiveRun() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("liveBrowsing") == "true")
        }
    }
}
