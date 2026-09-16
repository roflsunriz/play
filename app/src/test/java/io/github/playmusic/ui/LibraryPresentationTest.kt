package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentArtist
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.DetailSort
import io.github.playmusic.data.model.SpotifyContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPresentationTest {
    @Test fun albumAndTrackMetadataUseTheSameFilterAndSortRules() {
        val album = item("album", "Album", ContentKind.ALBUM).copy(subtitle = "Artist", releaseDate = "1991-09-24", trackCount = 12)
        val track = item("track", "Song", ContentKind.TRACK).copy(subtitle = "Artist", albumTitle = "Parent album", durationMs = 185_000)
        assertEquals(listOf(album), presentLibrary(listOf(album), "ARTIST 1991 12", LibrarySort.CREATOR))
        assertEquals(listOf(track), presentLibrary(listOf(track), "parent artist 3:05", LibrarySort.TITLE))
        assertEquals(LibrarySort.options(ContentKind.PLAYLIST), LibrarySort.options(ContentKind.ALBUM))
    }

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

    @Test fun detailSortOptionsDifferByContainerKind() {
        assertEquals(listOf(DetailSort.ADDED_NEWEST, DetailSort.ADDED_OLDEST, DetailSort.TITLE,
            DetailSort.TITLE_DESCENDING, DetailSort.ARTIST, DetailSort.ARTIST_DESCENDING,
            DetailSort.ALBUM, DetailSort.ALBUM_DESCENDING),
            DetailSort.options(ContentKind.PLAYLIST))
        assertEquals(listOf(DetailSort.TRACK_ORDER, DetailSort.TRACK_REVERSE, DetailSort.TITLE,
            DetailSort.TITLE_DESCENDING, DetailSort.PLAYCOUNT, DetailSort.PLAYCOUNT_ASCENDING),
            DetailSort.options(ContentKind.ALBUM))
        assertEquals(listOf(DetailSort.TRACK_ORDER), DetailSort.options(ContentKind.TRACK))
        assertEquals(listOf(DetailSort.TRACK_ORDER), DetailSort.options(ContentKind.ARTIST))
    }

    @Test fun detailTracksSortByTitleArtistAlbumAddedDateAndPlays() {
        fun track(id: String) = item(id, kind = ContentKind.TRACK)
        val old = track("old").copy(title = "Zulu", subtitle = "Amy, Zed", albumTitle = "Beta",
            artists = listOf(ContentArtist("spotify:artist:amy", "Amy")),
            addedAtMs = 1_000, playcount = 10, discNumber = 1, trackNumber = 2)
        val recent = track("recent").copy(title = "Alpha", subtitle = "Zed", albumTitle = "Alpha",
            artists = listOf(ContentArtist("spotify:artist:zed", "Zed")),
            addedAtMs = 9_000, playcount = 900, discNumber = 1, trackNumber = 1)
        val undated = track("undated").copy(title = "Mike", subtitle = "Amy", albumTitle = "Beta",
            addedAtMs = null, playcount = null, discNumber = 2, trackNumber = 1)
        val source = listOf(old, undated, recent)
        assertEquals(source, presentDetailTracks(source, DetailSort.TRACK_ORDER))
        assertEquals(listOf(recent, undated, old), presentDetailTracks(source, DetailSort.TRACK_REVERSE))
        assertEquals(listOf(recent, undated, old), presentDetailTracks(source, DetailSort.TITLE))
        assertEquals(listOf(old, undated, recent), presentDetailTracks(source, DetailSort.TITLE_DESCENDING))
        assertEquals(listOf(undated, old, recent), presentDetailTracks(source, DetailSort.ARTIST))
        assertEquals(listOf(recent, old, undated), presentDetailTracks(source, DetailSort.ARTIST_DESCENDING))
        assertEquals(listOf(recent, old, undated), presentDetailTracks(source, DetailSort.ALBUM))
        assertEquals(listOf(undated, old, recent), presentDetailTracks(source, DetailSort.ALBUM_DESCENDING))
        assertEquals(listOf(recent, old, undated), presentDetailTracks(source, DetailSort.ADDED_NEWEST))
        assertEquals(listOf(old, recent, undated), presentDetailTracks(source, DetailSort.ADDED_OLDEST))
        assertEquals(listOf(recent, old, undated), presentDetailTracks(source, DetailSort.PLAYCOUNT))
        assertEquals(listOf(old, recent, undated), presentDetailTracks(source, DetailSort.PLAYCOUNT_ASCENDING))
    }

    @Test fun viewportPrefetchIsBoundedAndPrioritizesVisibleContent() {
        val items = (0..100).map { item(it.toString(), kind = ContentKind.ALBUM) }
        assertEquals(listOf(40, 41, 43, 44).map { items[it] },
            viewportPrefetchItems(items, listOf(40, 41, 42)))
        assertTrue(viewportPrefetchItems(items, (0..99).toList()).size <= 4)
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
