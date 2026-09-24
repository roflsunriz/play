package io.github.playmusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.DetailSort
import io.github.playmusic.data.model.PlaylistMetadata
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.ui.ContentDetailScreen
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ContentDetailScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val track = MusicContent("track", "spotify:track:0000000000000000000001", "Synthetic track", "Artist",
        null, ContentKind.TRACK, durationMs = 125_000, albumUri = "spotify:album:0000000000000000000001",
        albumTitle = "Synthetic album")

    @Test fun duplicateTracksKeepTheirPositionsAndUnavailableTracksCannotPlay() {
        val album = track.copy(kind = ContentKind.ALBUM, uri = track.albumUri!!, title = "Album with a deliberately long descriptive title",
            albumUri = null, albumTitle = null)
        val detail = ContentDetail(album, listOf(track, track, track.copy(isPlayable = false)), releaseDate = "2026-08-01")
        val positions = mutableListOf<Int>()
        var played: MusicContent? = null
        var vibrations = 0
        render {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { vibrations++ }
            }) {
                ContentDetailScreen(album, detail, false, { played = it }, {}, {}, { positions += it })
            }
        }
        scrollToItem("title", "detail-title").assertTextEquals(album.title)
        assertFullyVisible("detail-title")
        scrollToItem("title", "detail-artists").assertTextEquals("Artist")
        scrollToItem("release-date", "detail-release-date").assertTextEquals("2026-08-01")
        captureScreen(composeRule.onRoot(), "album-detail")
        scrollToItem("actions", "detail-play-button").performClick()
        scrollToItem("1:${track.uri}", "detail-track-1").performClick()
        scrollToItem("2:${track.uri}", "detail-track-2").assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(album, played)
            assertEquals(listOf(1), positions)
            assertEquals(2, vibrations)
        }
    }

    @Test fun retriesMissingDetailsAndOpensTheTracksAlbum() {
        var detail by mutableStateOf<ContentDetail?>(null)
        var loading by mutableStateOf(true)
        var retried = false
        var opened: MusicContent? = null
        render {
            ContentDetailScreen(track, detail, loading, {}, { opened = it }, { retried = true }, {})
        }
        scrollToItem("actions", "detail-play-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("detail-retry-button").assertDoesNotExist()
        composeRule.runOnIdle { loading = false }
        scrollToItem("retry", "detail-retry-button").performClick()
        composeRule.runOnIdle { assertTrue(retried); detail = ContentDetail(track) }
        scrollToItem("actions", "detail-play-button").assertIsEnabled()
        scrollToItem("title", "detail-artists").assertTextEquals("Artist")
        scrollToItem("duration", "detail-duration").assertTextEquals(String.format(Locale.getDefault(), "%d:%02d", 2, 5))
        scrollToItem("album", "detail-album-button").performClick()
        composeRule.runOnIdle {
            assertEquals(ContentKind.ALBUM, opened?.kind)
            assertEquals(track.albumUri, opened?.uri)
        }
    }

    @Test fun playlistDetailsUseConfiguredMetadataAndInlineIconActions() {
        val playlist = playlist().copy(ownerName = "Displayed creator", description = "Earlier summary", trackCount = 4)
        val detail = ContentDetail(playlist, listOf(track), totalTracks = 7,
            playlistMetadata = metadata(playlist, "Configured description"))
        var played: MusicContent? = null
        var edits = 0
        var deleted = 0
        var vibrations = 0
        render(maxHeight = 240.dp) {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { vibrations++ }
            }) {
                ContentDetailScreen(playlist, detail, false, { played = it }, {}, {}, {}, { edits++ }, { deleted++ })
            }
        }
        scrollToItem("title", "detail-creator")
            .assertTextEquals(composeRule.activity.getString(R.string.detail_creator, "Displayed creator"))
        assertFullyVisible("detail-creator")
        scrollToItem("description", "detail-description").assertTextEquals("Configured description")
        scrollToItem("track-count", "detail-track-count")
            .assertTextEquals(composeRule.activity.resources.getQuantityString(R.plurals.track_count, 7, 7))
        assertActionsShareOneVisibleRow()
        composeRule.onNodeWithTag("edit-playlist-button")
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.edit_playlist))
        composeRule.onNodeWithTag("delete-playlist-button")
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.delete_playlist))
        assertFalse(composeRule.onNodeWithTag("edit-playlist-button").fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        assertFalse(composeRule.onNodeWithTag("delete-playlist-button").fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        captureScreen(composeRule.onRoot(), "playlist-detail-actions")
        composeRule.onNodeWithTag("detail-play-button").performClick()
        composeRule.onNodeWithTag("edit-playlist-button").performClick()
        composeRule.onNodeWithTag("delete-playlist-button").performClick()
        composeRule.runOnIdle {
            assertEquals(playlist, played)
            assertEquals(1, edits)
            assertEquals(1, deleted)
            assertEquals(3, vibrations)
        }
    }

    @Test fun narrowRtlPlaylistActionsRemainFullyVisibleTogether() {
        val playlist = playlist().copy(title = "A playlist title that wraps in a narrow window", ownerName = "Creator")
        render(maxWidth = 220.dp, maxHeight = 180.dp, direction = LayoutDirection.Rtl) {
            ContentDetailScreen(playlist, ContentDetail(playlist, listOf(track), playlistMetadata = metadata(playlist)),
                false, {}, {}, {}, {})
        }
        scrollToItem("title", "detail-title")
        assertFullyVisible("detail-title")
        assertActionsShareOneVisibleRow()
        composeRule.onNodeWithTag("detail-play-button")
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.play))
        assertFalse(composeRule.onNodeWithTag("detail-play-button").fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        captureScreen(composeRule.onRoot(), "playlist-detail-narrow-rtl")
    }

    @Test fun absentMetadataIsNotReplacedAndAnExplicitlyClearedDescriptionStaysEmpty() {
        val playlist = playlist().copy(description = "Known cached description", trackCount = 0)
        var detail by mutableStateOf<ContentDetail?>(null)
        render {
            ContentDetailScreen(playlist, detail, false, {}, {}, {}, {})
        }
        composeRule.onNodeWithTag("detail-artists").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-creator").assertDoesNotExist()
        scrollToItem("description", "detail-description").assertTextEquals("Known cached description")
        scrollToItem("track-count", "detail-track-count")
            .assertTextEquals(composeRule.activity.resources.getQuantityString(R.plurals.track_count, 0, 0))
        composeRule.runOnIdle { detail = ContentDetail(playlist, playlistMetadata = metadata(playlist, "").copy(ownerUsername = null)) }
        scrollToItem("title", "detail-title")
        composeRule.onNodeWithTag("detail-description").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-creator").assertDoesNotExist()
    }

    @Test fun emptyAlbumAndTrackSubtitlesDoNotCreateAPlaceholder() {
        var selected by mutableStateOf(track.copy(subtitle = "", releaseDate = "2026-09-01"))
        render { ContentDetailScreen(selected, ContentDetail(selected), false, {}, {}, {}, {}) }
        composeRule.onNodeWithTag("detail-artists").assertDoesNotExist()
        scrollToItem("release-date", "detail-release-date").assertTextEquals("2026-09-01")
        scrollToItem("duration", "detail-duration")
            .assertTextEquals(String.format(Locale.getDefault(), "%d:%02d", 2, 5))
        composeRule.runOnIdle { selected = selected.copy(kind = ContentKind.ALBUM, albumUri = null, albumTitle = null) }
        scrollToItem("title", "detail-title")
        composeRule.onNodeWithTag("detail-artists").assertDoesNotExist()
        scrollToItem("track-count", "detail-track-count")
            .assertTextEquals(composeRule.activity.resources.getQuantityString(R.plurals.track_count, 0, 0))
    }

    @Test fun detailSortMenuOffersKindOptionsAndReportsSelection() {
        val playlist = playlist()
        val first = track.copy(id = "a", uri = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa", title = "Beta")
        val second = track.copy(id = "b", uri = "spotify:track:bbbbbbbbbbbbbbbbbbbbbb", title = "Alpha")
        var selected: DetailSort? = null
        var sort by mutableStateOf(DetailSort.ADDED_NEWEST)
        render {
            ContentDetailScreen(playlist, ContentDetail(playlist, listOf(first, second)), false,
                {}, {}, {}, {}, sort = sort, sortOptions = DetailSort.options(ContentKind.PLAYLIST),
                onSortChanged = { selected = it; sort = it })
        }
        scrollToItem("actions", "detail-sort-button")
        composeRule.waitForIdle()
        val playCenter = composeRule.onNodeWithTag("detail-play-button").fetchSemanticsNode().boundsInRoot.center.y
        val sortCenter = composeRule.onNodeWithTag("detail-sort-button").fetchSemanticsNode().boundsInRoot.center.y
        assertEquals(playCenter, sortCenter, 0.5f)
        composeRule.onNodeWithTag("detail-sort-button").performClick()
        for (tag in listOf("added_newest", "added_oldest", "title", "title_descending",
            "artist", "artist_descending", "album", "album_descending")) {
            composeRule.onNodeWithTag("detail-sort-$tag").assertExists()
        }
        composeRule.onNodeWithTag("detail-sort-title").performClick()
        composeRule.runOnIdle { assertEquals(DetailSort.TITLE, selected) }
        composeRule.onNodeWithTag("content-detail").performScrollToKey("1:${second.uri}")
        scrollToItem("0:${first.uri}", "detail-track-0")
    }

    private fun playlist() = MusicContent("0000000000000000000001", "spotify:playlist:0000000000000000000001",
        "Configured playlist", "", null, ContentKind.PLAYLIST)

    private fun metadata(playlist: MusicContent, description: String = "Description") = PlaylistMetadata(
        playlist.uri, playlist.title, description, null, "account-id", canEdit = true, canDelete = true, isOwned = true)

    private fun scrollToItem(key: String, tag: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("content-detail").performScrollToKey(key)
        return composeRule.onNodeWithTag(tag).performScrollTo()
    }

    private fun assertActionsShareOneVisibleRow() {
        scrollToItem("actions", "detail-actions")
        composeRule.waitForIdle()
        val tags = listOf("detail-play-button", "edit-playlist-button", "delete-playlist-button")
        tags.forEach { assertFullyVisible(it) }
        val centers = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.center.y }
        centers.drop(1).forEach { assertEquals(centers.first(), it, 0.5f) }
    }

    private fun assertFullyVisible(tag: String) {
        val interaction = composeRule.onNodeWithTag(tag)
        composeRule.waitForIdle()
        val full = interaction.getUnclippedBoundsInRoot()
        val visible = interaction.getBoundsInRoot()
        assertTrue(full.right > full.left && full.bottom > full.top)
        assertEquals(full.right - full.left, visible.right - visible.left)
        assertEquals(full.bottom - full.top, visible.bottom - visible.top)
        val node = interaction.fetchSemanticsNode()
        val frame = android.graphics.Rect().also {
            composeRule.runOnUiThread { composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        }
        assertTrue(node.positionOnScreen.x >= frame.left && node.positionOnScreen.y >= frame.top)
        assertTrue(node.positionOnScreen.x + node.size.width <= frame.right && node.positionOnScreen.y + node.size.height <= frame.bottom)
    }

    private fun render(maxWidth: Dp? = null, maxHeight: Dp? = null, direction: LayoutDirection? = null,
        content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides (direction ?: LocalLayoutDirection.current)) {
                PlayTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopStart) {
                            var bounds: Modifier = Modifier
                            if (maxWidth != null) bounds = bounds.widthIn(max = maxWidth)
                            if (maxHeight != null) bounds = bounds.heightIn(max = maxHeight)
                            Box(bounds.fillMaxSize()) { content() }
                        }
                    }
                }
            }
        }
    }
}
