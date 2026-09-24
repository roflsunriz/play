package io.github.playmusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.ui.ExpandedPlayerScreen
import io.github.playmusic.ui.theme.PlayTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExpandedPlayerScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val artworkFile = File(context.cacheDir, "expanded-player-${UUID.randomUUID()}.png")
    private val track = MusicContent("0000000000000000000001", "spotify:track:0000000000000000000001",
        "A full track title with room to read", "Synthetic artist", null, ContentKind.TRACK, durationMs = 180_000)

    @After fun cleanup() {
        check(artworkFile.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        artworkFile.delete()
    }

    @Test fun artworkMetadataAndTransportControlsRemainVisibleAndDispatchActions() {
        val content = track.copy(imageUrl = createArtwork())
        val actions = mutableListOf<String>()
        var haptics = 0
        render {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics++ }
            }) {
                ExpandedPlayerScreen(Playback(content, progressMs = 42_000, durationMs = 180_000, playWhenReady = true),
                    onPlayPause = { actions += "pause" }, onNext = { actions += "next" }, onPrevious = { actions += "previous" },
                    onSeek = {}, onShuffle = { actions += "shuffle" }, onRepeat = { actions += "repeat" },
                    onBack = { actions += "back" })
            }
        }
        val artwork = composeRule.onNodeWithTag("expanded-artwork")
        artwork.assertIsDisplayed()
        assertFullyVisible("expanded-artwork")
        val bounds = artwork.getUnclippedBoundsInRoot()
        assertEquals(bounds.right - bounds.left, bounds.bottom - bounds.top)
        assertTrue(bounds.right - bounds.left >= 96.dp)
        composeRule.onNodeWithTag("expanded-title").performScrollTo().assertTextEquals(content.title)
        assertFullyVisible("expanded-title")
        composeRule.onNodeWithTag("expanded-artist").assertTextEquals(content.subtitle)
        composeRule.onNodeWithTag("expanded-controls").performScrollTo()
        for (tag in listOf("expanded-shuffle", "expanded-previous", "expanded-play-pause", "expanded-next", "expanded-repeat")) {
            assertFullyVisible(tag)
        }
        captureScreen(composeRule.onRoot(), "expanded-player")
        composeRule.onNodeWithTag("expanded-previous").performClick()
        composeRule.onNodeWithTag("expanded-play-pause").performClick()
        composeRule.onNodeWithTag("expanded-next").performClick()
        composeRule.onNodeWithTag("expanded-shuffle").performClick()
        composeRule.onNodeWithTag("expanded-repeat").performClick()
        composeRule.onNodeWithTag("expanded-player-back").performClick()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle {
            assertEquals(listOf("previous", "pause", "next", "shuffle", "repeat", "back", "back"), actions)
            assertTrue(haptics >= 6)
        }
    }

    @Test fun tappingDraggingAndAccessibilitySeekingCommitOnlyCompletedPositions() {
        var playback by mutableStateOf(Playback(track, durationMs = 180_000))
        val seeks = mutableListOf<Long>()
        render {
            ExpandedPlayerScreen(playback, {}, {}, {}, onSeek = {
                seeks += it
                playback = playback.copy(progressMs = it)
            }, {}, {}, {})
        }
        val slider = composeRule.onNodeWithTag("expanded-seek-slider")
        slider.performScrollTo().assertIsEnabled()
        val direction = slider.fetchSemanticsNode().layoutInfo.layoutDirection
        slider.performTouchInput { click(Offset(width * physicalFraction(.75f, direction), center.y)) }
        composeRule.runOnIdle { assertEquals(1, seeks.size); assertTrue(seeks.last() in 110_000..155_000) }
        slider.performTouchInput {
            swipe(Offset(width * physicalFraction(.25f, direction), center.y),
                Offset(width * physicalFraction(.60f, direction), center.y), durationMillis = 300)
        }
        composeRule.runOnIdle { assertEquals(2, seeks.size); assertTrue(seeks.last() in 85_000..130_000) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(45_000f) }
        composeRule.runOnIdle { assertEquals(listOf(45_000L), seeks.takeLast(1)); assertEquals(3, seeks.size) }
        val elapsed = String.format(context.resources.configuration.locales[0], "%d:%02d", 0, 45)
        composeRule.onNodeWithTag("expanded-elapsed").assertTextEquals(elapsed)
    }

    @Test fun progressUpdatesDoNotMoveAHeldThumbAndTrackChangesCancelTheOldSeek() {
        var playback by mutableStateOf(Playback(track, progressMs = 1_000, durationMs = 180_000))
        val seeks = mutableListOf<Long>()
        render { ExpandedPlayerScreen(playback, {}, {}, {}, { seeks += it }, {}, {}, {}) }
        val slider = composeRule.onNodeWithTag("expanded-seek-slider")
        slider.performScrollTo()
        val direction = slider.fetchSemanticsNode().layoutInfo.layoutDirection
        slider.performTouchInput {
            down(Offset(width * physicalFraction(.3f, direction), center.y))
            moveTo(Offset(width * physicalFraction(.7f, direction), center.y))
        }
        val held = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(held > 90_000f)
        composeRule.runOnIdle { playback = playback.copy(progressMs = 20_000) }
        assertEquals(held, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        composeRule.runOnIdle {
            playback = Playback(track.copy(id = "0000000000000000000002", uri = "spotify:track:0000000000000000000002",
                title = "Next track"), progressMs = 15_000, durationMs = 240_000)
        }
        slider.performTouchInput { up() }
        composeRule.runOnIdle { assertTrue(seeks.isEmpty()) }
        assertEquals(15_000f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        composeRule.onNodeWithTag("expanded-title").assertTextEquals("Next track")
    }

    @Test fun bufferingPauseRepeatShuffleAndLongDurationsReflectPlaybackState() {
        var playback by mutableStateOf(Playback(track, durationMs = 3_661_000, progressMs = 3_600_000,
            isPlaying = false, playWhenReady = true, isBuffering = true, shuffle = true, repeatMode = RepeatMode.TRACK))
        render { ExpandedPlayerScreen(playback, {}, {}, {}, {}, {}, {}, {}) }
        composeRule.onNodeWithTag("expanded-buffering").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("expanded-play-pause").performScrollTo().assertContentDescriptionEquals(context.getString(R.string.pause))
        composeRule.onNodeWithTag("expanded-repeat").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.repeat_track)))
        composeRule.onNodeWithTag("expanded-shuffle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.player_shuffle_on)))
        val locale = context.resources.configuration.locales[0]
        composeRule.onNodeWithTag("expanded-elapsed").assertTextEquals(String.format(locale, "%d:%02d:%02d", 1, 0, 0))
        composeRule.onNodeWithTag("expanded-duration").assertTextEquals(String.format(locale, "%d:%02d:%02d", 1, 1, 1))
        composeRule.runOnIdle { playback = playback.copy(playWhenReady = false, isBuffering = false, shuffle = false, repeatMode = RepeatMode.OFF) }
        composeRule.onNodeWithTag("expanded-buffering").assertDoesNotExist()
        composeRule.onNodeWithTag("expanded-play-pause").assertContentDescriptionEquals(context.getString(R.string.play))
        composeRule.onNodeWithTag("expanded-repeat").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.repeat_off)))
    }

    @Test fun emptyPlaybackAndUnknownDurationRemainSafe() {
        var playback by mutableStateOf(Playback())
        var callbacks = 0
        render { ExpandedPlayerScreen(playback, { callbacks++ }, { callbacks++ }, { callbacks++ }, { callbacks++ },
            { callbacks++ }, { callbacks++ }, {}) }
        composeRule.onNodeWithTag("expanded-title").performScrollTo().assertTextEquals(context.getString(R.string.player_no_track))
        for (tag in listOf("expanded-play-pause", "expanded-previous", "expanded-next", "expanded-shuffle", "expanded-repeat", "expanded-seek-slider")) {
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        composeRule.runOnIdle { assertEquals(0, callbacks); playback = Playback(track, progressMs = 30_000) }
        composeRule.onNodeWithTag("expanded-seek-slider").assertIsNotEnabled()
        composeRule.onNodeWithTag("expanded-duration").assertTextEquals(context.getString(R.string.player_unknown_time))
        composeRule.onNodeWithTag("expanded-elapsed").assertTextEquals(String.format(context.resources.configuration.locales[0], "%d:%02d", 0, 30))
        composeRule.onNodeWithTag("expanded-play-pause").performScrollTo().assertIsEnabled()
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize(), content = content) } }
    }

    private fun physicalFraction(logicalFraction: Float, direction: LayoutDirection): Float =
        if (direction == LayoutDirection.Rtl) 1f - logicalFraction else logicalFraction

    private fun assertFullyVisible(tag: String) {
        val element = composeRule.onNodeWithTag(tag)
        val full = element.getUnclippedBoundsInRoot()
        val visible = element.getBoundsInRoot()
        assertEquals("$tag height", full.bottom - full.top, visible.bottom - visible.top)
        assertEquals("$tag width", full.right - full.left, visible.right - visible.left)
        val node = element.fetchSemanticsNode()
        val screen = android.graphics.Rect()
        composeRule.runOnUiThread { composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(screen) }
        assertTrue("$tag outside visible display", node.positionOnScreen.y >= screen.top && node.positionOnScreen.y + node.size.height <= screen.bottom)
    }

    private fun createArtwork(): String {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(20, 65, 84))
                drawCircle(256f, 256f, 190f, Paint().apply { color = Color.rgb(45, 175, 165); isAntiAlias = true })
                drawCircle(256f, 256f, 76f, Paint().apply { color = Color.rgb(255, 206, 110); isAntiAlias = true })
            }
            artworkFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return artworkFile.toUri().toString()
    }
}
