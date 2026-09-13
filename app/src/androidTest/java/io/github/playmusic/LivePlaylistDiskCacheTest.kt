package io.github.playmusic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.cache.PlaylistDiskCache
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Uses the signed-in account read-only, and blocks only the reconstructed container's library transport. */
class LivePlaylistDiskCacheTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test fun persistedLibraryRendersBeforeSyncAndRemainsAvailableWhenSyncCannotConnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val live = (context.applicationContext as PlayApplication).container
        val account = runBlocking { live.sessionManager.username() }
        val expected = runBlocking { live.repository.library(ContentKind.PLAYLIST, forceRefresh = true) }
        assertTrue(expected.isNotEmpty())
        val reopened = PlaylistDiskCache(context)
        try { assertEquals(expected, runBlocking { reopened.read(account).snapshot?.entries?.map { it.content } }) }
        finally { reopened.close() }
        val calls = AtomicInteger()
        val cacheWasAlreadyVisible = AtomicBoolean(true)
        lateinit var model: PlayViewModel
        val offline = AppContainer(context, apiConnection = {
            calls.incrementAndGet()
            if (model.state.value.items != expected) cacheWasAlreadyVisible.set(false)
            throw IOException("Synthetic unavailable library transport")
        })
        val models = ViewModelStore()
        try {
            composeRule.runOnIdle { model = PlayViewModel(offline); models.put("offline-cache", model) }
            composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) { PlayRoute(model) } } }
            composeRule.waitUntil(10_000) { model.state.value.playlistSyncFailed }
            assertTrue(calls.get() > 0)
            assertTrue("Disk content must be visible before a library request begins", cacheWasAlreadyVisible.get())
            assertEquals(expected, model.state.value.items)
            assertFalse(model.state.value.isLoading)
            assertNull(model.state.value.error)
            composeRule.onNodeWithTag("content-playlist-${expected.first().id}").assertIsDisplayed()
            composeRule.onNodeWithTag("loading-indicator").assertDoesNotExist()
            composeRule.onNodeWithTag("error-dismiss-button").assertDoesNotExist()
            captureScreen(composeRule.onRoot(), "playlist-disk-live-offline")
            assertEquals(expected, runBlocking { offline.repository.cachedPlaylists() })
        } finally {
            composeRule.runOnIdle { models.clear() }
            runBlocking { offline.localPlayback.release() }
            offline.playlistDiskCache.close()
        }
    }

    companion object {
        @BeforeClass @JvmStatic fun requireExplicitAccount() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("livePlaylistCache") == "true")
        }
    }
}
