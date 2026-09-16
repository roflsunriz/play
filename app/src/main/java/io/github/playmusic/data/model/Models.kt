package io.github.playmusic.data.model

enum class ContentKind {
    PLAYLIST,
    ALBUM,
    TRACK,
    ARTIST,
    SHOW,
    EPISODE,
    GENRE,
}

enum class SearchFilter(val kinds: List<ContentKind>) {
    ALL(listOf(ContentKind.TRACK, ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.ARTIST, ContentKind.SHOW, ContentKind.EPISODE, ContentKind.GENRE)),
    TRACKS(listOf(ContentKind.TRACK)),
    PLAYLISTS(listOf(ContentKind.PLAYLIST)),
    ALBUMS(listOf(ContentKind.ALBUM)),
    ARTISTS(listOf(ContentKind.ARTIST)),
    PODCASTS(listOf(ContentKind.SHOW, ContentKind.EPISODE)),
    GENRES(listOf(ContentKind.GENRE)),
}

data class ContentArtist(val uri: String, val name: String)

enum class DetailSort {
    TRACK_ORDER, TRACK_REVERSE, TITLE, TITLE_DESCENDING, ARTIST, ARTIST_DESCENDING,
    ALBUM, ALBUM_DESCENDING, ADDED_NEWEST, ADDED_OLDEST, PLAYCOUNT, PLAYCOUNT_ASCENDING;

    companion object {
        fun options(kind: ContentKind): List<DetailSort> = when (kind) {
            ContentKind.PLAYLIST -> listOf(ADDED_NEWEST, ADDED_OLDEST, TITLE, TITLE_DESCENDING,
                ARTIST, ARTIST_DESCENDING, ALBUM, ALBUM_DESCENDING)
            ContentKind.ALBUM -> listOf(TRACK_ORDER, TRACK_REVERSE, TITLE, TITLE_DESCENDING,
                PLAYCOUNT, PLAYCOUNT_ASCENDING)
            else -> listOf(TRACK_ORDER)
        }
    }
}

data class SpotifyContent(
    val id: String,
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val kind: ContentKind,
    val durationMs: Long = 0,
    val albumUri: String? = null,
    val albumTitle: String? = null,
    val isPlayable: Boolean? = null,
    val ownerName: String? = null,
    val ownerUsername: String? = null,
    val description: String? = null,
    val trackCount: Int? = null,
    val releaseDate: String? = null,
    val artists: List<ContentArtist> = emptyList(),
    val addedAtMs: Long? = null,
    val playcount: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
)

data class ContentDetail(
    val content: SpotifyContent,
    val tracks: List<SpotifyContent> = emptyList(),
    val totalTracks: Int = tracks.size,
    val releaseDate: String? = null,
    val playlistMetadata: PlaylistMetadata? = null,
    val relatedContent: List<SpotifyContent> = emptyList(),
    val artistPage: ArtistPage? = null,
)

enum class RepeatMode(val apiValue: String) {
    OFF("off"),
    CONTEXT("context"),
    TRACK("track");

    fun next(): RepeatMode = when (this) {
        OFF -> CONTEXT
        CONTEXT -> TRACK
        TRACK -> OFF
    }

    companion object {
        fun fromApi(value: String?): RepeatMode = entries.firstOrNull { it.apiValue == value } ?: OFF
    }
}

data class Playback(
    val item: SpotifyContent? = null,
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val deviceName: String? = null,
)

data class AuthSession(
    val username: String,
    val accessToken: String,
    val storedCredential: ByteArray?,
    val expiresAtEpochMs: Long,
    val refreshToken: String? = null,
) {
    fun expiresSoon(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs - nowEpochMs <= TOKEN_REFRESH_SKEW_MS

    private companion object {
        const val TOKEN_REFRESH_SKEW_MS = 60_000L
    }
}
