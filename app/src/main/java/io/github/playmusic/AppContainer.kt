package io.github.playmusic

import android.content.Context
import io.github.playmusic.data.api.SessionManager
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.api.SpotifyRepository
import io.github.playmusic.data.auth.AppConstants
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.cache.TrackCache
import io.github.playmusic.data.playback.PreviewPlayer
import io.github.playmusic.data.security.SecureSessionStore

class AppContainer(context: Context) {
    val sessionStore = SecureSessionStore(context)
    private val clientTokenClient = SpotifyClientTokenClient()
    val login5Client = SpotifyLogin5Client(AppConstants.SPOTIFY_CLIENT_ID, clientTokenClient)
    val trackCache = TrackCache(context)
    val previewPlayer = PreviewPlayer(trackCache)
    val sessionManager = SessionManager(sessionStore, login5Client, clientTokenClient)
    val repository = SpotifyRepository(
        api = SpotifyApiClient(sessionManager),
        sessionManager = sessionManager,
        deviceIdProvider = { sessionStore.loadDeviceId() },
    )

    companion object {
        const val SPOTIFY_CLIENT_ID = AppConstants.SPOTIFY_CLIENT_ID
    }
}