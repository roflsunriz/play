package io.github.playmusic.data.auth

import android.net.Uri
import androidx.core.net.toUri
import io.github.playmusic.data.model.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

class SpotifyOAuthCoordinator(private val accountsClient: SpotifyAccountsClient) {
    class AuthorizationAttempt internal constructor(
        val authorizationUri: Uri,
        internal val clientId: String,
        internal val verifier: String,
        internal val state: String,
        internal val redirectUri: String,
        internal val socket: ServerSocket,
    )

    fun prepare(clientId: String): AuthorizationAttempt {
        require(clientId.isNotBlank())
        val verifier = Pkce.randomUrlSafe()
        val state = Pkce.randomUrlSafe(32)
        val socket = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 1)
        }
        val redirectUri = "http://$LOOPBACK_HOST:${socket.localPort}/callback"
        val uri = AUTHORIZATION_ENDPOINT.toUri().buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .build()
        return AuthorizationAttempt(uri, clientId, verifier, state, redirectUri, socket)
    }

    suspend fun complete(attempt: AuthorizationAttempt): AuthSession = try {
        val code = awaitAuthorizationCode(attempt)
        accountsClient.exchangeCode(attempt.clientId, code, attempt.verifier, attempt.redirectUri)
    } finally {
        runCatching { attempt.socket.close() }
    }

    private suspend fun awaitAuthorizationCode(attempt: AuthorizationAttempt): String =
        withTimeout(AUTHORIZATION_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                attempt.socket.accept().use { client ->
                    val requestLine = client.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                        ?: throw SpotifyAuthException("Spotify returned an empty callback")
                    val target = requestLine.split(' ').getOrNull(1)
                        ?: throw SpotifyAuthException("Spotify returned an invalid callback")
                    val parameters = parseQuery(URI("http://$LOOPBACK_HOST$target").rawQuery.orEmpty())
                    val state = parameters["state"].orEmpty()
                    val stateMatches = MessageDigest.isEqual(
                        state.toByteArray(Charsets.UTF_8),
                        attempt.state.toByteArray(Charsets.UTF_8),
                    )
                    val error = parameters["error"]
                    val code = parameters["code"]
                    val success = stateMatches && error == null && !code.isNullOrBlank()
                    writeBrowserResponse(client.getOutputStream().bufferedWriter(Charsets.UTF_8), success)
                    when {
                        !stateMatches -> throw SpotifyAuthException("Spotify login state did not match")
                        error != null -> throw SpotifyAuthException("Spotify login was denied: $error")
                        code.isNullOrBlank() -> throw SpotifyAuthException("Spotify did not return an authorization code")
                        else -> code
                    }
                }
            }
        }

    private fun parseQuery(rawQuery: String): Map<String, String> = rawQuery
        .split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) return@mapNotNull null
            decode(part.substring(0, separator)) to decode(part.substring(separator + 1))
        }
        .toMap()

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun writeBrowserResponse(writer: BufferedWriter, success: Boolean) {
        val title = if (success) "Login complete / ログイン完了" else "Login failed / ログイン失敗"
        val message = if (success) {
            "You can return to Play. / Playへ戻ってください。"
        } else {
            "Return to Play and try again. / Playへ戻って再試行してください。"
        }
        val body = "<!doctype html><meta charset=\"utf-8\"><title>$title</title>" +
            "<meta name=\"viewport\" content=\"width=device-width\"><h1>$title</h1><p>$message</p>"
        writer.use {
            it.write("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\n")
            it.write("Content-Type: text/html; charset=utf-8\r\n")
            it.write("Cache-Control: no-store\r\n")
            it.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            it.write("Connection: close\r\n\r\n")
            it.write(body)
        }
    }

    private companion object {
        const val AUTHORIZATION_ENDPOINT = "https://accounts.spotify.com/authorize"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val AUTHORIZATION_TIMEOUT_MS = 5 * 60 * 1_000L
        val SCOPES = listOf(
            "playlist-read-private",
            "user-library-read",
            "user-read-playback-state",
            "user-modify-playback-state",
        )
    }
}
