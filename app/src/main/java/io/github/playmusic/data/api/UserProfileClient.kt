package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import java.net.URLEncoder
import java.net.URLDecoder

/** Public profile name, distinct from the account identifier used for permissions. */
internal class UserProfileClient(private val api: ServiceApiClient) {
    data class Profile(val displayName: String?)

    suspend fun profile(username: String): Profile {
        require(username.isNotBlank())
        val encoded = URLEncoder.encode(username, Charsets.UTF_8.name()).replace("+", "%20")
        val response = try {
            api.getProto("/user-profile-view/v3/profile/$encoded",
                query = mapOf("playlist_limit" to "0", "artist_limit" to "0", "episode_limit" to "0"),
                accept = "application/x-protobuf")
        } catch (exception: ServiceApiException) {
            if (exception.status in setOf(403, 404, 410)) return Profile(null)
            throw exception
        }
        return parse(response.bodyBytes, username)
    }

    companion object {
        // Observed Profile: URI field 1, display name field 2. Other profile data is not retained.
        fun parse(bytes: ByteArray, username: String): Profile {
            val reader = ProtoWire.Reader(bytes)
            var uri: String? = null
            var name: String? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when {
                    reader.fieldNumber(tag) == 1 && reader.wireType(tag) == 2 -> uri = reader.readString()
                    reader.fieldNumber(tag) == 2 && reader.wireType(tag) == 2 -> name = reader.readString()
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            require(uri?.let(::usernameFromUri) == username) { "Profile response belongs to a different user" }
            return Profile(name?.trim()?.takeIf(String::isNotEmpty))
        }

        /** User URI components are percent encoded, while API usernames are decoded identifiers. */
        fun usernameFromUri(uri: String): String {
            require(uri.startsWith("spotify:user:")) { "Unexpected user URI" }
            val component = uri.removePrefix("spotify:user:")
            require(component.isNotEmpty()) { "User URI is missing its identifier" }
            // '+' in a URI is literal, unlike application/x-www-form-urlencoded input. Decode exactly once.
            return URLDecoder.decode(component.replace("+", "%2B"), Charsets.UTF_8.name())
        }
    }
}
