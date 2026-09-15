package io.github.playmusic.data.model

enum class ArtistReleaseType { ALBUM, SINGLE, EP, COMPILATION, UNKNOWN }

data class ArtistRelease(val content: SpotifyContent, val type: ArtistReleaseType)

/** Service artist data. Null statistics mean not supplied, rather than zero listeners. */
data class ArtistPage(
    val isFollowed: Boolean,
    val monthlyListeners: Long? = null,
    val followers: Long? = null,
    val worldRank: Int? = null,
    val biography: String? = null,
    val biographySource: String? = null,
    val discography: List<ArtistRelease> = emptyList(),
    val appearsOn: List<SpotifyContent> = emptyList(),
    val featuringPlaylists: List<SpotifyContent> = emptyList(),
    val discoveredOnPlaylists: List<SpotifyContent> = emptyList(),
    val suggestedArtists: List<SpotifyContent> = emptyList(),
    val songRadioSeeds: List<SpotifyContent> = emptyList(),
    val unavailableRelatedItems: Int = 0,
)
