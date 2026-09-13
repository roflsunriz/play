package io.github.playmusic.data.auth

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.api.SessionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Derives short-lived playback authorization from this app's saved sign-in. Nothing is persisted. */
class PlaybackAuthorizationProvider(
    private val sourceToken: suspend (Boolean) -> Source,
    private val acquire: suspend (String, Boolean) -> Credentials,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Source(val owner: String, val accessToken: String) {
        init { require(owner.isNotBlank() && accessToken.isNotBlank()) }
        internal fun matches(other: Source): Boolean = owner == other.owner && accessToken == other.accessToken
        override fun toString(): String = "PlaybackAuthorizationProvider.Source"
    }

    class Credentials(
        private val accessToken: String,
        private val clientToken: GrantedClientToken,
        val refreshAtEpochMs: Long,
    ) {
        init {
            require(accessToken.isNotBlank() && accessToken.none { it == '\r' || it == '\n' })
            require(clientToken.token.isNotBlank() && clientToken.token.none { it == '\r' || it == '\n' })
        }

        internal fun headers(uri: URI): Map<String, String> {
            check(clientToken.allows(uri)) { "Playback authorization does not permit this host" }
            return mapOf("Authorization" to "Bearer $accessToken", "client-token" to clientToken.token)
        }

        override fun toString(): String = "PlaybackAuthorizationProvider.Credentials"
    }

    private class Cached(val source: Source, val credentials: Credentials)
    private val mutex = Mutex()
    private var cached: Cached? = null

    suspend fun headers(uri: URI, forceRefresh: Boolean = false): Map<String, String> = mutex.withLock {
        var source = try { sourceToken(false) } catch (error: Exception) { cached = null; throw error }
        val previous = cached
        if (!forceRefresh && previous != null && previous.source.matches(source) && now() < previous.credentials.refreshAtEpochMs) {
            return@withLock previous.credentials.headers(uri)
        }
        cached = null
        val credentials = try { acquire(source.accessToken, forceRefresh) }
        catch (error: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
            if (error.stage != PlaybackAuthorizationClient.Stage.TRANSFER ||
                error.failure != PlaybackAuthorizationClient.Failure.HTTP || error.status != 401) throw error
            val owner = source.owner
            val current = sourceToken(false)
            check(current.owner == owner) { "Sign-in changed while playback authorization was being prepared" }
            source = if (!current.matches(source)) current else sourceToken(true)
            check(source.owner == owner) { "Sign-in changed while playback authorization was being prepared" }
            acquire(source.accessToken, true)
        }
        check(sourceToken(false).matches(source)) { "Sign-in changed while playback authorization was being prepared" }
        check(now() < credentials.refreshAtEpochMs) { "Playback authorization has expired" }
        credentials.headers(uri).also { cached = Cached(source, credentials) }
    }

    companion object {
        fun create(tokens: SessionTokens, device: WebClientDevice): PlaybackAuthorizationProvider =
            PlaybackAuthorizationProvider(
                sourceToken = { forceRefresh ->
                    if (!tokens.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
                    val owner = tokens.username()
                    val accessToken = tokens.accessToken(forceRefresh)
                    check(tokens.username() == owner) { "Sign-in changed while playback authorization was being prepared" }
                    Source(owner, accessToken)
                },
                acquire = { bearer, forceRefresh ->
                    PlaybackAuthorizationClient().connect(bearer).use { session ->
                        val page = session.configuration()
                        val script = readPublicScript(session.publicMainScriptUrls.single())
                        PublicWebTokenConfiguration.parse(script).use { configuration ->
                            val reason = if (forceRefresh) PublicWebTokenConfiguration.Reason.TRANSPORT
                                else PublicWebTokenConfiguration.Reason.INITIAL
                            val serverTime = if (forceRefresh) session.serverTime() else page.serverTimeSeconds
                            val query = configuration.query(reason, page.pageKind, System.currentTimeMillis(), serverTime)
                            val observation = session.requestToken(query, forceRefresh)
                            check(observation.status == 200 && observation.hasAccessToken && observation.isAnonymous == false) {
                                "Playback authorization could not be issued"
                            }
                            val clientId = checkNotNull(observation.clientId) { "Playback authorization has no client identity" }
                            val client = WebClientTokenClient(device, clientId = clientId, clientVersion = page.clientVersion).acquire()
                            val clientLifetime = listOf(client.expiresAfterSeconds, client.refreshAfterSeconds)
                                .filter { it > 0 }.minOrNull() ?: error("Playback client authorization has no lifetime")
                            val refreshAt = minOf(checkNotNull(observation.expiresAtEpochMs),
                                System.currentTimeMillis() + clientLifetime * 1000L) - 30_000L
                            session.withAccessToken { accessToken -> Credentials(accessToken, client, refreshAt) }
                        }
                    }
                },
            )

        private suspend fun readPublicScript(location: String): String = withContext(Dispatchers.IO) {
            val uri = URI(location)
            require(uri.scheme == "https" && uri.host == "open.spotifycdn.com" && uri.port in listOf(-1, 443) &&
                uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
                uri.path.matches(Regex("/cdn/build/(?:web-player|mobile-web-player)/(?:web-player|mobile-web-player)\\.[A-Za-z0-9]+\\.js")))
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                check(connection.responseCode == 200) { "Playback client configuration is unavailable" }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 8_388_608) { "Playback client configuration exceeds its size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            } finally { connection.disconnect() }
        }
    }
}
