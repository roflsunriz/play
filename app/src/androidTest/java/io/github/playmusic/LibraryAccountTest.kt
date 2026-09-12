package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.security.SecureSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** Opt in with liveAccount=true on a test account with saved playlists, albums and tracks. */
class LibraryAccountTest {
    @Test
    fun browserAuthorizationIsSaved() {
        val session = checkNotNull(app.sessionStore.loadSession())
        val ready = !session.refreshToken.isNullOrBlank()
        Log.i(TAG, "content authorization saved=$ready")
        Log.i(TAG, "refresh placeholder=${session.refreshToken == "null"} expired=${session.expiresSoon()}")
        assertTrue("A completed normal login must be saved", ready)
    }

    @Test
    fun consecutiveRefreshesUseTheSavedCredentials(): Unit = runBlocking {
        val before = app.sessionStore.loadSession()?.refreshToken
        repeat(2) {
            assertTrue(app.sessionManager.accessToken(forceRefresh = true).isNotBlank())
            assertTrue(checkNotNull(app.sessionStore.loadSession()).expiresSoon() == false)
        }
        Log.i(TAG, "consecutive refreshes completed, rotation=${before != app.sessionStore.loadSession()?.refreshToken}")
    }

    @Test
    fun expiredSavedAuthorizationRenewsWithoutOpeningLogin(): Unit = runBlocking {
        val session = checkNotNull(app.sessionStore.loadSession())
        app.sessionStore.saveSession(session.copy(expiresAtEpochMs = 0))
        assertTrue(app.sessionManager.accessToken().isNotBlank())
        assertTrue(!checkNotNull(app.sessionStore.loadSession()).expiresSoon())
        Log.i(TAG, "expired saved authorization renewed automatically")
    }

    @Test
    fun storedCredentialRefreshIsSaved(): Unit = runBlocking {
        assertTrue("Refresh must return a token", app.sessionManager.accessToken(forceRefresh = true).isNotBlank())
        val saved = checkNotNull(SecureSessionStore(context).loadSession())
        assertTrue("Refreshed session must be persisted", !saved.expiresSoon())
        assertTrue("Refresh credentials must be retained",
            !saved.refreshToken.isNullOrBlank() || saved.storedCredential?.isNotEmpty() == true)
        Log.i(TAG, "verified authentication refresh and saved session")
    }

    @Test
    fun playlistsLoadFromTheSavedSession(): Unit = runBlocking {
        verifyLibrary(ContentKind.PLAYLIST)
        if (InstrumentationRegistry.getArguments().getString("recordCapture") == "true") {
            val api = SpotifyApiClient(app.sessionManager)
            val username = java.net.URLEncoder.encode(app.sessionManager.username(), "UTF-8")
            val response = api.get("/playlist/v2/user/$username/rootlist", query = mapOf(
                "decorate" to "revision,attributes,length,owner,capabilities,status_code,timestamp",
                "from" to "0", "length" to "120",
            ), acceptProto = true)
            val directory = context.filesDir.resolve("verification-captures").apply { mkdirs() }
            directory.resolve("rootlist.pb").writeBytes(response.bodyBytes)
        }
    }

    @Test
    fun playlistDetailsReturnTheMusicItems(): Unit = runBlocking {
        val playlists = app.repository.library(ContentKind.PLAYLIST)
        val details = playlists.take(3).map { app.repository.detail(it) }
        val populated = details.firstOrNull { it.tracks.isNotEmpty() }
        assertTrue("This test account must contain a playlist with music", populated != null)
        val detail = checkNotNull(populated)
        assertTrue("Playlist tracks need metadata", detail.tracks.all { it.title.isNotBlank() && it.durationMs > 0 })
        Log.i(TAG, "verified playlist details tracks=${detail.tracks.size} total=${detail.totalTracks}")
    }

    @Test
    fun albumsLoadFromTheSavedSession(): Unit = runBlocking { verifyLibrary(ContentKind.ALBUM) }

    @Test
    fun tracksLoadFromTheSavedSession(): Unit = runBlocking { verifyLibrary(ContentKind.TRACK) }

    @Test
    fun searchReturnsContent(): Unit = runBlocking {
        val items = app.repository.search("Nirvana")
        assertTrue("Search must return content", items.isNotEmpty())
        assertTrue("Search must return titled content", items.all { it.title.isNotBlank() })
        assertTrue("Search must include albums and tracks", items.map { it.kind }.containsAll(listOf(ContentKind.ALBUM, ContentKind.TRACK)))
        Log.i(TAG, "verified search count=${items.size}")
    }

    private suspend fun verifyLibrary(kind: ContentKind) {
        val items = app.repository.library(kind)
        assertTrue("This test account must have saved content for the requested section", items.isNotEmpty())
        assertTrue("Content kinds must match the selected section", items.all { it.kind == kind })
        assertTrue("Library URIs must be unique", items.map { it.uri }.distinct().size == items.size)
        Log.i(TAG, "verified library kind=$kind count=${items.size}")
    }

    companion object {
        private const val TAG = "PlayLibraryCheck"
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var app: AppContainer

        @BeforeClass
        @JvmStatic
        fun requireSignedInTestAccount() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
            app = (context.applicationContext as PlayApplication).container
            check(app.sessionStore.loadSession() != null) { "Sign in on the target device first" }
        }
    }
}
