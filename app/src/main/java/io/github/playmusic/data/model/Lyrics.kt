package io.github.playmusic.data.model

enum class LyricsSyncType { LINE_SYNCED, SYLLABLE_SYNCED, UNSYNCED }

data class LyricsLine(val words: String, val startTimeMs: Long? = null)

/** Response text is kept in memory only, with the provider's attribution intact. */
data class TrackLyrics(
    val trackUri: String,
    val syncType: LyricsSyncType,
    val lines: List<LyricsLine>,
    val providerDisplayName: String,
    val providerLyricsId: String = "",
    val language: String = "",
    val isCapped: Boolean = false,
) {
    val isTimeSynced: Boolean get() = syncType != LyricsSyncType.UNSYNCED

    fun activeLine(positionMs: Long): Int {
        if (!isTimeSynced || positionMs < 0) return -1
        // Upper bound includes all lines with the same timestamp, without advancing early.
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (checkNotNull(lines[middle].startTimeMs) <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }
}
