package io.github.playmusic.data.api

import io.github.playmusic.data.auth.SpotifyAccountsClient
import io.github.playmusic.data.auth.SpotifyAuthException
import io.github.playmusic.data.security.SecureSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionManager(
    private val store: SecureSessionStore,
    private val accountsClient: SpotifyAccountsClient,
) {
    private val refreshMutex = Mutex()

    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val clientId = store.loadClientId()
        val session = store.loadSession() ?: throw SpotifyAuthException("Spotify login is required")
        if (!forceRefresh && !session.expiresSoon()) return@withLock session.accessToken
        if (clientId.isBlank()) throw SpotifyAuthException("Spotify Client ID is missing")
        val refreshed = accountsClient.refresh(clientId, session)
        store.saveSession(refreshed)
        refreshed.accessToken
    }
}
