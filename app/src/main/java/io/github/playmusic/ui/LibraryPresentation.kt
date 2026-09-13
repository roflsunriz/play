package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import java.text.Normalizer
import java.util.Locale

enum class LibrarySort {
    LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, TRACK_COUNT, DURATION, DURATION_DESCENDING;

    companion object {
        fun options(kind: ContentKind): List<LibrarySort> = when (kind) {
            ContentKind.PLAYLIST -> listOf(LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, TRACK_COUNT)
            ContentKind.TRACK -> listOf(LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, DURATION, DURATION_DESCENDING)
            else -> listOf(LIBRARY_ORDER)
        }
    }
}

internal fun presentLibrary(items: List<SpotifyContent>, query: String, sort: LibrarySort): List<SpotifyContent> {
    val words = normalizeLibraryText(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val filtered = if (words.isEmpty()) items else items.filter { item ->
        val text = normalizeLibraryText(listOfNotNull(item.title, item.ownerName, item.description,
            item.subtitle, item.albumTitle, item.trackCount?.toString()).joinToString(" "))
        words.all(text::contains)
    }
    val byTitle = compareBy<SpotifyContent> { normalizeLibraryText(it.title) }
    return when (sort) {
        LibrarySort.LIBRARY_ORDER -> filtered
        LibrarySort.TITLE -> filtered.sortedWith(byTitle)
        LibrarySort.TITLE_DESCENDING -> filtered.sortedWith(byTitle.reversed())
        LibrarySort.CREATOR -> filtered.sortedWith(compareBy<SpotifyContent> {
            normalizeLibraryText(it.ownerName ?: it.subtitle)
        }.then(byTitle))
        LibrarySort.TRACK_COUNT -> filtered.sortedWith(compareByDescending<SpotifyContent> { it.trackCount ?: -1 }.then(byTitle))
        LibrarySort.DURATION -> filtered.sortedWith(compareBy<SpotifyContent> {
            it.durationMs.takeIf { duration -> duration > 0 } ?: Long.MAX_VALUE
        }.then(byTitle))
        LibrarySort.DURATION_DESCENDING -> filtered.sortedWith(compareByDescending<SpotifyContent> { it.durationMs }.then(byTitle))
    }
}

private fun normalizeLibraryText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** Keep the visible rows first, then a small window ahead of and behind them. */
internal fun viewportPrefetchItems(items: List<SpotifyContent>, visibleIndices: List<Int>): List<SpotifyContent> {
    val visible = visibleIndices.filter { it in items.indices }
    if (visible.isEmpty()) return emptyList()
    val start = visible.min()
    val end = visible.max()
    return (visible + (end + 1..end + 4) + (start - 2 until start))
        .distinct().mapNotNull { items.getOrNull(it) }
        .filter { it.kind == ContentKind.PLAYLIST || it.kind == ContentKind.ALBUM }.take(12)
}

internal fun librarySuggestions(libraries: Map<LibrarySection, List<SpotifyContent>>): List<SpotifyContent> {
    val sources = listOf(LibrarySection.PLAYLISTS, LibrarySection.ALBUMS, LibrarySection.TRACKS)
        .map { libraries[it].orEmpty() }
    return (0 until 4).flatMap { index -> sources.mapNotNull { it.getOrNull(index) } }.distinctBy { it.uri }
}
