package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

class SpotifyLogin5Client(
    val clientId: String,
    private val clientTokenClient: SpotifyClientTokenClient,
) {
    suspend fun acquireClientToken(deviceId: String): GrantedClientToken =
        clientTokenClient.acquire(clientId = AppConstants.CLIENT_TOKEN_CLIENT_ID, deviceId = deviceId)

    data class LoginSuccess(
        val username: String,
        val accessToken: String,
        val accessTokenExpiresIn: Int,
        val storedCredential: ByteArray?,
    )

    suspend fun loginWithPassword(username: String, password: String, deviceId: String): LoginSuccess =
        authenticate(
            loginRequest = LoginRequest(
                clientInfo = ClientInfo(clientId, deviceId),
                password = LoginPassword(username, password),
            ),
            deviceId = deviceId,
        )

    suspend fun loginWithStoredCredential(username: String, storedCredential: ByteArray, deviceId: String): LoginSuccess =
        authenticate(
            loginRequest = LoginRequest(
                clientInfo = ClientInfo(clientId, deviceId),
                storedCredential = LoginStoredCredential(username, storedCredential),
            ),
            deviceId = deviceId,
        )

    private suspend fun authenticate(loginRequest: LoginRequest, deviceId: String): LoginSuccess =
        withContext(Dispatchers.IO) {
            val clientToken = clientTokenClient.acquire(AppConstants.CLIENT_TOKEN_CLIENT_ID, deviceId).token
            var currentRequest = loginRequest
            var response = post(currentRequest, clientToken)
            var attempt = 0
            while (attempt < MAX_LOGIN_TRIES) {
                response.error?.let { errorCode ->
                    when (Login5Error.fromCode(errorCode)) {
                        Login5Error.TIMEOUT,
                        Login5Error.TOO_MANY_ATTEMPTS,
                        -> {
                            delay(LOGIN_TIMEOUT_MS)
                            response = post(currentRequest, clientToken)
                            attempt++
                            return@let
                        }
                        else -> throw SpotifyAuthException(
                            "Spotify login failed: ${Login5Error.fromCode(errorCode).name}",
                        )
                    }
                }
                if (response.ok != null) break
                if (response.challenges.isNotEmpty()) {
                    currentRequest = solveChallenges(response, currentRequest)
                }
                response = post(currentRequest, clientToken)
                attempt++
            }
            val success = response.ok ?: throw SpotifyAuthException("Spotify login timed out")
            LoginSuccess(
                username = success.username,
                accessToken = success.accessToken,
                accessTokenExpiresIn = success.accessTokenExpiresIn,
                storedCredential = success.storedCredential,
            )
        }

    private fun solveChallenges(response: LoginResponse, request: LoginRequest): LoginRequest {
        val solutions = response.challenges.mapNotNull { challenge ->
            challenge.hashcash?.let { hashcash ->
                val solution = HashCash.solve(response.loginContext, hashcash.prefix, hashcash.length)
                ChallengeSolution(
                    hashcash = HashcashSolution(
                        suffix = solution.suffix,
                        durationSeconds = solution.durationSeconds,
                        durationNanos = solution.durationNanos,
                    ),
                )
            }
        }
        return request.copy(
            loginContext = response.loginContext,
            challengeSolutions = solutions,
        )
    }

    private suspend fun post(request: LoginRequest, clientToken: String): LoginResponse =
        withContext(Dispatchers.IO) {
            val body = request.encode()
            val connection = URI(LOGIN_ENDPOINT).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/x-protobuf")
                connection.setRequestProperty("Content-Type", "application/x-protobuf")
                connection.setRequestProperty("Client-Token", clientToken)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Content-Length", body.size.toString())
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
                android.util.Log.w("SpotifyLogin5", "status=$status body=${responseBody.take(256).joinToString("") { "%02x".format(it) }}")
                if (status !in 200..299) {
                    throw SpotifyAuthException("Spotify login request failed ($status)")
                }
                runCatching { LoginResponse.parse(responseBody) }.getOrElse {
                    android.util.Log.e("SpotifyLogin5", "parse failed", it)
                    throw it
                }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val LOGIN_ENDPOINT = "https://login5.spotify.com/v3/login"
        const val USER_AGENT = "Spotify/9.1.78.2218 Android/37 (Android 16)"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val LOGIN_TIMEOUT_MS = 3_000L
        const val MAX_LOGIN_TRIES = 3
    }
}