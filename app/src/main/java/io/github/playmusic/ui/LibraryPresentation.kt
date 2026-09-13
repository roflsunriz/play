package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import java.text.Normalizer
import java.util.Locale

enum class LibrarySort {
    LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, TRACK_COUNT, DURATION, DURATION_DESCENDING;

    companion object {
        fun options(kind: ContentKind): List<LibrarySort> = when (kind) {
            ContentKind.PLAYLIST, ContentKind.ALBUM -> listOf(LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, TRACK_COUNT)
            ContentKind.TRACK -> listOf(LIBRARY_ORDER, TITLE, TITLE_DESCENDING, CREATOR, DURATION, DURATION_DESCENDING)
            else -> listOf(LIBRARY_ORDER)
        }
    }
}

internal fun PlayUiState.queryFor(section: LibrarySection): String = when (section) {
    LibrarySection.PLAYLISTS -> libraryQuery
    LibrarySection.ALBUMS -> albumQuery
    LibrarySection.TRACKS -> trackQuery
    LibrarySection.SEARCH -> ""
}

internal fun PlayUiState.sortFor(section: LibrarySection): LibrarySort = when (section) {
    LibrarySection.PLAYLISTS -> playlistSort
    LibrarySection.ALBUMS -> albumSort
    LibrarySection.TRACKS -> trackSort
    LibrarySection.SEARCH -> LibrarySort.LIBRARY_ORDER
}

internal fun presentLibrary(items: List<SpotifyContent>, query: String, sort: LibrarySort): List<SpotifyContent> {
    val words = normalizeLibraryText(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val filtered = if (words.isEmpty()) items else items.filter { item ->
        val text = normalizeLibraryText(listOfNotNull(item.title, item.ownerName, item.description,
            item.subtitle, item.albumTitle, item.trackCount?.toString(), item.releaseDate,
            item.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(Locale.ROOT, it / 60_000, it / 1_000 % 60) })
            .joinToString(" "))
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

/** Two visible items and at most two upcoming items keep background work small. */
internal fun viewportPrefetchItems(items: List<SpotifyContent>, visibleIndices: List<Int>): List<SpotifyContent> {
    val visible = visibleIndices.filter { it in items.indices }
    if (visible.isEmpty()) return emptyList()
    val end = visible.max()
    return (visible.take(2) + (end + 1..end + 2))
        .distinct().mapNotNull { items.getOrNull(it) }
        .filter { it.kind == ContentKind.PLAYLIST || it.kind == ContentKind.ALBUM }.take(DetailPrefetcher.MAX_ITEMS)
}

internal fun librarySuggestions(libraries: Map<LibrarySection, List<SpotifyContent>>): List<SpotifyContent> {
    val sources = listOf(LibrarySection.PLAYLISTS, LibrarySection.ALBUMS, LibrarySection.TRACKS)
        .map { libraries[it].orEmpty() }
    return (0 until 4).flatMap { index -> sources.mapNotNull { it.getOrNull(index) } }.distinctBy { it.uri }
}
