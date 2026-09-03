package io.github.playmusic.data.model

enum class ContentKind {
    PLAYLIST,
    ALBUM,
    TRACK,
    ARTIST,
    SHOW,
    EPISODE,
}

data class SpotifyContent(
    val id: String,
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val kind: ContentKind,
    val previewUrl: String? = null,
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
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val deviceName: String? = null,
)

data class AuthSession(
    val username: String,
    val accessToken: String,
    val storedCredential: ByteArray?,
    val expiresAtEpochMs: Long,
) {
    fun expiresSoon(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs - nowEpochMs <= TOKEN_REFRESH_SKEW_MS

    private companion object {
        const val TOKEN_REFRESH_SKEW_MS = 60_000L
    }
}