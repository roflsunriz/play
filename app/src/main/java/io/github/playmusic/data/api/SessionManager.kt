package io.github.playmusic.data.api

import io.github.playmusic.data.auth.SpotifyAuthException
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.auth.LoginVerificationRequiredException
import io.github.playmusic.data.security.SecureSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SessionTokens {
    suspend fun username(): String
    suspend fun accessToken(forceRefresh: Boolean = false): String
    suspend fun clientToken(forceRefresh: Boolean = false): String
}

class SessionManager(
    private val store: SecureSessionStore,
    private val login5Client: SpotifyLogin5Client,
    private val clientTokenClient: SpotifyClientTokenClient,
) : SessionTokens {
    private val refreshMutex = Mutex()

    override suspend fun username(): String {
        val session = store.loadSession() ?: throw SpotifyAuthException("Service login is required")
        return session.username
    }

    override suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
        val session = store.loadSession() ?: throw SpotifyAuthException("Service login is required")
        if (!forceRefresh && !session.expiresSoon()) return@withLock session.accessToken
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
        store.saveSession(
            session.copy(
                username = refreshed.username,
                accessToken = refreshed.accessToken,
                storedCredential = refreshed.storedCredential ?: session.storedCredential,
                expiresAtEpochMs = System.currentTimeMillis() + refreshed.accessTokenExpiresIn * 1_000L,
            ),
        )
        refreshed.accessToken
    }

    override suspend fun clientToken(forceRefresh: Boolean): String =
        clientTokenClient.acquire(
            clientId = io.github.playmusic.data.auth.AppConstants.SPOTIFY_CLIENT_ID,
            deviceId = store.loadDeviceId(),
            forceRefresh = forceRefresh,
        ).token
}
