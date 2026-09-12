package io.github.playmusic.data.api

import io.github.playmusic.data.auth.SpotifyAuthException
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.LoginVerificationRequiredException
import io.github.playmusic.data.security.SecureSessionStore
import io.github.playmusic.data.model.AuthSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SessionTokens {
    suspend fun username(): String
    suspend fun accessToken(forceRefresh: Boolean = false): String
    suspend fun clientToken(forceRefresh: Boolean = false): String
    suspend fun usesBrowserAuthorization(): Boolean = false
}

class SessionManager(
    private val store: SecureSessionStore,
    private val login5Client: SpotifyLogin5Client,
    private val clientTokenClient: SpotifyClientTokenClient,
    private val browserAuthorizationClient: BrowserAuthorizationClient = BrowserAuthorizationClient(),
) : SessionTokens {
    private val refreshMutex = Mutex()
    private val sessionChangeLock = Any()
    private var sessionGeneration = 0L

    fun replaceSession(session: AuthSession) = synchronized(sessionChangeLock) {
        sessionGeneration++
        store.saveSession(session)
    }

    fun clearSession() = synchronized(sessionChangeLock) {
        sessionGeneration++
        store.clearSession()
    }

    private fun saveRefreshedSession(session: AuthSession, generation: Long) = synchronized(sessionChangeLock) {
        check(sessionGeneration == generation) { "Account changed while authorization was refreshing" }
        store.saveSession(session)
    }

    override suspend fun username(): String {
        val session = store.loadSession() ?: throw SpotifyAuthException("Service login is required")
        return session.username
    }

    override suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
        val (session, generation) = synchronized(sessionChangeLock) {
            (store.loadSession() ?: throw SpotifyAuthException("Service login is required")) to sessionGeneration
        }
        if (!forceRefresh && !session.expiresSoon()) return@withLock session.accessToken
        session.refreshToken?.let { refreshToken ->
            val refreshed = browserAuthorizationClient.refresh(refreshToken)
            saveRefreshedSession(session.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: refreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + refreshed.expiresInSeconds * 1_000L,
            ), generation)
            return@withLock refreshed.accessToken
        }
        val storedCredential = session.storedCredential
            ?: throw SpotifyAuthException("Service stored credentials are missing")
        val outcome = login5Client.loginWithStoredCredential(
            username = session.username,
            storedCredential = storedCredential,
            deviceId = store.loadDeviceId(),
        )
        val refreshed = when (outcome) {
            is SpotifyLogin5Client.LoginOutcome.Success -> outcome
            is SpotifyLogin5Client.LoginOutcome.CodeChallengeRequired -> throw LoginVerificationRequiredException(session.username, outcome)
        }
        saveRefreshedSession(
            session.copy(
                username = refreshed.username,
                accessToken = refreshed.accessToken,
                storedCredential = refreshed.storedCredential ?: session.storedCredential,
                expiresAtEpochMs = System.currentTimeMillis() + refreshed.accessTokenExpiresIn * 1_000L,
            ),
            generation,
        )
        refreshed.accessToken
    }

    override suspend fun clientToken(forceRefresh: Boolean): String =
        clientTokenClient.acquire(
            clientId = io.github.playmusic.data.auth.AppConstants.SPOTIFY_CLIENT_ID,
            deviceId = store.loadDeviceId(),
            forceRefresh = forceRefresh,
        ).token

    override suspend fun usesBrowserAuthorization(): Boolean = store.loadSession()?.refreshToken != null
}
