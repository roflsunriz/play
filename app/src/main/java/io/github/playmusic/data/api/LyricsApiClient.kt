package io.github.playmusic.data.api

import io.github.playmusic.data.model.LyricsLine
import io.github.playmusic.data.model.LyricsSyncType
import io.github.playmusic.data.model.TrackLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

fun interface LyricsSource {
    /** Null means the service explicitly returned 404, not a network or decoding failure. */
    suspend fun lyrics(trackUri: String): TrackLyrics?
}

/**
 * Wire contract cross-checked 2026-09-15 against public implementation source:
 * https://github.com/Spotui/Spotui/blob/main/spotify/src/main/kotlin/com/metrolist/spotify/Spotify.kt
 * https://github.com/akashrchandran/spotify-lyrics-api
 * Official bundle: https://open.spotifycdn.com/cdn/build/web-player/web-player.bca09f6d.js
 * Modules 65509 (request), 17547 (timing and provider); the renderer also checks capStatus.
 * Response content belongs to the provider; only synthetic fixtures belong in this repository.
 */
class LyricsApiClient(
    private val session: SessionTokens,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : LyricsSource {
    override suspend fun lyrics(trackUri: String): TrackLyrics? = withContext(Dispatchers.IO) {
        require(trackUri.matches(TRACK_URI)) { "Lyrics require a valid track URI" }
        if (!session.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
        val uri = URI("$ENDPOINT/${trackUri.substringAfterLast(':')}?format=json&vocalRemoval=false&market=from_token")
        var response = execute(uri, session.accessToken())
        if (response.first == 401) {
            currentCoroutineContext().ensureActive()
            response = execute(uri, session.accessToken(forceRefresh = true))
        }
        currentCoroutineContext().ensureActive()
        when (response.first) {
            404 -> null
            200 -> LyricsJson.parse(trackUri, response.second)
            else -> throw SpotifyApiException(response.first, "Lyrics request failed")
        }
    }

    private fun execute(uri: URI, token: String): Pair<Int, String> {
        val connection = openConnection(uri)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("App-Platform", "WebPlayer")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            // Error bodies may contain account data and must not enter logs or exceptions.
            if (status != 200) return status to ""
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 1_048_576) { "Lyrics response exceeds size limit" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            return status to body
        } finally { connection.disconnect() }
    }

    private companion object {
        val TRACK_URI = Regex("spotify:track:[0-9A-Za-z]{22}")
        const val ENDPOINT = "https://spclient.wg.spotify.com/color-lyrics/v2/track"
    }
}

internal object LyricsJson {
    fun parse(trackUri: String, body: String): TrackLyrics {
        // JSON exceptions can echo the response, so never keep their message or cause.
        try {
            val lyrics = JSONObject(body).getJSONObject("lyrics")
            val sync = when (lyrics.getString("syncType")) {
                "LINE_SYNCED" -> LyricsSyncType.LINE_SYNCED
                "SYLLABLE_SYNCED" -> LyricsSyncType.SYLLABLE_SYNCED
                "UNSYNCED" -> LyricsSyncType.UNSYNCED
                else -> error("Unsupported lyrics timing")
            }
            val source = lyrics.getJSONArray("lines")
            require(source.length() <= 10_000)
            val lines = (0 until source.length()).map { index ->
                val line = source.getJSONObject(index)
                val words = line.get("words") as String
                val start = if (sync != LyricsSyncType.UNSYNCED) timestamp(line, "startTimeMs") else null
                // The official renderer uses the next line's start; endTimeMs is not a line boundary.
                LyricsLine(words, start)
            }
            if (sync != LyricsSyncType.UNSYNCED) {
                require(lines.zipWithNext().all { (before, after) -> checkNotNull(before.startTimeMs) <= checkNotNull(after.startTimeMs) })
            }
            val provider = lyrics.optString("providerDisplayName").ifBlank { lyrics.optString("provider") }
            return TrackLyrics(trackUri, sync, lines, provider, lyrics.optString("providerLyricsId"),
                lyrics.optString("language"), lyrics.optString("capStatus") == "CAPPED")
        } catch (_: Exception) {
            throw IllegalArgumentException("Lyrics response is invalid or uses unsupported timing")
        }
    }

    private fun timestamp(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is String || value is Number)
        return requireNotNull(value.toString().toLongOrNull()).also { require(it >= 0) }
    }
}
