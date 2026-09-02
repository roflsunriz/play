package io.github.playmusic.data.auth

import io.github.playmusic.data.model.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class SpotifyAccountsClient {
    suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): AuthSession = requestToken(
        mapOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
        ),
        existingRefreshToken = null,
    )

    suspend fun refresh(clientId: String, session: AuthSession): AuthSession = requestToken(
        mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken,
        ),
        existingRefreshToken = session.refreshToken,
    )

    private suspend fun requestToken(
        fields: Map<String, String>,
        existingRefreshToken: String?,
    ): AuthSession = withContext(Dispatchers.IO) {
        val connection = URI(TOKEN_ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(fields.entries.joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                })
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(body).optString("error_description") }.getOrNull()
                throw SpotifyAuthException(error?.ifBlank { null } ?: "Spotify authorization failed ($status)")
            }
            val json = JSONObject(body)
            AuthSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank {
                    existingRefreshToken ?: throw SpotifyAuthException("Spotify did not return a refresh token")
                },
                expiresAtEpochMs = System.currentTimeMillis() + json.getLong("expires_in") * 1_000L,
                scope = json.optString("scope"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

class SpotifyAuthException(message: String) : Exception(message)
