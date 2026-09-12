package io.github.playmusic

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.ui.BrowserAuthorizationUi
import io.github.playmusic.ui.BrowserLoginScreen
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class BrowserLoginScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun beginsWaitsOpensTheBrowserAndCancelsWithoutLosingTheScreen() {
        var authorizing by mutableStateOf(false)
        var pending by mutableStateOf<BrowserAuthorizationUi?>(null)
        var opened: Intent? = null
        var failure: String? = null
        var missingBrowser = false
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                if (missingBrowser) throw ActivityNotFoundException("Synthetic missing browser")
                opened = intent
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                PlayTheme {
                    BrowserLoginScreen(pending, authorizing, onBegin = { authorizing = true },
                        onCancel = { pending = null; authorizing = false }, onFailure = { failure = it })
                }
            }
        }
        composeRule.onNodeWithTag("browser-login-button").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("browser-login-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("browser-login-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open-browser-login-button").assertDoesNotExist()
        composeRule.runOnIdle { pending = BrowserAuthorizationUi("SYNTHETIC", "https://spotify.com/pair?code=SYNTHETIC") }
        composeRule.onNodeWithTag("browser-login-code").performScrollTo().assertTextEquals("SYNTHETIC")
        captureScreen(composeRule.onRoot(), "browser-authorization")
        composeRule.onNodeWithTag("open-browser-login-button").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(Intent.ACTION_VIEW, opened?.action)
            assertEquals(pending?.verificationUri, opened?.dataString)
            missingBrowser = true
        }
        composeRule.onNodeWithTag("open-browser-login-button").performClick()
        composeRule.runOnIdle { assertNotNull(failure) }
        composeRule.onNodeWithTag("browser-login-cancel-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("browser-login-button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("browser-login-code").assertDoesNotExist()
    }
}
