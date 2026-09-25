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
    private val cookieAcquire: (suspend (String, Boolean) -> Credentials)? = null,
) {
    class Source(val owner: String, val accessToken: String, val webCookie: String? = null) {
        init {
            require(owner.isNotBlank() && accessToken.isNotBlank())
            require(webCookie == null || webCookie.isNotBlank())
        }
        internal fun matches(other: Source): Boolean =
            owner == other.owner && accessToken == other.accessToken && webCookie == other.webCookie
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

    /**
     * Warms the short-lived credentials before the DRM thread needs them. A fresh sign-in
     * derives them over several round trips, so awaiting them outside playback keeps the first
     * license request from timing out at the first encrypted boundary.
     */
    suspend fun prepare(uri: URI) {
        headers(uri)
    }

    suspend fun headers(uri: URI, forceRefresh: Boolean = false): Map<String, String> = mutex.withLock {
        var source = try { sourceToken(false) } catch (error: Exception) { cached = null; throw error }
        val previous = cached
        if (!forceRefresh && previous != null && previous.source.matches(source) && now() < previous.credentials.refreshAtEpochMs) {
            return@withLock previous.credentials.headers(uri)
        }
        cached = null
        suspend fun finish(resolved: Source, credentials: Credentials): Map<String, String> {
            check(sourceToken(false).matches(resolved)) { "Sign-in changed while playback authorization was being prepared" }
            check(now() < credentials.refreshAtEpochMs) { "Playback authorization has expired" }
            return credentials.headers(uri).also { cached = Cached(resolved, credentials) }
        }
        val credentials = try { acquire(source.accessToken, forceRefresh) }
        catch (error: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
            if (error.stage != PlaybackAuthorizationClient.Stage.TRANSFER ||
                error.failure != PlaybackAuthorizationClient.Failure.HTTP) {
                throw error
            }
            val cookie = source.webCookie
            val cookieAcquire = cookieAcquire
            if (cookie != null && cookieAcquire != null && error.status != 401) {
                // The transfer route categorically rejects this bearer (#18). Fall through to the
                // imported web session instead of retrying a request the server will not honor.
                return@withLock finish(source, cookieAcquire(cookie, forceRefresh))
            }
            if (error.status != 401) throw error
            val owner = source.owner
            val current = sourceToken(false)
            check(current.owner == owner) { "Sign-in changed while playback authorization was being prepared" }
            source = if (!current.matches(source)) current else sourceToken(true)
            check(source.owner == owner) { "Sign-in changed while playback authorization was being prepared" }
            try { acquire(source.accessToken, true) }
            catch (retry: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
                val retryCookie = source.webCookie
                if (retry.stage == PlaybackAuthorizationClient.Stage.TRANSFER &&
                    retry.failure == PlaybackAuthorizationClient.Failure.HTTP && retryCookie != null &&
                    cookieAcquire != null) {
                    return@withLock finish(source, cookieAcquire(retryCookie, true))
                }
                throw retry
            }
        }
        finish(source, credentials)
    }

    companion object {
        fun create(tokens: SessionTokens, device: WebClientDevice): PlaybackAuthorizationProvider {
            return PlaybackAuthorizationProvider(
                sourceToken = { forceRefresh ->
                    if (!tokens.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
                    val owner = tokens.username()
                    val accessToken = tokens.accessToken(forceRefresh)
                    check(tokens.username() == owner) { "Sign-in changed while playback authorization was being prepared" }
                    Source(owner, accessToken, tokens.webCookie())
                },
                acquire = { bearer, forceRefresh ->
                    PlaybackAuthorizationClient().connect(bearer).use { session -> derive(session, device, forceRefresh) }
                },
                cookieAcquire = { spDc, forceRefresh ->
                    PlaybackAuthorizationClient().connectWithCookie(spDc).use { session ->
                        derive(session, device, forceRefresh)
                    }
                },
            )
        }

        private suspend fun derive(
            session: PlaybackAuthorizationClient.Session,
            device: WebClientDevice,
            forceRefresh: Boolean,
        ): Credentials {
            val page = session.configuration()
            val script = readPublicScript(session.publicMainScriptUrls.single())
            val configuration = PublicWebTokenConfiguration.parse(script)
            try {
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
                return session.withAccessToken { accessToken -> Credentials(accessToken, client, refreshAt) }
            } finally {
                configuration.close()
            }
        }

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
