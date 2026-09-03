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
        clientTokenClient.acquire(clientId = AppConstants.SPOTIFY_CLIENT_ID, deviceId = deviceId)

    sealed class LoginOutcome {
        data class Success(
            val username: String,
            val accessToken: String,
            val accessTokenExpiresIn: Int,
            val storedCredential: ByteArray?,
        ) : LoginOutcome()

        data class CodeChallengeRequired(
            val loginContext: ByteArray,
            val maskedTarget: String,
        ) : LoginOutcome()
    }

    suspend fun loginWithPassword(username: String, password: String, deviceId: String): LoginOutcome =
        authenticate(
            loginRequest = LoginRequest(
                clientInfo = ClientInfo(clientId, deviceId),
                password = LoginPassword(username, password),
                authFlow = newAuthFlow(),
                clientRequestId = newClientRequestId(),
            ),
            deviceId = deviceId,
        )

    suspend fun loginWithStoredCredential(username: String, storedCredential: ByteArray, deviceId: String): LoginOutcome =
        authenticate(
            loginRequest = LoginRequest(
                clientInfo = ClientInfo(clientId, deviceId),
                storedCredential = LoginStoredCredential(username, storedCredential),
                authFlow = newAuthFlow(),
                clientRequestId = newClientRequestId(),
            ),
            deviceId = deviceId,
        )

    suspend fun loginWithCode(
        username: String,
        password: String,
        deviceId: String,
        code: String,
        loginContext: ByteArray,
    ): LoginOutcome = authenticate(
        loginRequest = LoginRequest(
            clientInfo = ClientInfo(clientId, deviceId),
            loginContext = loginContext,
            challengeSolutions = listOf(
                ChallengeSolution(code = CodeSolution(code)),
            ),
            password = LoginPassword(username, password),
            authFlow = newAuthFlow(),
            clientRequestId = newClientRequestId(),
        ),
        deviceId = deviceId,
    )

    suspend fun loginWithStoredCredentialAndCode(
        username: String,
        storedCredential: ByteArray,
        deviceId: String,
        code: String,
        loginContext: ByteArray,
    ): LoginOutcome = authenticate(
        loginRequest = LoginRequest(
            clientInfo = ClientInfo(clientId, deviceId),
            loginContext = loginContext,
            challengeSolutions = listOf(
                ChallengeSolution(code = CodeSolution(code)),
            ),
            storedCredential = LoginStoredCredential(username, storedCredential),
            authFlow = newAuthFlow(),
            clientRequestId = newClientRequestId(),
        ),
        deviceId = deviceId,
    )

    private fun newAuthFlow(): LoginAuthFlow = LoginAuthFlow(
        redirectUri = AUTH_CALLBACK_URL,
        callbackUuid = java.util.UUID.randomUUID().toString(),
    )

    private fun newClientRequestId(): String = java.util.UUID.randomUUID().toString()

    private suspend fun authenticate(loginRequest: LoginRequest, deviceId: String): LoginOutcome =
        withContext(Dispatchers.IO) {
            val clientToken = clientTokenClient.acquire(AppConstants.SPOTIFY_CLIENT_ID, deviceId).token
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
                val codeChallenge = response.challenges.firstOrNull { it.code != null }
                if (codeChallenge != null) {
                    val context = response.loginContext
                        ?: throw SpotifyAuthException("Spotify login challenge is missing login context")
                    return@withContext LoginOutcome.CodeChallengeRequired(context, codeChallenge.code?.maskedTarget.orEmpty())
                }
                if (response.challenges.isNotEmpty()) {
                    currentRequest = solveChallenges(response, currentRequest)
                }
                response = post(currentRequest, clientToken)
                attempt++
            }
            val success = response.ok ?: throw SpotifyAuthException("Spotify login timed out")
            LoginOutcome.Success(
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
                android.util.Log.w("SpotifyLogin5", "status=$status body=${responseBody.joinToString("") { "%02x".format(it) }}")
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
        const val LOGIN_ENDPOINT = "https://login5.spotify.com/v4/login"
        const val USER_AGENT = AppConstants.SPOTIFY_USER_AGENT
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val LOGIN_TIMEOUT_MS = 3_000L
        const val MAX_LOGIN_TRIES = 3
        const val AUTH_CALLBACK_URL = "https://auth-callback.spotify.com/r/android/music/login"
    }
}
