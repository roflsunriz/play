package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentArtist
import io.github.playmusic.data.model.SpotifyContent
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Parses the observed catalog unions, including the different wrappers used by search and albums. */
internal object CatalogJson {
    fun content(value: JSONObject, kind: ContentKind, album: SpotifyContent? = null): SpotifyContent {
        val entity = unwrap(value)
        val rawUri = entity.getString("uri")
        // The service includes this navigation card in Genre search results. Keep its existing library route.
        if (kind == ContentKind.GENRE && entity.optString("__typename") == "Genre" && rawUri == "spotify:user:@:collection") {
            return SpotifyRepository.likedSongsContent(entity.getString("name"))
        }
        val uri = if (kind == ContentKind.GENRE && !rawUri.contains(':')) "spotify:genre:$rawUri" else rawUri
        require(uri.startsWith("spotify:${kind.name.lowercase()}:") || (kind == ContentKind.GENRE && uri.startsWith("spotify:page:"))) { "Unexpected catalog entity" }
        val title = if (kind == ContentKind.ARTIST) entity.getJSONObject("profile").getString("name") else entity.getString("name")
        require(kind == ContentKind.PLAYLIST || title.isNotBlank()) { "Catalog title is missing" }
        val parent = entity.optJSONObject("albumOfTrack")
        val artistItems = entity.optJSONObject("artists")?.optJSONArray("items")?.objects()
            ?: (entity.optJSONObject("firstArtist")?.optJSONArray("items")?.objects().orEmpty() +
                entity.optJSONObject("otherArtists")?.optJSONArray("items")?.objects().orEmpty())
        val artistEntities = artistItems.map { it.optJSONObject("data") ?: it }
        val artistNames = artistEntities.mapNotNull { it.optJSONObject("profile")?.text("name") ?: it.text("name") }
        val artists = artistEntities.mapNotNull { artist ->
            val artistUri = artist.text("uri")?.takeIf { it.startsWith("spotify:artist:") } ?: return@mapNotNull null
            val artistName = artist.optJSONObject("profile")?.text("name") ?: artist.text("name") ?: return@mapNotNull null
            ContentArtist(artistUri, artistName)
        }
        val owner = entity.optJSONObject("ownerV2")?.optJSONObject("data")
        val ownerName = owner?.text("displayName") ?: owner?.text("name")
        val ownerUsername = owner?.text("username") ?: owner?.text("uri")
            ?.takeIf { it.startsWith("spotify:user:") }?.let(UserProfileClient::usernameFromUri)
        return SpotifyContent(
            id = uri.substringAfterLast(':'),
            uri = uri,
            title = title,
            subtitle = artistNames.joinToString(", ").ifBlank { ownerName ?: entity.optJSONObject("publisher")?.text("name").orEmpty() },
            imageUrl = cover(parent ?: entity) ?: album?.imageUrl,
            kind = kind,
            durationMs = entity.optJSONObject("duration")?.optLong("totalMilliseconds", 0)?.coerceAtLeast(0) ?: 0,
            albumUri = parent?.text("uri") ?: album?.uri,
            albumTitle = parent?.text("name") ?: album?.title,
            isPlayable = entity.optJSONObject("playability")?.let { if (it.has("playable")) it.getBoolean("playable") else null },
            ownerName = ownerName,
            ownerUsername = ownerUsername,
            description = entity.opt("description") as? String ?: entity.optJSONObject("profile")?.optJSONObject("biography")?.text("text"),
            trackCount = entity.optJSONObject("tracksV2")?.count("totalCount"),
            releaseDate = releaseDate(parent ?: entity) ?: album?.releaseDate,
            artists = artists.ifEmpty { album?.artists.orEmpty() },
        )
    }

    fun search(data: JSONObject, kind: ContentKind): List<SpotifyContent> {
        val key = when (kind) {
            ContentKind.ALBUM -> "albumsV2"
            ContentKind.TRACK -> "tracksV2"
            ContentKind.PLAYLIST -> "playlists"
            ContentKind.ARTIST -> "artists"
            ContentKind.SHOW -> "podcasts"
            ContentKind.EPISODE -> "episodes"
            ContentKind.GENRE -> "genres"
        }
        val results = data.getJSONObject("searchV2")
        val page = results.getJSONObject(key)
        val items = page.getJSONArray("items")
        return (0 until items.length()).mapNotNull { index ->
            if (items.isNull(index)) null else {
                val entity = unwrap(items.getJSONObject(index), allowUnavailable = true)
                // Podcast search is consumed as podcasts by the public client, even when audiobook inclusion is enabled.
                entity?.takeUnless { kind == ContentKind.SHOW && it.optString("__typename") == "Audiobook" }?.let { content(it, kind) }
            }
        }
    }

