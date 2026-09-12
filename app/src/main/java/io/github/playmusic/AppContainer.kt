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

class AppContainer(
    context: Context,
    private val clientTokenClient: SpotifyClientTokenClient = SpotifyClientTokenClient(),
    val login5Client: SpotifyLogin5Client = SpotifyLogin5Client(AppConstants.SPOTIFY_CLIENT_ID, clientTokenClient),
    apiConnection: (java.net.URI) -> java.net.HttpURLConnection = { it.toURL().openConnection() as java.net.HttpURLConnection },
) {
    val sessionStore = SecureSessionStore(context)
    val trackCache = TrackCache(context)
    val previewPlayer = PreviewPlayer(trackCache)
    val sessionManager = SessionManager(sessionStore, login5Client, clientTokenClient)
    val repository = SpotifyRepository(
        api = SpotifyApiClient(sessionManager, apiConnection),
        sessionManager = sessionManager,
        deviceIdProvider = { sessionStore.loadDeviceId() },
    )
}
