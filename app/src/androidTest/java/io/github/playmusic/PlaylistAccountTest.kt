package io.github.playmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.PlaylistCreationException
import io.github.playmusic.data.api.SpClientProto
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.PlaylistMetadata
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Opt-in mutation test. It changes and deletes only the private playlist created by this test. */
class PlaylistAccountTest {
    @Test
    fun privatePlaylistCreationMetadataArtworkTracksAndDeletion(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlaylists") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        check(app.sessionStore.loadSession() != null) { "A signed-in test account is required" }
        val pending = context.getSharedPreferences("playlist_account_test", 0)
        val api = SpotifyApiClient(app.sessionManager)
        val username = app.sessionManager.username()
        // Read the raw rootlist: decorated library results can omit unavailable playlists.
        val originalUris = libraryUris(api, username)
        pending.getString("created_uri", null)?.let { previousUri ->
            check(previousUri !in originalUris) { "Clean up the previous test playlist before retrying" }
            check(pending.edit().remove("created_uri").commit()) { "Could not clear the completed test journal" }
            Log.i(TAG, "previous test playlist is absent; stale journal cleared")
        }
        val cachedBefore = app.repository.library(ContentKind.PLAYLIST).map { it.uri }.toSet()
        val initialName = "Play verification ${System.currentTimeMillis()}"
        var created: SpotifyContent? = null
        var primaryFailure: Throwable? = null
        try {
            Log.i(TAG, "stage=create private playlist")
            try {
                created = app.repository.createPlaylist(initialName, "Temporary verification playlist")
            } catch (error: PlaylistCreationException) {
                created = error.createdContent
                throw error
            } finally {
                created?.let { content ->
                    check(!originalUris.contains(content.uri)) { "Creation unexpectedly returned an existing playlist" }
                    check(pending.edit().putString("created_uri", content.uri).commit())
                }
            }
            val target = checkNotNull(created)
            assertTrue("Creation must be persisted before another sync", checkNotNull(app.repository.cachedPlaylists()).any { it.uri == target.uri })
            assertTrue(app.repository.library(ContentKind.PLAYLIST).any { it.uri == target.uri })
            val permission = api.get("/playlist-permission/v1/playlist/${target.id}/permission/base")
            assertEquals("BLOCKED", JSONObject(permission.body).getString("permissionLevel"))
            val original = app.repository.playlistMetadata(target)
            assertTrue(original.isOwned && original.canEdit && original.canDelete)
            assertEquals(initialName, original.name)

            Log.i(TAG, "stage=save name description artwork")
            val newName = "$initialName edited"
            val newDescription = "日本語の説明・English description"
            val saved = app.repository.updatePlaylistMetadata(target, newName, newDescription, imageJpeg())
            assertEquals("Metadata changes must survive a restart", saved, checkNotNull(app.repository.cachedPlaylists()).first { it.uri == target.uri })
            val imageMetadata = awaitMetadata({ app.repository.playlistMetadata(target) }) {
                it.name == newName && it.description == newDescription && !it.imageUrl.isNullOrBlank()
            }
            assertTrue(imageMetadata.imageUrl?.startsWith("https://") == true)
            verifyUploadedImage(checkNotNull(imageMetadata.imageUrl))

            Log.i(TAG, "stage=add and remove test track")
            app.repository.addPlaylistTracks(target, listOf(TRACK))
            val populated = app.repository.detail(target)
            assertEquals(listOf(TRACK), populated.tracks.map { it.uri })
            assertTrue(populated.tracks.single().title.isNotBlank())
            app.repository.removePlaylistTracks(target, listOf(TRACK))
            assertTrue(app.repository.detail(target).tracks.isEmpty())

            Log.i(TAG, "stage=clear description and custom artwork")
            val cleared = app.repository.updatePlaylistMetadata(target, newName, "", removeImage = true)
            assertEquals(cleared, checkNotNull(app.repository.cachedPlaylists()).first { it.uri == target.uri })
            awaitMetadata({ app.repository.playlistMetadata(target) }) { it.description.isEmpty() && it.imageUrl.isNullOrBlank() }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            created?.let { target ->
                if (target.uri !in originalUris) withContext(NonCancellable) {
                    try {
                        Log.i(TAG, "stage=delete own temporary playlist")
                        app.repository.deletePlaylist(target)
                        assertEquals("Deleting a test playlist must preserve the other disk entries", cachedBefore,
                            checkNotNull(app.repository.cachedPlaylists()).map { it.uri }.toSet())
                        val finalUris = libraryUris(api, username)
                        assertFalse(finalUris.contains(target.uri))
                        assertEquals("Existing library playlists must be preserved", originalUris, finalUris)
                        check(pending.edit().remove("created_uri").commit()) { "Could not save test cleanup completion" }
                        Log.i(TAG, "temporary playlist deleted; existing library preserved")
                    } catch (cleanup: Throwable) {
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }

    private suspend fun libraryUris(api: SpotifyApiClient, username: String): Set<String> {
        val encoded = URLEncoder.encode(username, Charsets.UTF_8.name()).replace("+", "%20")
        val uris = mutableSetOf<String>()
        var offset = 0
        do {
            val response = api.getProto("/playlist/v2/user/$encoded/rootlist",
                mapOf("from" to offset.toString(), "length" to "120"))
            val page = SpClientProto.parseRootlist(response.bodyBytes)
            check(page.offset == offset) { "Test library pagination returned a different position" }
            uris += page.items.map { it.uri }
            if (!page.truncated) return uris
            check(page.items.isNotEmpty()) { "Test library pagination did not advance" }
            offset += page.items.size
        } while (true)
    }

    private suspend fun awaitMetadata(read: suspend () -> PlaylistMetadata, matches: (PlaylistMetadata) -> Boolean): PlaylistMetadata {
        repeat(30) {
            val metadata = read()
            if (matches(metadata)) return metadata
            delay(500)
        }
        throw AssertionError("Saved playlist metadata did not become visible")
    }

    private fun imageJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 116, 168))
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun verifyUploadedImage(url: String) {
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            assertEquals("Uploaded cover must be downloadable", 200, connection.responseCode)
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("Uploaded cover must decode as an image")
            try {
                val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                assertTrue("The cover must contain the uploaded test image", kotlin.math.abs(Color.red(color) - 32) < 20 &&
                    kotlin.math.abs(Color.green(color) - 116) < 20 && kotlin.math.abs(Color.blue(color) - 168) < 20)
            } finally {
                bitmap.recycle()
            }
        } catch (error: Exception) {
            // Network exceptions can embed the image URL. Keep account-specific identifiers out of test logs.
            throw AssertionError("Uploaded cover request failed: ${error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "PlayPlaylistCheck"
        const val TRACK = "spotify:track:4CeeEOM32jQcH3eN9Q2dGj"
    }
}
