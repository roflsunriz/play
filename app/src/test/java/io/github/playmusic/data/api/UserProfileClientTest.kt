package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI

class UserProfileClientTest {
    @Test fun encodedProfileIdentitiesMatchWithoutAliasingDifferentUsers() {
        for ((encoded, username) in listOf("%E9%9F%B3%E6%A5%BD" to "音楽", "name%20with%2Fslash" to "name with/slash",
            "a+b" to "a+b", "a%2Bb" to "a+b", "a%2520b" to "a%20b", "a%3Ab" to "a:b")) {
            val bytes = fieldString(1, "spotify:user:$encoded") + fieldString(2, "Display")
            assertEquals("Display", UserProfileClient.parse(bytes, username).displayName)
            assertTrue(runCatching { UserProfileClient.parse(bytes, "different-user") }.isFailure)
        }
        for ((encoded, wrongUsername) in listOf("a+b" to "a b", "a%2520b" to "a b", "a%20b" to "a%20b",
            "broken%2" to "broken%2")) {
            assertTrue(runCatching { UserProfileClient.parse(fieldString(1, "spotify:user:$encoded"), wrongUsername) }.isFailure)
        }
    }

    @Test fun displayNameIsReadSeparatelyFromTheAccountIdAndUnrelatedFieldsAreSkipped() {
        val bytes = fieldString(1, "spotify:user:random-id") + fieldBytes(7, byteArrayOf(1, 2, 3)) +
            fieldString(2, "音楽好き") + fieldString(27, "Unneeded biography")
        assertEquals("音楽好き", UserProfileClient.parse(bytes, "random-id").displayName)
        assertTrue(runCatching { UserProfileClient.parse(bytes, "another-account") }.isFailure)
        assertTrue(runCatching { UserProfileClient.parse(fieldString(2, "Name"), "random-id") }.isFailure)
    }

    @Test fun missingDisplayNameDoesNotTurnIntoTheAccountId() {
        assertNull(UserProfileClient.parse(fieldString(1, "spotify:user:random-id"), "random-id").displayName)
    }

    @Test fun profileRequestUsesTheObservedPathMimeAndZeroContentLimits() = runBlocking {
        val tokens = tokens()
        val api = ServiceApiClient(tokens) { uri ->
            assertEquals("/user-profile-view/v3/profile/name%20with%2Fslash", uri.rawPath)
            assertEquals("playlist_limit=0&artist_limit=0&episode_limit=0", uri.rawQuery)
            object : HttpURLConnection(uri.toURL()) {
                override fun getResponseCode(): Int {
                    assertEquals("application/x-protobuf", getRequestProperty("Accept"))
                    return 200
                }
                override fun getInputStream() = ByteArrayInputStream(fieldString(1, "spotify:user:name with/slash") + fieldString(2, "Display name"))
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }
        assertEquals("Display name", UserProfileClient(api).profile("name with/slash").displayName)
    }

    @Test fun unavailableProfilesAndNetworkErrorsRemainDifferent() = runBlocking {
        for (status in listOf(403, 404, 410, 503)) {
            val api = ServiceApiClient(tokens()) { uri -> object : HttpURLConnection(uri.toURL()) {
                override fun getResponseCode() = status
                override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            } }
            val result = runCatching { UserProfileClient(api).profile("account") }
            if (status == 503) assertTrue(result.exceptionOrNull() is ServiceApiException)
            else assertNull(result.getOrThrow().displayName)
        }
    }

    private fun tokens() = object : SessionTokens {
        override suspend fun username() = "synthetic-user"
        override suspend fun accessToken(forceRefresh: Boolean) = "synthetic-access"
        override suspend fun clientToken(forceRefresh: Boolean) = "synthetic-client"
        override suspend fun usesBrowserAuthorization() = true
    }
}
