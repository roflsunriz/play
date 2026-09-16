package io.github.playmusic

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.ui.GITHUB_RELEASES_URL
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Hamburger menu download link: no account, player, or network is used. */
class GithubDownloadMenuTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test fun downloadMenuOpensTheReleasePage() {
        var opened: Intent? = null
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent, options: android.os.Bundle?) = startActivity(intent)
            override fun startActivity(intent: Intent) {
                opened = intent
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                PlayTheme {
                    HomeScreen(PlayUiState(isLoggedIn = true),
                        onSectionSelected = {}, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {}, onPlay = {}, onPlayPause = {},
                        onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {})
                }
            }
        }
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("github-download-menu-item").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "github-download-menu")
        composeRule.onNodeWithTag("github-download-menu-item").performClick()
        composeRule.waitUntil { opened != null }
        composeRule.runOnIdle {
            assertEquals(Intent.ACTION_VIEW, opened?.action)
            assertEquals(GITHUB_RELEASES_URL, opened?.dataString)
        }
    }

    @Test fun downloadMenuShowsAnErrorWhenNoBrowserIsAvailable() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val unavailable = target.getString(R.string.browser_unavailable)
        val context = object : ContextWrapper(target) {
            override fun startActivity(intent: Intent, options: android.os.Bundle?) = startActivity(intent)
            override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException("Synthetic missing browser")
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                PlayTheme {
                    HomeScreen(PlayUiState(isLoggedIn = true),
                        onSectionSelected = {}, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {}, onPlay = {}, onPlayPause = {},
                        onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {})
                }
            }
        }
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("github-download-menu-item").performClick()
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(unavailable).assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }
}
