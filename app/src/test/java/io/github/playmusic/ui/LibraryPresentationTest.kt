package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPresentationTest {
    private fun item(id: String, title: String = id, kind: ContentKind = ContentKind.PLAYLIST) =
        SpotifyContent(id, "spotify:${kind.name.lowercase()}:$id", title, "", null, kind)

    @Test fun filterMatchesWordsAcrossMetadataAndNormalizesWidthAndCase() {
        val match = item("one", "Evening JAZZ").copy(ownerName = "Alice", description = "静かな夜に", trackCount = 24)
        val other = item("two", "Evening Jazz").copy(ownerName = "Bob", description = "朝")
        assertEquals(listOf(match), presentLibrary(listOf(match, other), "ＡＬＩＣＥ jazz 夜 24", LibrarySort.LIBRARY_ORDER))
        assertTrue(presentLibrary(listOf(match), "jazz 朝", LibrarySort.LIBRARY_ORDER).isEmpty())
    }

    @Test fun sortingIsStableAndLeavesOriginalLibraryOrderIntact() {
        val a = item("a", "Alpha").copy(ownerName = "Zed", trackCount = 2)
        val b = item("b", "Beta").copy(ownerName = "Amy", trackCount = 20)
        val unknown = item("unknown", "Unknown")
        val source = listOf(b, unknown, a)
        assertEquals(listOf(a, b, unknown), presentLibrary(source, "", LibrarySort.TITLE))
        assertEquals(listOf(unknown, b, a), presentLibrary(source, "", LibrarySort.TITLE_DESCENDING))
        assertEquals(listOf(b, a, unknown), presentLibrary(source, "", LibrarySort.TRACK_COUNT))
        assertEquals(listOf(b, unknown, a), presentLibrary(source, "", LibrarySort.LIBRARY_ORDER))
    }

    @Test fun trackSortUsesArtistAndDurationWithUnknownDurationLast() {
        val short = item("short", kind = ContentKind.TRACK).copy(subtitle = "Zed", durationMs = 30_000)
        val long = item("long", kind = ContentKind.TRACK).copy(subtitle = "Amy", durationMs = 300_000)
        val unknown = item("unknown", kind = ContentKind.TRACK)
        assertEquals(listOf(short, long, unknown), presentLibrary(listOf(long, unknown, short), "", LibrarySort.DURATION))
        assertEquals(listOf(long, short, unknown), presentLibrary(listOf(short, unknown, long), "", LibrarySort.DURATION_DESCENDING))
        assertEquals(listOf(long, short), presentLibrary(listOf(short, long), "", LibrarySort.CREATOR))
    }

    @Test fun viewportPrefetchIsBoundedAndPrioritizesVisibleContent() {
        val items = (0..100).map { item(it.toString(), kind = ContentKind.ALBUM) }
        assertEquals(listOf(40, 41, 42, 43, 44, 45, 46, 38, 39).map { items[it] },
            viewportPrefetchItems(items, listOf(40, 41, 42)))
        assertTrue(viewportPrefetchItems(items, (0..99).toList()).size <= 12)
        assertTrue(viewportPrefetchItems(items, listOf(-1, 999)).isEmpty())
        assertTrue(viewportPrefetchItems(listOf(item("track", kind = ContentKind.TRACK)), listOf(0)).isEmpty())
    }

    @Test fun suggestionsUseAllLoadedTabsAndStayBounded() {
        val playlists = (0..10).map { item("p$it") }
        val albums = (0..10).map { item("a$it", kind = ContentKind.ALBUM) }
        val suggestions = librarySuggestions(mapOf(LibrarySection.PLAYLISTS to playlists, LibrarySection.ALBUMS to albums))
        assertEquals(listOf(playlists[0], albums[0]), suggestions.take(2))
        assertEquals(8, suggestions.size)
    }
}
