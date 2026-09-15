package io.github.playmusic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.SpotifyRepository
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Read-only opt-in check of the actual search UI, including owner enrichment and navigation cards. */
class SearchRegressionScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun reportedQueriesWorkWithAllAndAffectedFilters() {
        composeRule.waitUntil(30_000) { composeRule.onAllNodesWithTag("section-search").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("section-search").performClick()
        for ((query, filter) in listOf("トリッカル" to "playlists", "インターネット" to "genres")) {
            composeRule.onNodeWithTag("search-filter-all").performScrollTo().performClick()
            composeRule.onNodeWithTag("search-input").performTextReplacement(query)
            composeRule.onNodeWithTag("search-button").performClick()
            awaitResults()
            captureScreen(composeRule.onRoot(), "search-${filter}-all")
            composeRule.onNodeWithTag("search-filter-$filter").performScrollTo().performClick()
            awaitResults()
            captureScreen(composeRule.onRoot(), "search-$filter")
        }
        composeRule.onNodeWithTag("content-list").performScrollToKey(SpotifyRepository.LIKED_SONGS_URI)
        composeRule.onNodeWithTag("content-playlist-tracks").assertIsDisplayed().performClick()
        composeRule.waitUntil(30_000) { composeRule.onAllNodesWithTag("loading-indicator").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("error-dismiss-button").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-title").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "search-saved-songs-detail")
    }

    private fun awaitResults() {
        composeRule.waitUntil(45_000) {
            composeRule.onAllNodesWithTag("loading-indicator").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag("search-results-heading").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("error-dismiss-button").assertDoesNotExist()
        composeRule.onNodeWithTag("search-results-heading").assertIsDisplayed()
    }

    companion object {
        @BeforeClass @JvmStatic fun requireExplicitLiveRun() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        }
    }
}
