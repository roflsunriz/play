package io.github.playmusic

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.PlaylistEditorFailure
import io.github.playmusic.ui.theme.PlayTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses synthetic sessions and in-memory native responses; never mutates a real account. */
class PlaylistViewModelTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "playlist_ui_test_${UUID.randomUUID()}"
    private val cache = File(base.cacheDir, name)
    private val store = ViewModelStore()
    private var container: AppContainer? = null
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(name, Context.MODE_PRIVATE)
        override fun getCacheDir(): File = cache.apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(cache, "no-backup").apply { mkdirs() }
    }

    @After fun cleanup() {
        composeRule.runOnIdle { store.clear() }
        runBlocking { container?.localPlayback?.release() }
        container?.playlistDiskCache?.close()
        base.deleteSharedPreferences(name)
        check(cache.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        cache.deleteRecursively()
    }

    @Test fun partialCreationRetriesTheSamePlaylistAndDeletionRequiresConfirmation() {
        val server = Server().apply { failPermission = true }
        val model = createModel(server)
        composeRule.onNodeWithTag("create-playlist-button").performClick()
        composeRule.runOnIdle {
            model.updatePlaylistName("New playlist")
            model.updatePlaylistDescription("Saved description")
            model.savePlaylist()
            model.savePlaylist()
        }
        composeRule.waitUntil(5_000) { model.state.value.playlistEditor?.failure != null }
        composeRule.runOnIdle {
            val editor = checkNotNull(model.state.value.playlistEditor)
            assertEquals(PLAYLIST.uri, editor.content?.uri)
            assertEquals("New playlist", editor.name)
            assertEquals("Saved description", editor.description)
            assertEquals(PlaylistEditorFailure.PARTIAL_SAVE, editor.failure)
            assertTrue(editor.creationNeedsCompletion)
            assertEquals(1, server.creates.get())
            server.failPermission = false
        }
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { model.state.value.detail?.playlistMetadata != null }
        composeRule.runOnIdle {
            assertNull(model.state.value.playlistEditor)
            assertEquals("New playlist", model.state.value.detail?.content?.title)
            assertEquals("Saved description", model.state.value.detail?.playlistMetadata?.description)
            assertEquals(1, server.creates.get())
            assertEquals(1, server.additions.get())
        }
        composeRule.onNodeWithTag("delete-playlist-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("playlist-delete-cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, server.removals.get()); assertNotNull(model.state.value.detail) }
        composeRule.onNodeWithTag("delete-playlist-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("playlist-delete-confirm").performClick()
        composeRule.waitUntil(5_000) { model.state.value.playlistToDelete == null && model.state.value.selectedContent == null }
        composeRule.runOnIdle { assertEquals(1, server.removals.get()); assertTrue(model.state.value.items.isEmpty()) }
    }

    @Test fun failedEditKeepsDraftAndRetryUpdatesTheExistingDetail() {
        val server = Server().apply { created = true; inLibrary = true; failChanges = true }
        val model = createModel(server)
        composeRule.runOnIdle { model.openDetail(PLAYLIST) }
        composeRule.waitUntil(5_000) { model.state.value.detail?.playlistMetadata != null }
        composeRule.onNodeWithTag("edit-playlist-button").performScrollTo().performClick()
        composeRule.runOnIdle {
            model.updatePlaylistName("Changed name")
            model.updatePlaylistDescription("")
            model.savePlaylist()
            model.updateSearchQuery("Concurrent state update")
        }
        composeRule.waitUntil(5_000) { model.state.value.playlistEditor?.failure != null }
        composeRule.onNodeWithTag("playlist-editor-error").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("Original", model.state.value.detail?.content?.title)
            assertEquals("Changed name", model.state.value.playlistEditor?.name)
            assertEquals("", model.state.value.playlistEditor?.description)
            assertEquals("Concurrent state update", model.state.value.searchQuery)
            server.failChanges = false
        }
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { model.state.value.detail?.content?.title == "Changed name" }
        composeRule.runOnIdle {
            assertNull(model.state.value.playlistEditor)
            assertEquals("", model.state.value.detail?.playlistMetadata?.description)
            assertEquals("Concurrent state update", model.state.value.searchQuery)
            assertEquals(0, server.creates.get())
        }
    }

    private fun createModel(server: Server): PlayViewModel {
        SecureSessionStore(context).saveSession(AuthSession(USER, "synthetic-access-token", null, Long.MAX_VALUE,
            refreshToken = "synthetic-refresh-token"))
        val app = AppContainer(context, apiConnection = { Connection(it, server::reply) })
        container = app
        lateinit var model: PlayViewModel
        composeRule.runOnIdle { model = PlayViewModel(app); store.put("playlist", model) }
        composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) { PlayRoute(model) } } }
        composeRule.waitUntil(5_000) { !model.state.value.isLoading }
        return model
    }

    private class Server {
        val creates = AtomicInteger()
        val additions = AtomicInteger()
        val removals = AtomicInteger()
        @Volatile var failPermission = false
        @Volatile var failChanges = false
        @Volatile var created = false
        @Volatile var inLibrary = false
        private var permission = "VIEWER"
        private var title = "Original"
        private var description = "Original description"

        @Synchronized fun reply(request: Connection): Reply {
            val path = request.url.path
            return when {
                path == "/user-profile-view/v3/profile/$USER" -> Reply(bytes =
                    fieldString(1, "spotify:user:$USER") + fieldString(2, "Test creator"))
                path == "/playlist/v2/playlist" -> {
                    creates.incrementAndGet()
                    created = true
                    applyMetadata(fields(request.body).bytes(1))
                    Reply(bytes = fieldString(1, PLAYLIST.uri))
                }
                path.endsWith("permission/base") -> {
                    if (request.requestMethod == "POST") {
                        if (failPermission) return Reply(403)
                        permission = JSONObject(request.body.toString(Charsets.UTF_8)).getString("permissionLevel")
                    }
                    Reply(bytes = """{"permissionLevel":"$permission","revision":"AQID"}""".toByteArray())
                }
                path.endsWith("rootlist/changes") -> {
                    val operation = fields(fields(request.body).bytes(2)).bytes(2)
                    when (fields(operation).number(1)) {
                        2L -> { check(permission == "BLOCKED"); inLibrary = true; additions.incrementAndGet() }
                        3L -> { inLibrary = false; removals.incrementAndGet() }
                        else -> error("Unexpected rootlist operation")
                    }
                    Reply()
                }
                path.endsWith("/rootlist") -> Reply(bytes = fieldBytes(1, byteArrayOf(1)) + fieldBytes(5, fieldVarint(1, 0) + fieldVarint(2, 0) +
                    if (inLibrary) fieldBytes(3, fieldString(1, PLAYLIST.uri)) +
                        fieldBytes(4, fieldBytes(2, fieldString(1, title)) + fieldVarint(9, 200)) else byteArrayOf()))
                path.endsWith("/changes") -> {
                    if (failChanges) return Reply(503)
                    applyMetadata(fields(fields(request.body).bytes(2)).bytes(2))
                    Reply()
                }
                path == "/playlist/v2/playlist/${PLAYLIST.id}" -> {
                    check(created)
                    Reply(bytes = fieldBytes(3, fieldString(1, title) + fieldString(2, description)) +
                        fieldString(16, USER) + fieldBytes(18, fieldVarint(4, 1) + fieldVarint(13, 1)))
                }
                else -> error("Unexpected synthetic request path: $path")
            }
        }

        private fun applyMetadata(operation: ByteArray) {
            val partial = fields(fields(fields(operation).bytes(6)).bytes(1))
            val attributes = fields(partial.bytes(1))
            attributes.firstOrNull { it.id == 1 }?.bytes?.let { title = it.toString(Charsets.UTF_8) }
            attributes.firstOrNull { it.id == 2 }?.bytes?.let { description = it.toString(Charsets.UTF_8) }
            if (partial.any { it.id == 2 && it.value == 2L }) description = ""
        }
    }

    private data class Reply(val status: Int = 200, val bytes: ByteArray = byteArrayOf())
    private class Connection(uri: URI, private val handler: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val response by lazy { handler(this) }
        val body get() = output.toByteArray()
        override fun getOutputStream() = output
        override fun getResponseCode() = response.status
        override fun getInputStream() = ByteArrayInputStream(response.bytes)
        override fun getErrorStream() = ByteArrayInputStream(response.bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private data class Field(val id: Int, val bytes: ByteArray? = null, val value: Long? = null)
    private companion object {
        const val USER = "synthetic-user"
        val PLAYLIST = SpotifyContent("0123456789ABCDEFGHIJKL", "spotify:playlist:0123456789ABCDEFGHIJKL",
            "Original", "", null, ContentKind.PLAYLIST)
        fun List<Field>.bytes(id: Int) = checkNotNull(single { it.id == id }.bytes)
        fun List<Field>.number(id: Int) = checkNotNull(single { it.id == id }.value)
        fun fields(bytes: ByteArray): List<Field> {
            val reader = ProtoWire.Reader(bytes)
            val fields = mutableListOf<Field>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.wireType(tag)) {
                    0 -> fields += Field(reader.fieldNumber(tag), value = reader.readVarint())
                    2 -> fields += Field(reader.fieldNumber(tag), bytes = reader.readBytes())
                    else -> error("Unexpected synthetic wire type")
                }
            }
            return fields
        }
    }
}
