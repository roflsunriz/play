package io.github.playmusic

import android.content.Context
import io.github.playmusic.data.api.SessionManager
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.api.SpotifyRepository
import io.github.playmusic.data.api.CatalogApiClient
import io.github.playmusic.data.auth.AppConstants
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.AccessPointIdentity
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.StreamingApiClient
import io.github.playmusic.data.security.SecureSessionStore

class AppContainer(
    context: Context,
    private val clientTokenClient: SpotifyClientTokenClient = SpotifyClientTokenClient(),
    val login5Client: SpotifyLogin5Client = SpotifyLogin5Client(AppConstants.SPOTIFY_CLIENT_ID, clientTokenClient),
    val browserAuthorizationClient: BrowserAuthorizationClient = BrowserAuthorizationClient(),
    apiConnection: (java.net.URI) -> java.net.HttpURLConnection = { it.toURL().openConnection() as java.net.HttpURLConnection },
) {
    val sessionStore = SecureSessionStore(context)
    val accessPointIdentity = AccessPointIdentity()
    val sessionManager = SessionManager(sessionStore, login5Client, clientTokenClient, browserAuthorizationClient)
    val localPlayback = LocalPlayback(context)
    val streamingApi = StreamingApiClient(sessionManager, apiConnection)
    val repository = SpotifyRepository(
        api = SpotifyApiClient(sessionManager, apiConnection),
        sessionManager = sessionManager,
        catalog = CatalogApiClient(sessionManager, apiConnection),
    )

    private val applicationContext = context.applicationContext
    fun startLoginWaiting() {
        androidx.core.content.ContextCompat.startForegroundService(applicationContext,
            android.content.Intent(applicationContext, io.github.playmusic.data.auth.LoginCallbackService::class.java))
    }

    fun stopLoginWaiting() {
        applicationContext.stopService(android.content.Intent(applicationContext,
            io.github.playmusic.data.auth.LoginCallbackService::class.java))
    }

    fun returnToApp() {
        applicationContext.startActivity(android.content.Intent(applicationContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
