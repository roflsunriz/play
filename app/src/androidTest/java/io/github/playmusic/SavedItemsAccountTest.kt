package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.api.SpClientProto
import io.github.playmusic.data.api.SpotifyApiClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires separate permission to add and then remove one previously unsaved track and album. */
class SavedItemsAccountTest {
    @Test fun nativeFavoritesRoundTripPreservesExistingCollection(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSavedItems") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val repository = app.repository
        val journal = context.getSharedPreferences("saved_items_account_test", 0)
        check(!journal.contains("pending_uri")) { "Previous saved-item verification needs cleanup" }
        val api = SpotifyApiClient(app.sessionManager)
        suspend fun originalUris(): Set<String> {
            val uris = linkedSetOf<String>()
            var token: String? = null
            val seen = mutableSetOf<String>()
            do {
                val reply = api.postProto("/collection/v2/paging", SpClientProto.buildCollectionPageRequest(
                    app.sessionManager.username(), "collection", 200, token), "application/vnd.collection-v2.spotify.proto")
                val page = SpClientProto.parseCollectionPage(reply.bodyBytes)
                for (item in page.items) if (item.removed) uris.remove(item.uri) else uris.add(item.uri)
                token = page.nextPageToken
                check(token == null || seen.add(token))
            } while (token != null)
            return uris
        }
        val original = originalUris()
        for (kind in listOf(ContentKind.TRACK, ContentKind.ALBUM)) {
            val filter = if (kind == ContentKind.TRACK) SearchFilter.TRACKS else SearchFilter.ALBUMS
            val item = repository.search("jazz", filter).first { it.uri !in original }
            assertFalse(repository.isSaved(item, forceRefresh = true))
            check(journal.edit().putString("pending_uri", item.uri).commit())
            var primary: Throwable? = null
            try {
                repository.setSaved(item, true)
                assertTrue(repository.isSaved(item, forceRefresh = true))
                assertTrue(repository.library(kind, forceRefresh = true).any { it.uri == item.uri })
                if (kind == ContentKind.TRACK) assertTrue(repository.detail(
                    io.github.playmusic.data.api.SpotifyRepository.likedSongsContent("Verification"), true).tracks.any { it.uri == item.uri })
                Log.i("PlaySavedCheck", "native add verified kind=$kind")
            } catch (error: Throwable) { primary = error; throw error }
            finally {
                withContext(NonCancellable) {
                    try {
                        repository.setSaved(item, false)
                        assertFalse(repository.isSaved(item, forceRefresh = true))
                        assertEquals("Existing saved items must be preserved", original, originalUris())
                        check(journal.edit().remove("pending_uri").commit())
                        Log.i("PlaySavedCheck", "temporary saved item removed; original collection preserved kind=$kind")
                    } catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
                }
            }
        }
    }
}
