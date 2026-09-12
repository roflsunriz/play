package io.github.playmusic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class SetupScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupStartsWithBrowserAuthorization() {
        composeRule.onNodeWithTag("username-input").assertDoesNotExist()
        composeRule.onNodeWithTag("password-input").assertDoesNotExist()
        composeRule.onNodeWithTag("browser-login-button").performScrollTo().assertIsDisplayed().assertIsEnabled()
        captureScreen(composeRule.onRoot(), "setup")
    }
}
