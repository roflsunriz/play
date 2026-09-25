package io.github.playmusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.WebSessionDialog
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebSessionDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun settingsMenuExposesTheWebSessionEntry() {
        var opened = false
        composeRule.setContent {
            PlayTheme { HomeScreen(PlayUiState(isLoggedIn = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onWebSession = { opened = true }) }
        }
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("web-session-menu-item").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test fun invalidValuesShowAnErrorAndNeverReachSaving() {
        var saved: String? = "unset"
        var input by mutableStateOf("has space")
        var invalid by mutableStateOf(false)
        composeRule.setContent {
            PlayTheme {
                WebSessionDialog(input, invalid, false, false, { input = it; invalid = false },
                    { if (input == "valid-cookie-value") saved = input else invalid = true }, {}, {})
            }
        }
        composeRule.onNodeWithTag("web-session-save").performClick()
        composeRule.onNodeWithTag("web-session-error").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("unset", saved) }
        composeRule.onNodeWithTag("web-session-input").performTextClearance()
        composeRule.onNodeWithTag("web-session-input").performTextInput("valid-cookie-value")
        composeRule.onNodeWithTag("web-session-save").performClick()
        composeRule.runOnIdle { assertEquals("valid-cookie-value", saved) }
    }

    @Test fun removeAndCancelAreWiredSeparatelyFromSaving() {
        var removed = false
        var cancelled = false
        var saved: String? = null
        composeRule.setContent {
            PlayTheme {
                WebSessionDialog("", false, false, true, {}, { saved = "" }, { removed = true }, { cancelled = true })
            }
        }
        composeRule.onNodeWithTag("web-session-remove").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("web-session-saved").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(removed)
            assertNull(saved)
        }
        composeRule.onNodeWithTag("web-session-cancel").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }
}
