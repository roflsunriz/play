package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.SpClientProto
import io.github.playmusic.data.api.ServiceApiClient
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/** Explicitly authorized round trip of one previously unfollowed artist; existing follows are read-only. */
class ArtistFollowAccountTest {
    @Test fun temporaryArtistFollowIsConfirmedAndRemovedWithoutChangingExistingFollows(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveArtistFollow") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        check(app.sessionStore.loadSession() != null) { "Sign in on the target device first" }
        val repository = app.repository
        val account = app.sessionManager.username()
        val accountHash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val journal = context.getSharedPreferences("artist_follow_account_test", 0)
        check(!journal.contains("pending_uri") && !journal.contains("account_hash")) {
            "Previous artist-follow verification needs reviewed cleanup before another write"
        }
        val api = ServiceApiClient(app.sessionManager)

        suspend fun checkAccount() = check(app.sessionManager.username() == account) {
            "Account changed; preserve the verification journal for cleanup on the original account"
        }

        suspend fun originalCollectionUris(): Set<String> {
            checkAccount()
            val uris = linkedSetOf<String>()
            var next: String? = null
            val seenPages = mutableSetOf<String>()
            do {
                val response = api.postProto("/collection/v2/paging", SpClientProto.buildCollectionPageRequest(
                    account, "collection", 200, next), "application/vnd.collection-v2.spotify.proto")
                val page = SpClientProto.parseCollectionPage(response.bodyBytes)
                for (item in page.items) if (item.removed) uris.remove(item.uri) else uris.add(item.uri)
                next = page.nextPageToken
                check(next == null || seenPages.add(next)) { "Collection pagination did not advance" }
                checkAccount()
            } while (next != null)
            return uris
        }

        suspend fun isFollowed(item: MusicContent): Boolean {
            checkAccount()
            val followed = checkNotNull(repository.detail(item, forceRefresh = true).artistPage).isFollowed
            checkAccount()
            return followed
        }

        val original = originalCollectionUris()
        val originalArtists = original.filterTo(linkedSetOf()) { it.startsWith("spotify:artist:") }
        val candidates = repository.search("jazz", SearchFilter.ARTISTS)
        var selected: MusicContent? = null
        for (candidate in candidates) {
            require(candidate.kind == ContentKind.ARTIST)
            if (candidate.uri !in original && !isFollowed(candidate)) { selected = candidate; break }
        }
        val artist = checkNotNull(selected) { "No previously unfollowed verification artist was found" }
        checkAccount()
        assertFalse("Target must still be unfollowed immediately before the write", isFollowed(artist))
        check(journal.edit().putString("pending_uri", artist.uri).putString("account_hash", accountHash).commit()) {
            "Could not save artist-follow verification journal"
        }
        var primary: Throwable? = null
        var mutationAttempted = false
        try {
            checkAccount()
            mutationAttempted = true
            assertTrue(repository.setArtistFollowed(artist, true))
            assertTrue("Fresh artist state must confirm the follow", isFollowed(artist))
            Log.i(TAG, "temporary artist follow confirmed")
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    checkAccount()
                    check(journal.getString("pending_uri", null) == artist.uri &&
                        journal.getString("account_hash", null) == accountHash) { "Verification journal changed" }
                    if (mutationAttempted) assertFalse(repository.setArtistFollowed(artist, false))
                    assertFalse("Fresh artist state must confirm the temporary follow was removed", isFollowed(artist))
                    val restored = originalCollectionUris()
                    assertFalse("Temporary artist must be absent from the original collection", artist.uri in restored)
                    assertEquals("Existing artist follows must be preserved", originalArtists,
                        restored.filterTo(linkedSetOf()) { it.startsWith("spotify:artist:") })
                    assertEquals("Existing saved collection must be preserved", original, restored)
                    check(journal.edit().remove("pending_uri").remove("account_hash").commit()) {
                        "Could not clear confirmed artist-follow verification journal"
                    }
                    Log.i(TAG, "temporary artist follow removed; existing follows and collection preserved")
                } catch (cleanup: Throwable) {
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private companion object { const val TAG = "PlayArtistFollowCheck" }
}
