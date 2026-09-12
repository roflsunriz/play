package io.github.playmusic

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.ui.ContentDetailScreen
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ContentDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val track = SpotifyContent("track", "spotify:track:0000000000000000000001", "Synthetic track", "Artist",
        null, ContentKind.TRACK, durationMs = 125_000, albumUri = "spotify:album:0000000000000000000001",
        albumTitle = "Synthetic album")

    @Test fun duplicateTracksKeepTheirPositionsAndUnavailableTracksCannotPlay() {
        val album = track.copy(kind = ContentKind.ALBUM, uri = track.albumUri!!, title = "Album with a deliberately long descriptive title",
            albumUri = null, albumTitle = null)
        val detail = ContentDetail(album, listOf(track, track, track.copy(isPlayable = false)))
        val positions = mutableListOf<Int>()
        var played: SpotifyContent? = null
        var vibrations = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { vibrations++ }
            }) {
                PlayTheme {
                    ContentDetailScreen(album, detail, false, { played = it }, {}, {}, { positions += it })
                }
            }
        }
        val title = composeRule.onNodeWithTag("detail-title")
        title.performScrollTo().assertTextEquals(album.title)
        val full = title.getUnclippedBoundsInRoot()
        val visible = title.getBoundsInRoot()
        assertEquals(full.bottom - full.top, visible.bottom - visible.top)
        captureScreen(composeRule.onRoot(), "album-detail")
        composeRule.onNodeWithTag("detail-play-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("detail-track-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("detail-track-2").performScrollTo().assertIsNotEnabled().performClick()
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
        var opened: SpotifyContent? = null
        composeRule.setContent {
            PlayTheme { ContentDetailScreen(track, detail, loading, {}, { opened = it }, { retried = true }, {}) }
        }
        composeRule.onNodeWithTag("detail-play-button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("detail-retry-button").assertDoesNotExist()
        composeRule.runOnIdle { loading = false }
        composeRule.onNodeWithTag("detail-retry-button").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(retried); detail = ContentDetail(track) }
        composeRule.onNodeWithTag("detail-play-button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("detail-duration").performScrollTo().assertTextEquals(String.format(Locale.getDefault(), "%d:%02d", 2, 5))
        composeRule.onNodeWithTag("detail-album-button").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(ContentKind.ALBUM, opened?.kind)
            assertEquals(track.albumUri, opened?.uri)
        }
    }
}
