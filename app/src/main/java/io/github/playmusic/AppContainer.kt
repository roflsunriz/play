package io.github.playmusic

import android.content.Context
import io.github.playmusic.data.api.SessionManager
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.api.SpotifyRepository
import io.github.playmusic.data.auth.SpotifyAccountsClient
import io.github.playmusic.data.auth.SpotifyOAuthCoordinator
import io.github.playmusic.data.security.SecureSessionStore

class AppContainer(context: Context) {
    val sessionStore = SecureSessionStore(context)
    private val accountsClient = SpotifyAccountsClient()
    val oauthCoordinator = SpotifyOAuthCoordinator(accountsClient)
    val repository = SpotifyRepository(
        SpotifyApiClient(SessionManager(sessionStore, accountsClient)),
    )
}
