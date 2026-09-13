package io.github.playmusic

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.LibrarySort
import io.github.playmusic.ui.PlayViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LibraryBrowsingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val name = "browsing_test_${java.util.UUID.randomUUID()}"
    private val cache = File(base.cacheDir, name)
    private val models = ViewModelStore()
    private lateinit var app: AppContainer
    private val requests = AtomicInteger()
    private val searchRequests = ConcurrentHashMap<String, AtomicInteger>()
    private val oldStarted = CountDownLatch(1)
    private val releaseOld = CountDownLatch(1)
    @Volatile private var watchMainSessionReads = false
    @Volatile private var watchAfterRootlist = false
    private val mainSessionReads = AtomicInteger()
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences {
            val preferences = base.getSharedPreferences(name, mode)
            return object : SharedPreferences by preferences {
                override fun getString(key: String?, default: String?): String? {
                    if (watchMainSessionReads && Looper.myLooper() == Looper.getMainLooper()) mainSessionReads.incrementAndGet()
                    return preferences.getString(key, default)
                }
            }
        }
        override fun getCacheDir(): File = cache.apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(cache, "no-backup").apply { mkdirs() }
    }

    @After fun cleanup() {
        releaseOld.countDown()
        instrumentation.runOnMainSync { models.clear() }
        if (::app.isInitialized) runBlocking { app.localPlayback.release() }
        if (::app.isInitialized) app.playlistDiskCache.close()
        base.deleteSharedPreferences(name)
        check(cache.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        cache.deleteRecursively()
    }

    @Test fun allTabsPrefetchAndSwitchWithoutRequestsOrLosingFilterAndSort() {
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        val before = requests.get()
        instrumentation.runOnMainSync {
            model.updateLibraryQuery("Alpha")
            model.updateLibrarySort(LibrarySort.TITLE_DESCENDING)
            assertEquals(listOf("Alpha"), model.state.value.items.map { it.title })
            model.selectSection(LibrarySection.ALBUMS)
            assertFalse(model.state.value.isLoading)
            model.updateLibraryQuery("Album artist")
            model.updateLibrarySort(LibrarySort.CREATOR)
            model.selectSection(LibrarySection.TRACKS)
            assertFalse(model.state.value.isLoading)
            model.updateLibraryQuery("Track artist")
            model.updateLibrarySort(LibrarySort.DURATION)
            model.selectSection(LibrarySection.PLAYLISTS)
            assertEquals("Alpha", model.state.value.libraryQuery)
            assertEquals(LibrarySort.TITLE_DESCENDING, model.state.value.playlistSort)
            assertEquals(listOf("Alpha"), model.state.value.items.map { it.title })
            assertFalse(model.state.value.isLoading)
            assertTrue(model.state.value.suggestedItems.isNotEmpty())
            model.selectSection(LibrarySection.ALBUMS)
            assertEquals("Album artist", model.state.value.albumQuery)
            assertEquals(LibrarySort.CREATOR, model.state.value.albumSort)
            model.selectSection(LibrarySection.TRACKS)
            assertEquals("Track artist", model.state.value.trackQuery)
            assertEquals(LibrarySort.DURATION, model.state.value.trackSort)
            model.selectSection(LibrarySection.PLAYLISTS)
        }
        assertEquals(before, requests.get())
        instrumentation.runOnMainSync { model.refreshAll() }
        await { requests.get() > before && !model.state.value.isLoading }
        assertEquals("Alpha", model.state.value.libraryQuery)
        instrumentation.runOnMainSync { model.logout() }
        assertTrue(model.state.value.libraries.isEmpty())
        assertTrue(model.state.value.suggestedItems.isEmpty())
        assertEquals(null, app.repository.peekLibrary(io.github.playmusic.data.model.ContentKind.PLAYLIST))
    }

    @Test fun prefetchDoesNotReadCredentialsOnMainOrRewriteTheVisibleLibrary() {
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        val items = model.state.value.items
        watchMainSessionReads = true
        instrumentation.runOnMainSync { model.prefetchDetails(items) }
        await { app.repository.peekDetail(items.first()) != null }
        instrumentation.runOnMainSync { model.prefetchDetails(items) }
        assertEquals(0, mainSessionReads.get())
        assertEquals(items, model.state.value.items)
        watchMainSessionReads = false
    }

    @Test fun warmingOtherTabsDoesNotReadCredentialsOnMain() {
        watchAfterRootlist = true
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        assertEquals(0, mainSessionReads.get())
    }

    @Test fun openingAPrefetchedDetailDoesNotReadCredentialsOnMainOrRequestItAgain() {
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        val item = model.state.value.items.first()
        runBlocking { app.repository.detail(item) }
        val before = requests.get()
        watchMainSessionReads = true
        instrumentation.runOnMainSync { model.openDetail(item) }
        await { model.state.value.detail != null && !model.state.value.isLoading }
        assertEquals(0, mainSessionReads.get())
        assertEquals(before, requests.get())
        assertEquals(item.uri, model.state.value.detail?.content?.uri)
    }

    @Test fun typingShowsLocalSuggestionsAndDebouncesRemoteResults() {
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        instrumentation.runOnMainSync {
            model.selectSection(LibrarySection.SEARCH)
            model.updateSearchQuery("Al")
            model.updateSearchQuery("Alpha")
            assertEquals(listOf("Alpha"), model.state.value.searchSuggestions.map { it.title })
        }
        await { model.state.value.searchResults.size == 3 }
        assertEquals(null, searchRequests["Al"])
        assertEquals(3, searchRequests["Alpha"]?.get())
        instrumentation.runOnMainSync {
            model.selectSection(LibrarySection.ALBUMS)
            model.selectSection(LibrarySection.SEARCH)
            assertEquals(3, model.state.value.items.size)
            assertFalse(model.state.value.isLoading)
            model.updateSearchQuery("")
            assertTrue(model.state.value.items.isEmpty())
            assertTrue(model.state.value.searchSuggestions.isEmpty())
        }
        assertEquals(3, searchRequests["Alpha"]?.get())
    }

    @Test fun aSlowEarlierQueryCannotReplaceNewerSearchResults() {
        val model = createModel()
        await { model.state.value.libraries.size == 3 }
        instrumentation.runOnMainSync { model.selectSection(LibrarySection.SEARCH); model.updateSearchQuery("old") }
        assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { model.updateSearchQuery("new") }
        await { model.state.value.searchResults.size == 3 }
        assertTrue(model.state.value.searchResults.all { it.title == "new" })
        releaseOld.countDown()
        instrumentation.waitForIdleSync()
        assertTrue(model.state.value.searchResults.all { it.title == "new" })
        assertEquals("new", model.state.value.searchQuery)
    }

    private fun createModel(): PlayViewModel {
        SecureSessionStore(context).saveSession(AuthSession("synthetic-browser", "synthetic-access", null, Long.MAX_VALUE, "synthetic-refresh"))
        app = AppContainer(context, apiConnection = { uri -> Connection(uri) { body ->
            requests.incrementAndGet()
            when {
                uri.path.endsWith("/rootlist") -> {
                    if (watchAfterRootlist) watchMainSessionReads = true
                    val rows = listOf("0000000000000000000002" to "Beta", "0000000000000000000001" to "Alpha")
                    fieldBytes(1, byteArrayOf(1)) + fieldBytes(5, fieldVarint(1, 0) + fieldVarint(2, 0) +
                        rows.fold(ByteArray(0)) { bytes, (id, _) -> bytes + fieldBytes(3, fieldString(1, "spotify:playlist:$id")) } +
                        rows.fold(ByteArray(0)) { bytes, (_, title) -> bytes + fieldBytes(4, fieldBytes(2, fieldString(1, title))) })
                }
                uri.path == "/collection/v2/paging" -> ByteArray(0)
                uri.path.startsWith("/playlist/v2/playlist/") -> fieldBytes(3, fieldString(1, "Prefetched detail title")) + fieldVarint(2, 0)
                uri.path == "/pathfinder/v2/query" -> {
                    val json = JSONObject(body.toString(Charsets.UTF_8))
                    val query = json.getJSONObject("variables").getString("searchTerm")
                    searchRequests.computeIfAbsent(query) { AtomicInteger() }.incrementAndGet()
                    if (query == "old") { oldStarted.countDown(); check(releaseOld.await(8, TimeUnit.SECONDS)) }
                    val (key, kind) = when (json.getString("operationName")) {
                        "searchAlbums" -> "albumsV2" to "album"
                        "searchTracks" -> "tracksV2" to "track"
                        "searchPlaylists" -> "playlists" to "playlist"
                        else -> error("Unexpected test request")
                    }
                    """{"data":{"searchV2":{"$key":{"items":[{"item":{"data":{"uri":"spotify:$kind:$query","name":"$query"}}}]}}}}""".toByteArray()
                }
                else -> error("Unexpected test request: ${uri.path}")
            }
        } })
        lateinit var model: PlayViewModel
        instrumentation.runOnMainSync { model = PlayViewModel(app); models.put("browsing", model) }
        return model
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for browsing state", predicate())
    }

    private class Connection(uri: URI, private val reply: (ByteArray) -> ByteArray) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val response by lazy { reply(output.toByteArray()) }
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
