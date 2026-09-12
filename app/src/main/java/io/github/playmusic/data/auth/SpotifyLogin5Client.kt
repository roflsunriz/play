package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URI

class SpotifyLogin5Client(
    val clientId: String,
    private val clientTokenClient: SpotifyClientTokenClient,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
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
            // The device SDK token is platform-specific, independently of the Login5 audience.
            val clientToken = clientTokenClient.acquire(AppConstants.SPOTIFY_CLIENT_ID, deviceId).token
            var currentRequest = loginRequest
            repeat(MAX_LOGIN_TRIES) { attempt ->
                val response = post(currentRequest, clientToken)
                val error = response.error?.let(Login5Error::fromCode)
                if (error != null) {
                    if (error == Login5Error.TIMEOUT && attempt < MAX_LOGIN_TRIES - 1) {
                        delay(LOGIN_TIMEOUT_MS)
                        return@repeat
                    }
                    throw SpotifyAuthException("Login failed: ${error.name}")
                }
                val success = response.ok
                if (success != null) {
                    if (success.username.isBlank() || success.accessToken.isBlank() || success.accessTokenExpiresIn <= 0) {
                        throw SpotifyAuthException("Login response is incomplete")
                    }
                    return@withContext LoginOutcome.Success(
                        username = success.username,
                        accessToken = success.accessToken,
                        accessTokenExpiresIn = success.accessTokenExpiresIn,
                        storedCredential = success.storedCredential?.takeIf { it.isNotEmpty() },
                    )
                }
                val codeChallenge = response.challenges.firstOrNull { it.code != null }
                if (codeChallenge != null) {
                    val context = response.loginContext
                        ?: throw SpotifyAuthException("Service login challenge is missing login context")
                    return@withContext LoginOutcome.CodeChallengeRequired(context, codeChallenge.code?.maskedTarget.orEmpty())
                }
                if (response.challenges.isEmpty() || response.challenges.any { it.hashcash == null }) {
                    throw SpotifyAuthException("Login response contains no supported result or challenge")
                }
                if (attempt == MAX_LOGIN_TRIES - 1) {
                    throw SpotifyAuthException("Login challenge attempt limit reached")
                }
                currentRequest = solveChallenges(response, currentRequest)
            }
            throw SpotifyAuthException("Login challenge attempt limit reached")
        }

    private suspend fun solveChallenges(response: LoginResponse, request: LoginRequest): LoginRequest {
        val solutions = response.challenges.mapNotNull { challenge ->
            challenge.hashcash?.let { hashcash ->
                val solution = runInterruptible(Dispatchers.Default) {
                    HashCash.solve(response.loginContext, hashcash.prefix, hashcash.length)
                }
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
            val connection = openConnection(URI(endpointFor(request)))
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
                if (status !in 200..299) {
                    throw SpotifyAuthException("Service login request failed ($status)")
                }
                LoginResponse.parse(responseBody)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        internal fun endpointFor(request: LoginRequest): String =
            if (request.storedCredential != null) {
                "https://login5.spotify.com/v3/login"
            } else {
                "https://login5.spotify.com/v4/login"
            }

        private const val USER_AGENT = AppConstants.SPOTIFY_USER_AGENT
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val LOGIN_TIMEOUT_MS = 3_000L
        private const val MAX_LOGIN_TRIES = 3
        private const val AUTH_CALLBACK_URL = "https://auth-callback.spotify.com/r/android/music/login"
    }
}
