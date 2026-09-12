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
    fun storedCredentialRefreshIsSaved(): Unit = runBlocking {
        assertTrue("Refresh must return a token", app.sessionManager.accessToken(forceRefresh = true).isNotBlank())
        val saved = checkNotNull(SecureSessionStore(context).loadSession())
        assertTrue("Refreshed session must be persisted", !saved.expiresSoon())
        assertTrue("Stored credential must be retained", saved.storedCredential?.isNotEmpty() == true)
        Log.i(TAG, "verified stored-credential refresh and saved session")
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
    fun albumsLoadFromTheSavedSession(): Unit = runBlocking { verifyLibrary(ContentKind.ALBUM) }

    @Test
    fun tracksLoadFromTheSavedSession(): Unit = runBlocking { verifyLibrary(ContentKind.TRACK) }

    @Test
    fun searchReturnsContent(): Unit = runBlocking {
        val items = app.repository.search("Nirvana")
        assertTrue("Search must return content", items.isNotEmpty())
        assertTrue("Search must return titled content", items.all { it.title.isNotBlank() })
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
            app = AppContainer(context)
            check(app.sessionStore.loadSession() != null) { "Sign in on the target device first" }
        }
    }
}
