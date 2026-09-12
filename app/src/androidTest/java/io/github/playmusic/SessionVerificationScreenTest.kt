package io.github.playmusic

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.ErrorKind
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class SessionVerificationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "verification_test_${java.util.UUID.randomUUID()}"
    private val cache = File(base.cacheDir, name)
    private val modelStore = ViewModelStore()
    private var container: AppContainer? = null
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(name, Context.MODE_PRIVATE)
        override fun getCacheDir(): File = cache.apply { mkdirs() }
    }

    @After
    fun cleanup() {
        modelStore.clear()
        runBlocking { container?.localPlayback?.release() }
        base.deleteSharedPreferences(name)
        check(cache.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        cache.deleteRecursively()
    }

    @Test
    fun expiredSessionAcceptsCodeAndReturnsToLibraryWithoutPassword() {
        val (viewModel, requests) = createModel()
        render(viewModel)
        composeRule.waitUntil(5_000) { viewModel.state.value.loginPending != null }
        composeRule.onNodeWithTag("code-input").assertIsDisplayed().performTextInput("123456")
        captureScreen(composeRule.onRoot(), "verification-code")
        val submit = composeRule.onNodeWithTag("code-submit-button")
        // IME insets and focus scrolling can still be settling after text input.
        composeRule.waitUntil(5_000) {
            submit.performScrollTo()
            val full = submit.getUnclippedBoundsInRoot()
            val visible = submit.getBoundsInRoot()
            full.bottom > full.top && visible.bottom - visible.top == full.bottom - full.top
        }
        submit.assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.items.isNotEmpty() }
        composeRule.onNodeWithTag("content-playlist-saved").assertIsDisplayed()
        assertNull(viewModel.state.value.loginPending)
        assertTrue(viewModel.state.value.isLoggedIn)
        assertEquals(2, requests.get())
        assertArrayEquals(byteArrayOf(1, 2, 3), SecureSessionStore(context).loadSession()?.storedCredential)
        assertTrue(SecureSessionStore(context).loadSession()?.expiresSoon() == false)
    }

    @Test
    fun cancellingRefreshVerificationKeepsSavedLoginInformation() {
        val (viewModel, requests) = createModel()
        render(viewModel)
        composeRule.waitUntil(5_000) { viewModel.state.value.loginPending != null }
        composeRule.onNodeWithTag("code-cancel-button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertNull(viewModel.state.value.loginPending)
            assertTrue(viewModel.state.value.isLoggedIn)
            assertEquals(1, requests.get())
            assertArrayEquals(byteArrayOf(1, 2, 3), SecureSessionStore(context).loadSession()?.storedCredential)
        }
    }

    @Test
    fun playbackDoesNotStartASeparatePairingOrBrowserFlow() {
        val (viewModel, _) = createModel(expired = false, libraryStatus = 404)
        render(viewModel)
        composeRule.waitUntil(5_000) { viewModel.state.value.error != null }
        composeRule.runOnIdle {
            viewModel.clearError()
            viewModel.play(io.github.playmusic.data.model.SpotifyContent("test", "spotify:track:0000000000000000000001",
                "Synthetic track", "", null, io.github.playmusic.data.model.ContentKind.TRACK))
            assertEquals(ErrorKind.LOGIN_REQUIRED, viewModel.state.value.error?.kind)
            assertTrue(!viewModel.state.value.isAuthorizing)
            assertNull(viewModel.state.value.browserAuthorization)
            assertArrayEquals(byteArrayOf(1, 2, 3), SecureSessionStore(context).loadSession()?.storedCredential)
        }
    }

    @Test
    fun libraryNotFoundIsNotAPlaybackErrorAndBackgroundRefreshDoesNotEraseIt() {
        val (viewModel, _) = createModel(expired = false, libraryStatus = 404)
        render(viewModel)
        composeRule.waitUntil(5_000) { viewModel.state.value.error != null }
        composeRule.runOnIdle {
            assertEquals(ErrorKind.REQUEST, viewModel.state.value.error?.kind)
            viewModel.refreshPlayback()
            assertEquals(ErrorKind.REQUEST, viewModel.state.value.error?.kind)
        }
        composeRule.onNodeWithTag("error-dismiss-button").performClick()
        composeRule.runOnIdle { assertNull(viewModel.state.value.error) }
    }

    private fun render(viewModel: PlayViewModel) {
        composeRule.setContent { PlayTheme { PlayRoute(viewModel) } }
    }

    private fun createModel(expired: Boolean = true, libraryStatus: Int = 200): Pair<PlayViewModel, AtomicInteger> {
        SecureSessionStore(context).saveSession(AuthSession("synthetic-user", "test-token", byteArrayOf(1, 2, 3), if (expired) 0 else Long.MAX_VALUE))
        val count = AtomicInteger()
        val clientTokens = SpotifyClientTokenClient(openConnection = { uri -> Connection(uri) {
            fieldVarint(1, 1) + fieldBytes(2, fieldString(1, "synthetic-sdk-token") + fieldVarint(2, 3600) + fieldVarint(3, 1800))
        } })
        val login = SpotifyLogin5Client("synthetic-client", clientTokens) { uri -> Connection(uri) { body ->
            assertEquals("/v3/login", uri.path)
            val fields = readFields(body)
            assertTrue(100 in fields)
            assertTrue(111 !in fields)
            if (count.incrementAndGet() == 1) {
                fieldBytes(3, fieldBytes(1, fieldBytes(2, fieldString(5, "u***@example.com")))) +
                    fieldBytes(5, byteArrayOf(4, 5, 6))
            } else {
                assertArrayEquals(byteArrayOf(4, 5, 6), fields[2])
                val code = checkNotNull(fields[3])
                assertTrue(String(code, Charsets.UTF_8).contains("123456"))
                // A refresh may omit an unchanged stored credential; it must be retained.
                fieldBytes(1, fieldString(1, "synthetic-user") + fieldString(2, "renewed-token") + fieldVarint(4, 3600))
            }
        } }
        val app = AppContainer(context, clientTokens, login) { uri -> Connection(uri, libraryStatus) {
            check(uri.path.endsWith("/rootlist"))
            fieldBytes(5, fieldVarint(1, 0) + fieldVarint(2, 0) +
                fieldBytes(3, fieldString(1, "spotify:playlist:saved")) +
                fieldBytes(4, fieldBytes(2, fieldString(1, "Recovered playlist")) + fieldVarint(9, 400)))
        } }
        container = app
        val model = PlayViewModel(app)
        modelStore.put("verification", model)
        return model to count
    }

    private fun readFields(bytes: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        val reader = ProtoWire.Reader(bytes)
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.wireType(tag) == 2) result[reader.fieldNumber(tag)] = reader.readBytes()
            else reader.skip(reader.wireType(tag))
        }
        return result
    }

    private class Connection(uri: URI, private val status: Int = 200, private val reply: (ByteArray) -> ByteArray) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val body by lazy { reply(output.toByteArray()) }
        override fun getOutputStream() = output
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
