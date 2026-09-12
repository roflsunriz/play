package io.github.playmusic.data.playback

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.api.SessionTokens
import io.github.playmusic.data.api.SpotifyApiException
import io.github.playmusic.data.auth.DesktopClientProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class StreamingApiClient(
    private val session: SessionTokens,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Audio(val fileId: String, val urls: List<String>)
    private class Cached(val audio: Audio, val expiresAt: Long)
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Cached>()

    suspend fun resolve(uri: String): Audio = mutex.withLock {
        require(uri.matches(TRACK_URI)) { "Unsupported audio item" }
        if (!session.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
        val owner = session.username()
        val key = "$owner:$uri"
        cache[key]?.takeIf { now() < it.expiresAt }?.let { return@withLock it.audio }
        val metadata = request("/track-playback/v1/media/$uri", mapOf("manifestFileFormat" to "file_ids_mp4"))
        val media = metadata.getJSONObject("media")
        val entry = media.optJSONObject(uri) ?: error("Audio manifest is missing")
        val item = entry.getJSONObject("item")
        val files = item.getJSONObject("manifest").getJSONArray("file_ids_mp4")
        val file = (0 until files.length()).map(files::getJSONObject)
            .firstOrNull { it.opt("format")?.toString() == "10" }
            ?: throw IllegalStateException("Compatible audio is unavailable for this item")
        val fileId = file.getString("file_id")
        require(fileId.matches(FILE_ID)) { "Invalid audio file identity" }
        val storage = request("/storage-resolve/v2/files/audio/interactive/10/$fileId", mapOf(
            "version" to "10000000", "product" to "9", "platform" to "39", "alt" to "json"))
        val urls = storage.getJSONArray("cdnurl")
        val resolvedUrls = (0 until urls.length()).map(urls::getString).onEach(::validateAudioUrl)
        check(resolvedUrls.isNotEmpty()) { "Audio download locations are missing" }
        Audio(fileId, resolvedUrls).also { audio ->
            cache.remove(key)
            cache[key] = Cached(audio, now() + CACHE_LIFETIME_MS)
            while (cache.size > MAX_CACHED_ITEMS) cache.remove(cache.keys.first())
        }
    }

    suspend fun accessToken(forceRefresh: Boolean = false): String = session.accessToken(forceRefresh)

    private suspend fun request(path: String, query: Map<String, String>): JSONObject {
        val encoded = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val endpoint = URI("$BASE$path?$encoded")
        var response = execute(endpoint, session.accessToken())
        if (response.first == 401) response = execute(endpoint, session.accessToken(forceRefresh = true))
        if (response.first !in 200..299) throw SpotifyApiException(response.first, "Audio information request failed")
        return JSONObject(response.second)
    }

    private suspend fun execute(uri: URI, token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = openConnection(uri)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            requestHeaders.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            status to (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally { connection.disconnect() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val LICENSE_URL = "https://gae2-spclient.spotify.com/widevine-license/v1/audio/license"
        private const val BASE = "https://gae2-spclient.spotify.com"
        private const val CACHE_LIFETIME_MS = 300_000L
        private const val MAX_CACHED_ITEMS = 32
        private val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}")
        private val FILE_ID = Regex("[a-f0-9]{40}")
        val requestHeaders = DesktopClientProfile.headers

        internal fun validateAudioUrl(value: String) {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
                listOf("spotifycdn.com", "scdn.co", "akamaized.net").any { host ->
                    uri.host == host || uri.host?.endsWith(".$host") == true
                }) { "Unexpected audio download location" }
        }
    }
}
