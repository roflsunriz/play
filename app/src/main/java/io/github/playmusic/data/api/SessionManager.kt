package io.github.playmusic.data.api

import io.github.playmusic.data.auth.SpotifyAuthException
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.security.SecureSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionManager(
    private val store: SecureSessionStore,
    private val login5Client: SpotifyLogin5Client,
    private val clientTokenClient: SpotifyClientTokenClient,
) {
    private val refreshMutex = Mutex()
    private val clientTokenMutex = Mutex()
    private var cachedClientToken: String? = null

    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val session = store.loadSession() ?: throw SpotifyAuthException("Spotify login is required")
        if (!forceRefresh && !session.expiresSoon()) return@withLock session.accessToken
        val storedCredential = session.storedCredential
            ?: throw SpotifyAuthException("Spotify stored credentials are missing")
        val refreshed = login5Client.loginWithStoredCredential(
            username = session.username,
            storedCredential = storedCredential,
            deviceId = store.loadDeviceId(),
        ) as? io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.Success
            ?: throw SpotifyAuthException("Spotify token refresh requires a verification code")
        store.saveSession(
            session.copy(
                accessToken = refreshed.accessToken,
                storedCredential = refreshed.storedCredential ?: session.storedCredential,
                expiresAtEpochMs = System.currentTimeMillis() + refreshed.accessTokenExpiresIn * 1_000L,
            ),
        )
        refreshed.accessToken
    }

    suspend fun clientToken(forceRefresh: Boolean = false): String = clientTokenMutex.withLock {
        val cached = cachedClientToken
        if (!forceRefresh && cached != null) return@withLock cached
        val token = clientTokenClient.acquire(
            clientId = io.github.playmusic.data.auth.AppConstants.SPOTIFY_CLIENT_ID,
            deviceId = store.loadDeviceId(),
        ).token
        cachedClientToken = token
        token
    }
}
