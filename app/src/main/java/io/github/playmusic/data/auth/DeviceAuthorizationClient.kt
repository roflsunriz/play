package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Device authorization observed in the desktop client; no desktop credentials are imported. */
class DeviceAuthorizationClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    class Pending(
        internal val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresAtEpochMs: Long,
        internal val intervalSeconds: Long,
    )

    class Tokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

    suspend fun begin(): Pending {
        val (status, json) = request(AUTHORIZATION_ENDPOINT, mapOf("client_id" to CLIENT_ID, "scope" to SCOPES))
        requireSuccess(status, json)
        val lifetime = json.getLong("expires_in")
        require(lifetime in 1..86_400) { "Invalid authorization lifetime" }
        val interval = json.optLong("interval", 5).coerceAtLeast(5)
        require(interval <= lifetime) { "Invalid authorization interval" }
        val verification = URI(json.getString("verification_uri_complete"))
        require(verification.scheme == "https" && verification.host in VERIFICATION_HOSTS &&
            verification.path == "/pair" && verification.userInfo == null && verification.port == -1 && verification.fragment == null) {
            "Unexpected authorization page"
        }
        return Pending(
            deviceCode = json.getString("device_code").also { require(it.isNotBlank()) },
            userCode = json.getString("user_code").also { require(it.isNotBlank()) },
            verificationUri = verification.toASCIIString(),
            expiresAtEpochMs = now() + lifetime * 1_000,
            intervalSeconds = interval,
        )
    }

    suspend fun awaitAuthorization(pending: Pending): Tokens {
        var interval = pending.intervalSeconds
        while (now() < pending.expiresAtEpochMs) {
            wait(interval * 1_000)
            if (now() >= pending.expiresAtEpochMs) break
            val (status, json) = request(TOKEN_ENDPOINT, mapOf(
                "client_id" to CLIENT_ID,
                "device_code" to pending.deviceCode,
                "grant_type" to DEVICE_GRANT,
            ))
            when (json.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> { interval += 5; continue }
                "access_denied" -> throw SpotifyAuthException("Browser authorization was declined")
                "expired_token" -> break
            }
            requireSuccess(status, json)
            return tokens(json).also { require(!it.refreshToken.isNullOrBlank()) { "Authorization did not return a refresh token" } }
        }
        throw SpotifyAuthException("Browser authorization expired. Start again")
    }

    suspend fun refresh(refreshToken: String): Tokens {
        require(refreshToken.isNotBlank())
        val (status, json) = request(TOKEN_ENDPOINT, mapOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ))
        requireSuccess(status, json)
        return tokens(json)
    }

    private fun tokens(json: JSONObject): Tokens {
        require(json.getString("token_type").equals("Bearer", ignoreCase = true)) { "Unsupported authorization token" }
        return Tokens(
            accessToken = json.getString("access_token").also { require(it.isNotBlank()) },
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = json.getLong("expires_in").also { require(it in 1..86_400) },
        )
    }

    private fun requireSuccess(status: Int, json: JSONObject) {
        if (status !in 200..299 || json.has("error")) {
            val code = json.optString("error").takeIf { it.matches(Regex("[a-z_]{1,64}")) }
            throw SpotifyAuthException("Browser authorization failed ($status${code?.let { ": $it" }.orEmpty()})")
        }
    }

    private suspend fun request(
        endpoint: String,
        form: Map<String, String>? = null,
        bearer: String? = null,
    ): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
        val connection = openConnection(URI(endpoint))
        try {
            connection.requestMethod = if (form == null) "GET" else "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            form?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = it.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
                connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            status to if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // Public identifier observed in desktop platform metadata and the actual OAuth request.
        const val CLIENT_ID = DesktopClientProfile.CLIENT_ID
        private const val AUTHORIZATION_ENDPOINT = "https://accounts.spotify.com/oauth2/device/authorize"
        private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private val VERIFICATION_HOSTS = setOf("spotify.com", "www.spotify.com", "accounts.spotify.com")
        // Read/playback scopes observed in the desktop request. This audience rejects OpenID scopes.
        private const val SCOPES = "app-remote-control,playlist-read,playlist-read-collaborative," +
            "playlist-read-private,streaming,user-follow-read,user-library-read,user-modify-playback-state," +
            "user-personalized,user-read-currently-playing,user-read-play-history,user-read-playback-position," +
            "user-read-playback-state,user-read-private,user-read-recently-played,user-top-read"
    }
}
