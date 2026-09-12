package io.github.playmusic

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
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

    @Test fun loginOpensTheBrowserOnceWithoutShowingAPairingCode() {
        var authorizing by mutableStateOf(false)
        var pending by mutableStateOf<BrowserAuthorizationUi?>(null)
        var surfaceVersion by mutableStateOf(0)
        var opened: Intent? = null
        var opens = 0
        var failure: String? = null
        var missingBrowser = false
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent, options: android.os.Bundle?) = startActivity(intent)
            override fun startActivity(intent: Intent) {
                if (missingBrowser) throw ActivityNotFoundException("Synthetic missing browser")
                opened = intent
                opens++
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                PlayTheme {
                    key(surfaceVersion) {
                        BrowserLoginScreen(pending, authorizing, onBegin = { authorizing = true },
                            onCancel = { pending = null; authorizing = false }, onFailure = { failure = it },
                            onBrowserOpened = { pending = pending?.copy(browserOpened = true) })
                    }
                }
            }
        }
        composeRule.onNodeWithTag("browser-login-button").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("browser-login-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("browser-login-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open-browser-login-button").assertDoesNotExist()
        composeRule.runOnIdle { pending = BrowserAuthorizationUi("https://accounts.spotify.com/authorize?state=synthetic") }
        composeRule.waitUntil { opened != null }
        composeRule.onNodeWithTag("browser-login-code").assertDoesNotExist()
        captureScreen(composeRule.onRoot(), "browser-authorization")
        composeRule.runOnIdle {
            assertEquals(Intent.ACTION_VIEW, opened?.action)
            assertEquals(pending?.authorizationUrl, opened?.dataString)
            assertEquals(1, opens)
            surfaceVersion++
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("Recreating the screen must not open another browser", 1, opens) }
        composeRule.onNodeWithTag("browser-login-cancel-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("browser-login-button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("browser-login-code").assertDoesNotExist()
        composeRule.runOnIdle {
            missingBrowser = true
            authorizing = true
            pending = BrowserAuthorizationUi("https://accounts.spotify.com/authorize?state=second")
        }
        composeRule.waitUntil { failure != null }
        composeRule.runOnIdle { assertNotNull(failure) }
    }
}
