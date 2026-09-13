package io.github.playmusic

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.audio.AudioEffectsStore
import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class AudioEffectsRouteTest {
    @get:Rule val compose = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "effects_route_${java.util.UUID.randomUUID()}"
    private val directory = File(base.cacheDir, name)
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val models = ViewModelStore()
    private lateinit var app: AppContainer
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(requested: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("${name}_$requested".also(preferenceNames::add), mode)
        override fun getCacheDir(): File = directory.apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
    }

    @After fun cleanup() {
        compose.runOnIdle { models.clear() }
        if (::app.isInitialized) {
            runBlocking { app.audioEffects.close(); app.localPlayback.release() }
            app.playlistDiskCache.close()
        }
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
        check(directory.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        directory.deleteRecursively()
    }

    @Test fun menuControlsPersistAndReturningKeepsTheLibraryScrollPosition() {
        SecureSessionStore(context).saveSession(AuthSession("synthetic-effects", "synthetic-access", null, Long.MAX_VALUE, "synthetic-refresh"))
        app = AppContainer(context, apiConnection = { Connection(it) })
        lateinit var model: PlayViewModel
        compose.runOnIdle { model = PlayViewModel(app); models.put("effects", model) }
        compose.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) { PlayRoute(model) } } }
        compose.waitUntil(10_000) { model.state.value.items.size == 50 }
        compose.onNodeWithTag("content-list").performScrollToKey("spotify:playlist:${id(40)}")
        val anchor = "content-playlist-${id(40)}"
        val before = compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("settings-button").performClick()
        compose.onNodeWithTag("audio-effects-menu-item").performClick()
        compose.waitUntil(5_000) { app.audioEffects.state.value.isReady &&
            compose.onAllNodesWithTag("audio-effects-preamp-slider").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio-effects-enable").performClick()
        compose.onNodeWithTag("audio-effects-preamp-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(8.5f) }
        compose.runOnIdle { assertEquals(8.5f, app.audioEffects.state.value.settings.preampDb, 0f) }
        compose.onNodeWithTag("audio-effects-back").performClick()
        compose.onNodeWithTag(anchor).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInRoot.top, 1f)
        val prefs = context.getSharedPreferences(AudioEffectsStore.PREFERENCES, Context.MODE_PRIVATE)
        compose.waitUntil(5_000) { prefs.getString("audio_effects_state", null)?.let {
            JSONObject(it).getJSONObject("settings").getDouble("preampDb") == 8.5
        } == true }
        compose.onNodeWithTag("settings-button").performClick()
        compose.onNodeWithTag("audio-effects-menu-item").performClick()
        compose.onNodeWithTag("audio-effects-preamp-slider").assertIsDisplayed()
        assertEquals(8.5f, app.audioEffects.state.value.settings.preampDb, 0f)
    }

    private class Connection(private val uri: URI) : HttpURLConnection(uri.toURL()) {
        private val response by lazy {
            if (uri.path.endsWith("/rootlist")) fieldBytes(1, byteArrayOf(1)) + fieldBytes(5,
                fieldVarint(1, 0) + fieldVarint(2, 0) + (0 until 50).fold(ByteArray(0)) { bytes, index ->
                    bytes + fieldBytes(3, fieldString(1, "spotify:playlist:${id(index)}"))
                } + (0 until 50).fold(ByteArray(0)) { bytes, index ->
                    bytes + fieldBytes(4, fieldBytes(2, fieldString(1, "Entry $index")))
                }) else ByteArray(0)
        }
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    companion object { private fun id(index: Int) = index.toString().padStart(22, '0') }
}
