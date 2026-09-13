package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Parses the observed catalog unions, including the different wrappers used by search and albums. */
internal object CatalogJson {
    fun content(value: JSONObject, kind: ContentKind, album: SpotifyContent? = null): SpotifyContent {
        val entity = unwrap(value)
        val uri = entity.getString("uri")
        require(uri.startsWith("spotify:${kind.name.lowercase()}:")) { "Unexpected catalog entity" }
        val title = entity.getString("name")
        require(kind == ContentKind.PLAYLIST || title.isNotBlank()) { "Catalog title is missing" }
        val parent = entity.optJSONObject("albumOfTrack")
        val artistItems = entity.optJSONObject("artists")?.optJSONArray("items")?.objects()
            ?: (entity.optJSONObject("firstArtist")?.optJSONArray("items")?.objects().orEmpty() +
                entity.optJSONObject("otherArtists")?.optJSONArray("items")?.objects().orEmpty())
        val artists = artistItems.mapNotNull { it.optJSONObject("profile")?.text("name") ?: it.text("name") }
        val owner = entity.optJSONObject("ownerV2")?.optJSONObject("data")
        val ownerName = owner?.text("displayName") ?: owner?.text("name") ?: owner?.text("username")
        return SpotifyContent(
            id = uri.substringAfterLast(':'),
            uri = uri,
            title = title,
            subtitle = artists.joinToString(", ").ifBlank { ownerName.orEmpty() },
            imageUrl = cover(parent ?: entity) ?: album?.imageUrl,
            kind = kind,
            durationMs = entity.optJSONObject("duration")?.optLong("totalMilliseconds", 0)?.coerceAtLeast(0) ?: 0,
            albumUri = parent?.text("uri") ?: album?.uri,
            albumTitle = parent?.text("name") ?: album?.title,
            isPlayable = entity.optJSONObject("playability")?.let { if (it.has("playable")) it.getBoolean("playable") else null },
            ownerName = ownerName,
            description = entity.opt("description") as? String,
            trackCount = entity.optJSONObject("tracksV2")?.count("totalCount"),
            releaseDate = releaseDate(parent ?: entity) ?: album?.releaseDate,
        )
    }

    fun search(data: JSONObject, kind: ContentKind): List<SpotifyContent> {
        val key = when (kind) {
            ContentKind.ALBUM -> "albumsV2"
            ContentKind.TRACK -> "tracksV2"
            ContentKind.PLAYLIST -> "playlists"
            else -> error("Unsupported search kind")
        }
        val results = data.getJSONObject("searchV2")
        val page = results.getJSONObject(key)
        val items = page.getJSONArray("items")
        return (0 until items.length()).mapNotNull { index ->
            if (items.isNull(index)) null else {
                val entity = unwrap(items.getJSONObject(index), allowUnavailable = true)
                entity?.let { content(it, kind) }
            }
        }
    }

    fun tracks(data: JSONObject): List<SpotifyContent> = data.getJSONArray("tracks").objects().map { content(it, ContentKind.TRACK) }

    fun albumTracks(album: JSONObject, parentAlbum: SpotifyContent): List<SpotifyContent> =
        album.getJSONObject("tracksV2").getJSONArray("items").objects().map { content(it, ContentKind.TRACK, parentAlbum) }

    fun releaseDate(album: JSONObject): String? = album.optJSONObject("date")?.let { date ->
        date.text("isoString")?.substringBefore('T') ?: date.optInt("year", 0).takeIf { it > 0 }?.toString()
    }

    private fun unwrap(value: JSONObject): JSONObject = checkNotNull(unwrap(value, allowUnavailable = false))

    private fun unwrap(value: JSONObject, allowUnavailable: Boolean): JSONObject? {
        var current = value
        repeat(4) {
            if (allowUnavailable && current.optString("__typename") in setOf("NotFound", "RestrictedContent")) return null
            if (current.has("uri") && current.has("name")) return current
            val nested = current.optJSONObject("item") ?: current.optJSONObject("data") ?: current.optJSONObject("track")
            if (nested == null) {
                if (allowUnavailable && listOf("item", "data", "track").any { current.has(it) && current.isNull(it) }) return null
                error("Catalog entity is unavailable")
            }
            current = nested
        }
        error("Invalid catalog entity wrapper")
    }

    private fun cover(entity: JSONObject): String? {
        val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.objects().orEmpty() +
            entity.optJSONObject("images")?.optJSONArray("items")?.objects().orEmpty()
                .flatMap { it.optJSONArray("sources")?.objects().orEmpty() }
        return sources.sortedByDescending { it.optInt("width", it.optInt("maxWidth", 0)) }
            .firstNotNullOfOrNull { source -> source.text("url")?.takeIf { url ->
                runCatching { URI(url).let { it.scheme == "https" && it.host != null && it.userInfo == null } }.getOrDefault(false)
            } }
    }

    internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.count(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = (get(key) as? Number)?.toString()?.toLongOrNull()
        require(value != null && value in 0..Int.MAX_VALUE.toLong()) { "Catalog track count is invalid" }
        return value.toInt()
    }
}
