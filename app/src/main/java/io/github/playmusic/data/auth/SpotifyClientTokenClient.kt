package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

class SpotifyClientTokenClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clientVersion: String = CLIENT_VERSION,
) {
    suspend fun acquire(clientId: String, deviceId: String): GrantedClientToken = withContext(Dispatchers.IO) {
        val initialRequest = ClientTokenRequest(
            clientId = clientId,
            clientVersion = clientVersion,
            deviceId = deviceId,
        )
        var response = post(initialRequest.encode())
        if (response.grantedToken != null) {
            return@withContext response.grantedToken
        }
        val challenges = response.challenges
            ?: throw SpotifyAuthException("Spotify client token request did not return a result")
        val hashCash = challenges.challenges.firstNotNullOfOrNull { it.hashCash }
            ?: throw SpotifyAuthException("Spotify client token challenge is not supported")
        val suffix = HashCash.solveClientToken(hashCash.prefix, hashCash.length)
        val answerRequest = initialRequest.encodeChallengeAnswers(challenges.state, HashCashAnswer(suffix))
        val answerResponse = post(answerRequest)
        answerResponse.grantedToken?.takeIf { it.token.isNotBlank() }
            ?: throw SpotifyAuthException("Spotify did not return a client token after challenge")
    }

    private fun post(body: ByteArray): ClientTokenResponse {
        val connection = URI(CLIENT_TOKEN_ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/x-protobuf")
            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Content-Length", body.size.toString())
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            android.util.Log.w("SpotifyClientToken", "req=${android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)} status=$status resp=${android.util.Base64.encodeToString(responseBody, android.util.Base64.NO_WRAP).take(120)}")
            if (status !in 200..299) {
                throw SpotifyAuthException("Spotify client token request failed ($status)")
            }
            return runCatching { ClientTokenResponse.parse(responseBody) }.getOrElse {
                android.util.Log.e("SpotifyClientToken", "parse failed", it)
                throw it
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CLIENT_TOKEN_ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken"
        const val CLIENT_VERSION = "9.1.78.2218"
        const val DEFAULT_USER_AGENT = "Spotify/9.1.78.2218 Android/37 (Android 16)"
        const val SPOTCONTROL_USER_AGENT = "spotcontrol/0.0.0 Go/1.0"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}