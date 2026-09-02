package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent
import org.json.JSONArray
import org.json.JSONObject

class SpotifyRepository(private val api: SpotifyApiClient) {
    suspend fun library(kind: ContentKind): List<SpotifyContent> = when (kind) {
        ContentKind.PLAYLIST -> parsePaging(api.get("/me/playlists", mapOf("limit" to PAGE_SIZE)).body) {
            parsePlaylist(it)
        }
        ContentKind.ALBUM -> parsePaging(api.get("/me/albums", mapOf("limit" to PAGE_SIZE)).body) {
            parseAlbum(it.optJSONObject("album") ?: it)
        }
        ContentKind.TRACK -> parsePaging(api.get("/me/tracks", mapOf("limit" to PAGE_SIZE)).body) {
            parseTrack(it.optJSONObject("track") ?: it)
        }
    }

    suspend fun search(query: String): List<SpotifyContent> {
        if (query.isBlank()) return emptyList()
        val json = JSONObject(
            api.get(
                "/search",
                mapOf("q" to query.trim(), "type" to "playlist,album,track", "limit" to SEARCH_LIMIT),
            ).body,
        )
        return buildList {
            addAll(parseContainer(json.optJSONObject("playlists"), ::parsePlaylist))
            addAll(parseContainer(json.optJSONObject("albums"), ::parseAlbum))
            addAll(parseContainer(json.optJSONObject("tracks"), ::parseTrack))
        }
    }

    suspend fun playback(): Playback {
        val response = api.get("/me/player")
        if (response.status == 204 || response.body.isBlank()) return Playback()
        val json = JSONObject(response.body)
        val item = json.optJSONObject("item")?.let(::parseTrack)
        val device = json.optJSONObject("device")
        return Playback(
            item = item,
            progressMs = json.optLong("progress_ms").coerceAtLeast(0),
            durationMs = item?.let { json.optJSONObject("item")?.optLong("duration_ms") } ?: 0,
            isPlaying = json.optBoolean("is_playing"),
            shuffle = json.optBoolean("shuffle_state"),
            repeatMode = RepeatMode.fromApi(json.optString("repeat_state")),
            deviceName = device?.optString("name")?.ifBlank { null },
        )
    }

    suspend fun play(content: SpotifyContent) {
        val body = JSONObject()
        if (content.kind == ContentKind.TRACK) {
            body.put("uris", JSONArray().put(content.uri))
        } else {
            body.put("context_uri", content.uri)
        }
        api.put("/me/player/play", body = body)
    }

    suspend fun resume() {
        api.put("/me/player/play")
    }

    suspend fun pause() {
        api.put("/me/player/pause")
    }

    suspend fun next() {
        api.post("/me/player/next")
    }

    suspend fun previous() {
        api.post("/me/player/previous")
    }

    suspend fun seek(positionMs: Long) {
        api.put("/me/player/seek", mapOf("position_ms" to positionMs.coerceAtLeast(0).toString()))
    }

    suspend fun setRepeat(mode: RepeatMode) {
        api.put("/me/player/repeat", mapOf("state" to mode.apiValue))
    }

    suspend fun setShuffle(enabled: Boolean) {
        api.put("/me/player/shuffle", mapOf("state" to enabled.toString()))
    }

    private fun parsePaging(body: String, parser: (JSONObject) -> SpotifyContent?): List<SpotifyContent> =
        parseContainer(JSONObject(body), parser)

    private fun parseContainer(
        container: JSONObject?,
        parser: (JSONObject) -> SpotifyContent?,
    ): List<SpotifyContent> {
        val items = container?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parser(item)?.let(::add)
            }
        }
    }

    private fun parsePlaylist(json: JSONObject): SpotifyContent? {
        val id = json.optString("id").ifBlank { return null }
        return SpotifyContent(
            id = id,
            uri = json.optString("uri").ifBlank { "spotify:playlist:$id" },
            title = json.optString("name").ifBlank { "Playlist" },
            subtitle = json.optJSONObject("owner")?.optString("display_name").orEmpty(),
            imageUrl = firstImage(json),
            kind = ContentKind.PLAYLIST,
        )
    }

    private fun parseAlbum(json: JSONObject): SpotifyContent? {
        val id = json.optString("id").ifBlank { return null }
        return SpotifyContent(
            id = id,
            uri = json.optString("uri").ifBlank { "spotify:album:$id" },
            title = json.optString("name").ifBlank { "Album" },
            subtitle = artistNames(json.optJSONArray("artists")),
            imageUrl = firstImage(json),
            kind = ContentKind.ALBUM,
        )
    }

    private fun parseTrack(json: JSONObject): SpotifyContent? {
        val id = json.optString("id").ifBlank { return null }
        val album = json.optJSONObject("album")
        return SpotifyContent(
            id = id,
            uri = json.optString("uri").ifBlank { "spotify:track:$id" },
            title = json.optString("name").ifBlank { "Track" },
            subtitle = artistNames(json.optJSONArray("artists")),
            imageUrl = album?.let(::firstImage),
            kind = ContentKind.TRACK,
        )
    }

    private fun firstImage(json: JSONObject): String? =
        json.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.ifBlank { null }

    private fun artistNames(artists: JSONArray?): String {
        if (artists == null) return ""
        return buildList {
            for (index in 0 until artists.length()) {
                artists.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString(", ")
    }

    private companion object {
        const val PAGE_SIZE = "50"
        const val SEARCH_LIMIT = "10"
    }
}
