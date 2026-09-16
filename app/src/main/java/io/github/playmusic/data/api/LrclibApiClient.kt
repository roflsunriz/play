package io.github.playmusic.data.api

import io.github.playmusic.BuildConfig
import io.github.playmusic.data.model.LyricsLine
import io.github.playmusic.data.model.LyricsSyncType
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.TrackLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Secondary lyrics source without authentication. Responses stay in memory only and are
 * never persisted, logged, or embedded in fixtures: only the request identity, the provider
 * name and the HTTP outcome may leave this class.
 */
class LrclibApiClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
) {
    /** Null means no usable lyrics for this track: not found, instrumental, empty, or mismatched. */
    suspend fun lyrics(content: SpotifyContent): TrackLyrics? = withContext(Dispatchers.IO) {
        require(content.uri.matches(TRACK_URI)) { "Lyrics require a valid track URI" }
        require(content.title.isNotBlank()) { "Lyrics require a track title" }
        val artists = content.artists.map { it.name }.filter { it.isNotBlank() }
        val artist = artists.firstOrNull() ?: content.subtitle.substringBefore(',').trim()
        require(artist.isNotBlank()) { "Lyrics require an artist name" }
        val query = StringBuilder("$ENDPOINT?track_name=${encode(content.title)}&artist_name=${encode(artist)}")
        if (content.albumTitle?.isNotBlank() == true) query.append("&album_name=${encode(content.albumTitle)}")
        val durationSeconds = (content.durationMs / 1_000).takeIf { it > 0 }
        if (durationSeconds != null) query.append("&duration=$durationSeconds")
        val response = get(URI(query.toString()))
        currentCoroutineContext().ensureActive()
        if (response == null || !matchLrclib(content, artists, response)) return@withContext null
        LrclibLyrics.parse(content.uri, response)
    }

    private suspend fun get(uri: URI): JSONObject? {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (status, body, retryAfterMs) = execute(uri)
            currentCoroutineContext().ensureActive()
            when {
                status == 404 -> return null
                status == 429 || status == 503 -> {
                    if (attempt >= MAX_RETRIES) throw SpotifyApiException(status, "Lyrics request failed")
                    sleepMs(retryAfterMs ?: defaultRetryAfterMs(attempt))
                    attempt++
                }
                status == 200 -> return body?.let { JSONObject(it) }
                    ?: throw IllegalArgumentException("Lyrics response is invalid")
                else -> throw SpotifyApiException(status, "Lyrics request failed")
            }
        }
    }

    private fun execute(uri: URI): Triple<Int, String?, Long?> {
        val connection = openConnection(uri)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status == 429 || status == 503) {
                val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                    ?.takeIf { it >= 0 }?.times(1_000)?.coerceAtMost(MAX_RETRY_AFTER_MS)
                return Triple(status, null, retryAfter)
            }
            if (status != 200) return Triple(status, null, null)
            val contentType = connection.getHeaderField("Content-Type").orEmpty()
            require(contentType.contains("json", ignoreCase = true)) { "Lyrics response is not JSON" }
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
            return Triple(status, body, null)
        } finally { connection.disconnect() }
    }

    private companion object {
        val TRACK_URI = Regex("spotify:track:[0-9A-Za-z]{22}")
        const val ENDPOINT = "https://lrclib.net/api/get"
        const val MAX_RETRIES = 2
        const val MAX_RETRY_AFTER_MS = 30_000L
        val USER_AGENT = "Play/${BuildConfig.VERSION_NAME}"
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun defaultRetryAfterMs(attempt: Int): Long = 1_000L shl attempt.coerceIn(0, 10)
    }
}

/** Normalizes names for track matching. */
internal fun normalizeLrclibName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

/** Matches the returned record against the requested track so another song's lyrics never show. */
internal fun matchLrclib(
    content: SpotifyContent,
    artists: List<String>,
    response: JSONObject,
): Boolean {
    if (normalizeLrclibName(response.optString("trackName")) != normalizeLrclibName(content.title)) return false
    val candidates = (artists + content.subtitle.split(',')).map(::normalizeLrclibName)
        .filter { it.isNotBlank() }.distinct()
    if (candidates.none { it == normalizeLrclibName(response.optString("artistName")) }) return false
    val albumName = response.optString("albumName")
    if (albumName.isNotBlank() && content.albumTitle?.isNotBlank() == true &&
        normalizeLrclibName(albumName) != normalizeLrclibName(content.albumTitle)) return false
    val expectedSeconds = (content.durationMs / 1_000).takeIf { it > 0 }
    val actualSeconds = (response.opt("duration") as? Number)?.toLong()
    if (expectedSeconds != null && actualSeconds != null && actualSeconds > 0 &&
        kotlin.math.abs(expectedSeconds - actualSeconds) > 10) return false
    return true
}

/** Parses LRCLIB plain/synced payloads into the shared lyrics model. */
internal object LrclibLyrics {
    fun parse(trackUri: String, response: JSONObject): TrackLyrics? {
        // JSON exceptions can echo the response, so never keep their message or cause.
        try {
            if (response.optBoolean("instrumental", false)) return null
            val synced = response.optString("syncedLyrics")
            val plain = response.optString("plainLyrics")
            val lines = if (synced.isNotBlank()) parseSynced(synced) else emptyList()
            if (lines.isNotEmpty()) {
                return TrackLyrics(trackUri, LyricsSyncType.LINE_SYNCED, lines, "LRCLIB",
                    response.optString("id"))
            }
            val untyped = plain.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (untyped.isEmpty()) return null
            return TrackLyrics(trackUri, LyricsSyncType.UNSYNCED, untyped.map { LyricsLine(it) }, "LRCLIB",
                response.optString("id"))
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseSynced(synced: String): List<LyricsLine> {
        var offsetMs = 0L
        val timed = mutableListOf<LyricsLine>()
        for (raw in synced.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val shift = OFFSET.matchEntire(line)
            if (shift != null) {
                offsetMs = shift.groupValues[1].toLong()
                continue
            }
            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val words = TIMESTAMP.replace(line, "").trim()
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                val fraction = stamp.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').take(3).toLong()
                timed += LyricsLine(words, (minutes * 60 + seconds) * 1_000 + fraction + offsetMs)
            }
        }
        require(timed.size <= 10_000)
        return timed.sortedWith(compareBy<LyricsLine> { it.startTimeMs }.thenBy { it.words })
    }

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")
    private val OFFSET = Regex("""\[offset:([+-]?\d+)\]""")
}