    fun tracks(data: JSONObject): List<SpotifyContent> = data.getJSONArray("tracks").objects().map { content(it, ContentKind.TRACK) }

    fun albumTracks(album: JSONObject, parentAlbum: SpotifyContent): List<SpotifyContent> =
        album.getJSONObject("tracksV2").getJSONArray("items").objects().map { content(it, ContentKind.TRACK, parentAlbum) }

    fun episodes(page: JSONObject): List<SpotifyContent> = page.getJSONArray("items").objects().mapNotNull {
        unwrap(it.getJSONObject("entity"), allowUnavailable = true)?.let { episode -> content(episode, ContentKind.EPISODE) }
    }

    fun browseItems(page: JSONObject): List<SpotifyContent> = page.getJSONArray("items").objects().mapNotNull { item ->
        val wrapper = item.getJSONObject("content")
        if (wrapper.optString("__typename") in setOf("UnknownType", "NoContent", "ConcertResponseWrapper")) return@mapNotNull null
        val entity = wrapper.getJSONObject("data")
        val kind = when (entity.getString("__typename")) {
            "Album" -> ContentKind.ALBUM
            "Artist" -> ContentKind.ARTIST
            "Track" -> ContentKind.TRACK
            "Playlist" -> ContentKind.PLAYLIST
            "Podcast" -> ContentKind.SHOW
            "Episode" -> ContentKind.EPISODE
            "BrowseSectionContainer" -> {
                val representation = entity.optJSONObject("data")?.optJSONObject("cardRepresentation") ?: return@mapNotNull null
                val title = representation.optJSONObject("title")?.text("transformedLabel") ?: return@mapNotNull null
                val uri = item.getString("uri")
                require(uri.startsWith("spotify:page:") || uri.startsWith("spotify:genre:")) { "Unexpected browse link" }
                return@mapNotNull SpotifyContent(uri.substringAfterLast(':'), uri, title, "", cover(entity), ContentKind.GENRE)
            }
            "NotFound", "RestrictedContent", "BrowseClientFeature", "BrowseSpacesHub", "BrowseExternalHref",
            "User", "Audiobook", "Chapter", "Merch", "ArtistConcerts" -> return@mapNotNull null
            else -> error("Unsupported browse entity")
        }
        content(entity, kind)
    }

    fun browseHeader(page: JSONObject, original: SpotifyContent): SpotifyContent {
        val header = page.optJSONObject("header") ?: return original
        val background = header.optJSONObject("backgroundImage")
        return original.copy(title = header.optJSONObject("title")?.text("transformedLabel") ?: original.title,
            subtitle = header.optJSONObject("subtitle")?.text("transformedLabel") ?: original.subtitle,
            imageUrl = background?.let { cover(JSONObject().put("coverArt", it)) } ?: original.imageUrl)
    }

    fun releaseDate(album: JSONObject): String? = album.optJSONObject("date")?.let { date ->
        date.text("isoString")?.substringBefore('T') ?: date.optInt("year", 0).takeIf { it > 0 }?.toString()
    }

    private fun unwrap(value: JSONObject): JSONObject = checkNotNull(unwrap(value, allowUnavailable = false))

    private fun unwrap(value: JSONObject, allowUnavailable: Boolean): JSONObject? {
        var current = value
        repeat(4) {
            if (allowUnavailable && current.optString("__typename") in setOf("NotFound", "RestrictedContent")) return null
            if (current.has("uri") && (current.has("name") || current.optJSONObject("profile")?.has("name") == true)) return current
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
            entity.optJSONObject("visuals")?.optJSONObject("avatarImage")?.optJSONArray("sources")?.objects().orEmpty() +
            entity.optJSONObject("image")?.optJSONArray("sources")?.objects().orEmpty() +
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
