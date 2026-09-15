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
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.cache.PlaylistCacheEntry
import io.github.playmusic.data.cache.PlaylistCacheSnapshot
import io.github.playmusic.data.cache.PlaylistDiskCache
import io.github.playmusic.data.api.SpotifyRepository
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated preferences and real SQLite, with a controllable service response. */
class PlaylistStartupCacheTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "playlist_startup_${java.util.UUID.randomUUID()}"
    private val directory = File(base.cacheDir, name)
    private val models = ViewModelStore()
    private val gate = CountDownLatch(1)
    private lateinit var app: AppContainer
    private lateinit var model: PlayViewModel
    private val keep = item("keep", "Saved title")
    private val gone = item("gone", "Saved second playlist")
    private val initial = listOf(keep, gone)
    private val profileCalls = AtomicInteger()
    private val rootCalls = AtomicInteger()
    @Volatile private var failed = false
    @Volatile private var emptyResponse = false
    @Volatile private var inconsistentPage = false
    @Volatile private var profileName = "Saved creator"
    @Volatile private var source = initial
    @Volatile private var sawSavedItemsBeforeResponse = false
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        override fun getCacheDir(): File = directory.apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
    }

    @After fun cleanup() {
        gate.countDown()
        composeRule.runOnIdle { models.clear() }
        if (::app.isInitialized) {
            runBlocking { app.localPlayback.release() }
            app.playlistDiskCache.close()
        }
        base.deleteSharedPreferences(name)
        check(directory.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        directory.deleteRecursively()
    }

    @Test fun aNewProcessShowsDiskItemsBeforeNetworkingAndReusesTheirOwnerNames() {
        start()
        composeRule.waitUntil(5_000) { model.state.value.items == displayed(initial) && rootCalls.get() > 0 }
        assertLikedSongsFirstAndUnique(model.state.value.items)
        composeRule.onNodeWithTag("content-playlist-tracks").assertIsDisplayed()
        composeRule.onNodeWithTag("content-playlist-${keep.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("loading-indicator").assertDoesNotExist()
        assertTrue(sawSavedItemsBeforeResponse)
        assertFalse(model.state.value.isLoading)
        captureScreen(composeRule.onRoot(), "playlist-disk-before-network")
        gate.countDown()
        composeRule.waitUntil(5_000) { runBlocking { app.playlistDiskCache.read(USER).snapshot?.entries?.first()?.fingerprint != "seed" } }
        assertEquals(0, profileCalls.get())
        assertEquals(displayed(initial), model.state.value.items)
        assertLikedSongsFirstAndUnique(model.state.value.items)
        assertEquals(initial, runBlocking { app.repository.cachedPlaylists() })
        assertRootlistDiskExcludesLikedSongs(initial)
    }

    @Test fun failedSyncKeepsDiskAndUiThenRetryAppliesAdditionDeletionAndMetadataChanges() {
        failed = true
        start()
        composeRule.waitUntil(5_000) { model.state.value.items == displayed(initial) }
        gate.countDown()
        composeRule.waitUntil(5_000) { model.state.value.playlistSyncFailed }
        assertNull(model.state.value.error)
        assertFalse(model.state.value.isLoading)
        assertEquals(initial, runBlocking { app.repository.cachedPlaylists() })
        composeRule.onNodeWithTag("content-playlist-${keep.id}").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "playlist-disk-offline")
        source = listOf(keep.copy(title = "Updated title"), item("new", "Added playlist"))
        failed = false
        composeRule.runOnIdle { model.selectSection(LibrarySection.ALBUMS); model.retryPlaylistSync() }
        composeRule.waitUntil(5_000) { model.state.value.libraries[LibrarySection.PLAYLISTS] == displayed(source) && !model.state.value.playlistSyncFailed }
        assertEquals(LibrarySection.ALBUMS, model.state.value.selectedSection)
        composeRule.runOnIdle { model.selectSection(LibrarySection.PLAYLISTS) }
        assertEquals(source, runBlocking { app.repository.cachedPlaylists() })
        assertLikedSongsFirstAndUnique(model.state.value.items)
        assertRootlistDiskExcludesLikedSongs(source)
        composeRule.onNodeWithTag("content-playlist-${gone.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("content-playlist-${source.last().id}").assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "playlist-disk-updated")
    }

    @Test fun logoutClearsTheSavedListAndAnOlderResponseCannotRestoreIt() {
        start()
        composeRule.waitUntil(5_000) { model.state.value.items == displayed(initial) && rootCalls.get() > 0 }
        composeRule.runOnIdle { model.logout() }
        gate.countDown()
        composeRule.waitUntil(5_000) { runBlocking { app.playlistDiskCache.read(USER).snapshot == null } }
        assertFalse(model.state.value.isLoggedIn)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(SecureSessionStore(context).loadSession())
        assertNull(runBlocking { app.playlistDiskCache.read(USER).snapshot })
    }

    @Test fun emptyOrInconsistentSuccessfulResponsesCannotEraseTheSavedLibrary() {
        emptyResponse = true
        start()
        gate.countDown()
        composeRule.waitUntil(5_000) { model.state.value.playlistSyncFailed }
        assertEquals(initial, runBlocking { app.repository.cachedPlaylists() })
        emptyResponse = false
        inconsistentPage = true
        composeRule.runOnIdle { model.retryPlaylistSync() }
        composeRule.waitUntil(5_000) { rootCalls.get() >= 2 && model.state.value.playlistSyncFailed }
        assertEquals(initial, runBlocking { app.repository.cachedPlaylists() })
        assertEquals(displayed(initial), model.state.value.items)
    }

    @Test fun aMissingOwnerTimestampRequiresARealProfileCheck() {
        start()
        gate.countDown()
        composeRule.waitUntil(5_000) { runBlocking { app.playlistDiskCache.read(USER).snapshot?.entries?.first()?.fingerprint != "seed" } }
        assertEquals(0, profileCalls.get())
        runBlocking { app.playlistDiskCache.removeItem(USER, gone.uri); app.playlistDiskCache.updateItem(USER, keep) }
        source = listOf(keep)
        profileName = "Updated creator"
        val refreshed = runBlocking { app.repository.library(ContentKind.PLAYLIST, forceRefresh = true, refreshOwnerNames = false) }
        assertEquals("Updated creator", refreshed.single().ownerName)
        assertEquals(1, profileCalls.get())
        assertTrue(runBlocking { app.playlistDiskCache.read(USER).snapshot!!.entries.single().ownerCheckedAtMs > 0 })
    }

    @Test fun repeatedSynchronizationKeepsOneLikedSongsEntryOutsideTheRootlistDiskCache() {
        start()
        composeRule.waitUntil(5_000) { model.state.value.items == displayed(initial) && rootCalls.get() > 0 }
        assertLikedSongsFirstAndUnique(model.state.value.items)
        gate.countDown()
        composeRule.waitUntil(5_000) {
            runBlocking { app.playlistDiskCache.read(USER).snapshot?.entries?.first()?.fingerprint != "seed" }
        }
        repeat(2) { pass ->
            source = listOf(keep.copy(title = "Synchronized $pass"))
            composeRule.runOnIdle { model.retryPlaylistSync() }
            composeRule.waitUntil(5_000) { model.state.value.items == displayed(source) }
            assertLikedSongsFirstAndUnique(model.state.value.items)
            assertLikedSongsFirstAndUnique(checkNotNull(model.state.value.libraries[LibrarySection.PLAYLISTS]))
            assertRootlistDiskExcludesLikedSongs(source)
        }
    }

    private fun displayed(rows: List<SpotifyContent>) = listOf(SpotifyRepository.likedSongsContent(base.getString(R.string.liked_songs))) + rows

    private fun assertLikedSongsFirstAndUnique(rows: List<SpotifyContent>) {
        assertEquals(SpotifyRepository.LIKED_SONGS_URI, rows.first().uri)
        assertEquals(1, rows.count(SpotifyRepository::isLikedSongs))
    }

    private fun assertRootlistDiskExcludesLikedSongs(expected: List<SpotifyContent>) {
        val rows = runBlocking { app.playlistDiskCache.read(USER).snapshot!!.entries.map { it.content } }
        assertEquals(expected, rows)
        assertFalse(rows.any(SpotifyRepository::isLikedSongs))
    }

    private fun start() {
        SecureSessionStore(context).saveSession(AuthSession(USER, "synthetic-access", null, Long.MAX_VALUE, "synthetic-refresh"))
        // Close the seeding store so bootstrap cannot rely on its memory or an open database handle.
        val seed = PlaylistDiskCache(context)
        runBlocking {
            val read = seed.read(USER)
            check(seed.reconcile(USER, read.generation, PlaylistCacheSnapshot(
                initial.map { PlaylistCacheEntry(it, "seed", System.currentTimeMillis()) }, System.currentTimeMillis())))
        }
        seed.close()
        app = AppContainer(context, apiConnection = { Connection(it, ::reply) })
        composeRule.runOnIdle { model = PlayViewModel(app); models.put("startup", model) }
        composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) { PlayRoute(model) } } }
    }

    private fun reply(uri: URI): Pair<Int, ByteArray> = when {
        uri.path.endsWith("/rootlist") -> {
            rootCalls.incrementAndGet()
            sawSavedItemsBeforeResponse = model.state.value.items == displayed(initial)
            check(gate.await(20, TimeUnit.SECONDS))
            if (failed) 503 to ByteArray(0) else if (emptyResponse) 200 to ByteArray(0) else 200 to rootlist(source)
        }
        uri.path == "/collection/v2/paging" -> 200 to fieldString(3, "synthetic-sync")
        uri.path.startsWith("/user-profile-view/") -> {
            profileCalls.incrementAndGet()
            200 to (fieldString(1, "spotify:user:$USER") + fieldString(2, profileName))
        }
        else -> 404 to ByteArray(0)
    }

    private fun rootlist(rows: List<SpotifyContent>): ByteArray = fieldBytes(1, byteArrayOf(1)) + fieldVarint(2, rows.size.toLong()) +
        fieldBytes(5, fieldVarint(2, if (inconsistentPage) 1 else 0) +
            rows.fold(ByteArray(0)) { bytes, row -> bytes + fieldBytes(3, fieldString(1, row.uri)) } +
            rows.fold(ByteArray(0)) { bytes, row -> bytes + fieldBytes(4,
                fieldBytes(2, fieldString(1, row.title)) + fieldVarint(3, 0) + fieldString(5, USER) + fieldVarint(9, 400)) })

    private fun item(id: String, title: String) = SpotifyContent(id.padStart(22, '0'), "spotify:playlist:${id.padStart(22, '0')}", title, "Saved creator", null,
        ContentKind.PLAYLIST, ownerName = "Saved creator", ownerUsername = USER, description = "", trackCount = 0)

    private class Connection(private val uri: URI, private val reply: (URI) -> Pair<Int, ByteArray>) : HttpURLConnection(uri.toURL()) {
        private val response by lazy { reply(uri) }
        override fun getResponseCode() = response.first
        override fun getInputStream() = ByteArrayInputStream(response.second)
        override fun getErrorStream() = ByteArrayInputStream(response.second)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private companion object { const val USER = "synthetic-disk-user" }
}
