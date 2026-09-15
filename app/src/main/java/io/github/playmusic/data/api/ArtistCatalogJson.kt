package io.github.playmusic.data.api

import io.github.playmusic.data.api.CatalogJson.objects
import io.github.playmusic.data.model.ArtistPage
import io.github.playmusic.data.model.ArtistRelease
import io.github.playmusic.data.model.ArtistReleaseType
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import org.json.JSONObject

/** The public artist route uses release groups, playlist unions and direct artist entities. */
internal object ArtistCatalogJson {
    fun overview(artist: JSONObject, releases: List<ArtistRelease>, tracks: List<SpotifyContent>): ArtistPage {
        val stats = artist.getJSONObject("stats")
        val biography = artist.getJSONObject("profile").optJSONObject("biography")
        val related = artist.getJSONObject("relatedContent")
        val featuring = playlists(related.getJSONObject("featuringV2"))
        val discovered = playlists(related.getJSONObject("discoveredOnV2"))
        return ArtistPage(
            isFollowed = artist.getBoolean("saved"),
            monthlyListeners = stats.nonnegativeLong("monthlyListeners"),
            followers = stats.nonnegativeLong("followers"),
            worldRank = stats.nonnegativeLong("worldRank")?.also { require(it <= Int.MAX_VALUE) }?.toInt(),
            biography = biography?.nullableText("text"),
            biographySource = biography?.nullableText("type"),
            discography = releases,
            appearsOn = releaseGroups(related.getJSONObject("appearsOn")).map { it.content },
            featuringPlaylists = featuring.items,
            discoveredOnPlaylists = discovered.items,
            suggestedArtists = related.getJSONObject("relatedArtists").getJSONArray("items").objects()
                .map { CatalogJson.content(it, ContentKind.ARTIST) },
            songRadioSeeds = tracks,
            unavailableRelatedItems = featuring.unavailable + discovered.unavailable,
        )
    }

    fun releaseGroups(page: JSONObject): List<ArtistRelease> = page.getJSONArray("items").objects().mapNotNull { group ->
        val releases = group.getJSONObject("releases").getJSONArray("items")
        // The service groups editions and explicitly selects the first available edition on its own artist page.
        if (releases.length() == 0) return@mapNotNull null
        val album = releases.getJSONObject(0)
        val type = album.getString("type")
        ArtistRelease(CatalogJson.content(album, ContentKind.ALBUM),
            ArtistReleaseType.entries.firstOrNull { it.name == type } ?: error("Unknown artist release type"))
    }

    private data class RelatedPlaylists(val items: List<SpotifyContent>, val unavailable: Int)

    private fun playlists(page: JSONObject): RelatedPlaylists {
        var unavailable = 0
        val items = page.getJSONArray("items").objects().mapNotNull { wrapper ->
            val value = wrapper.getJSONObject("data")
            val type = value.getString("__typename")
            when (type) {
                "Playlist" -> CatalogJson.content(value, ContentKind.PLAYLIST)
                // This is an item-level union in these two shelves, not a request-level GraphQL error.
                // The public artist consumer filters non-Playlist items; retain an explicit failure count for the UI.
                "GenericError", "NotFound", "RestrictedContent" -> { unavailable++; null }
                else -> error("Unsupported artist playlist result: ${type.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}")) } ?: "invalid type"}")
            }
        }
        return RelatedPlaylists(items, unavailable)
    }

    private fun JSONObject.nullableText(key: String): String? = if (isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    private fun JSONObject.nonnegativeLong(key: String): Long? {
        if (isNull(key)) return null
        val number = get(key)
        val value = (number as? Number)?.toString()?.toLongOrNull()
        require(value != null && value >= 0) { "Invalid artist statistic" }
        return value
    }
}
