package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URI

class SpotifyClientTokenClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clientVersion: String = CLIENT_VERSION,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    private data class CachedToken(val clientId: String, val deviceId: String, val value: GrantedClientToken, val refreshAt: Long)
    private val mutex = Mutex()
    private var cached: CachedToken? = null

    suspend fun acquire(clientId: String, deviceId: String, forceRefresh: Boolean = false): GrantedClientToken = mutex.withLock {
        cached?.takeIf { !forceRefresh && it.clientId == clientId && it.deviceId == deviceId && clock() < it.refreshAt }
            ?.let { return@withLock it.value }
        cached = null
        val value = fetch(clientId, deviceId)
        val lifetime = listOf(value.refreshAfterSeconds, value.expiresAfterSeconds).filter { it > 0 }.minOrNull() ?: 0
        cached = CachedToken(clientId, deviceId, value, clock() + lifetime * 1_000L)
        value
    }

    private suspend fun fetch(clientId: String, deviceId: String): GrantedClientToken = withContext(Dispatchers.IO) {
        val initialRequest = ClientTokenRequest(
            clientId = clientId,
            clientVersion = clientVersion,
            deviceId = deviceId,
        )
        val response = post(initialRequest.encode(), "initial")
        if (response.grantedToken != null) {
            return@withContext response.grantedToken.takeIf { it.token.isNotBlank() }
                ?: throw SpotifyAuthException("Client token response is empty")
        }
        val challenges = response.challenges
            ?: throw SpotifyAuthException("Service client token request did not return a result")
        val hashCash = challenges.challenges.firstNotNullOfOrNull { it.hashCash }
            ?: throw SpotifyAuthException("Service client token challenge is not supported")
        val suffix = runInterruptible(Dispatchers.Default) { HashCash.solveClientToken(hashCash.prefix, hashCash.length) }
        val answerRequest = initialRequest.encodeChallengeAnswers(challenges.state, HashCashAnswer(suffix))
        val answerResponse = post(answerRequest, "challenge")
        answerResponse.grantedToken?.takeIf { it.token.isNotBlank() }
            ?: throw SpotifyAuthException("Service did not return a client token after challenge")
    }

    private fun post(body: ByteArray, phase: String): ClientTokenResponse {
        val connection = openConnection(URI(CLIENT_TOKEN_ENDPOINT))
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
            if (status !in 200..299) {
                throw SpotifyAuthException("Client token $phase request failed ($status)")
            }
            return ClientTokenResponse.parse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CLIENT_TOKEN_ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken"
        const val CLIENT_VERSION = AppConstants.CLIENT_VERSION
        const val DEFAULT_USER_AGENT = AppConstants.SPOTIFY_USER_AGENT
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
