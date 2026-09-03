package io.github.playmusic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class SetupScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupRequiresCredentialsBeforeLogin() {
        composeRule.onNodeWithTag("username-input").assertIsDisplayed()
        composeRule.onNodeWithTag("password-input").assertIsDisplayed()
        composeRule.onNodeWithTag("login-button").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
}