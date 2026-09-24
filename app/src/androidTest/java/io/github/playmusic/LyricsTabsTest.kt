package io.github.playmusic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.playmusic.data.api.LrclibApiClient
import io.github.playmusic.data.api.LyricsSource
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.LyricsLine
import io.github.playmusic.data.model.LyricsSyncType
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.TrackLyrics
import io.github.playmusic.ui.LyricsTabbedRoute
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class LyricsTabsTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val track = MusicContent("1", "spotify:track:0000000000000000000001", "Synthetic", "Singer",
        null, ContentKind.TRACK, durationMs = 200_000, albumTitle = "Album")
    private val official = TrackLyrics(track.uri, LyricsSyncType.LINE_SYNCED,
        listOf(LyricsLine("Official line", 0)), "Official provider")

    @Test fun tabsSwitchBetweenOfficialAndCommunityLyrics() {
        var sought = -1L
        render(official = official, lrclib = syncedLrclib(), position = 4_500,
            onSeek = { sought = it })
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Official line").assertIsSelected()
        composeRule.onNodeWithTag("lyrics-tab-lrclib").performClick()
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Community first")
        composeRule.onNodeWithTag("lyrics-line-1").assertTextEquals("Community second").assertIsSelected()
        composeRule.onNodeWithTag("lyrics-line-1").performClick()
        composeRule.runOnIdle { assertEquals(4_000L, sought) }
        composeRule.onNodeWithTag("lyrics-tab-official").performClick()
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Official line")
    }

    @Test fun plainCommunityLyricsShowWithoutTiming() {
        render(official = official, lrclib = plainLrclib(), position = 4_500, onSeek = {})
        composeRule.onNodeWithTag("lyrics-tab-lrclib").performClick()
        composeRule.onNodeWithTag("lyrics-unsynced").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Community plain")
    }

    @Test fun missingSourcesStayUnavailable() {
        render(official = official, lrclib = null, position = 0, onSeek = {})
        composeRule.onNodeWithTag("lyrics-tab-lrclib").performClick()
        composeRule.onNodeWithTag("lyrics-unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-tab-official").performClick()
        composeRule.onNodeWithTag("lyrics-line-0").assertTextEquals("Official line")
    }

    private fun syncedLrclib(): LrclibApiClient = LrclibApiClient(openConnection = {
        connection(200, JSONObjectBody("Synthetic", "Singer",
            "[00:01.00]Community first\n[00:04.00]Community second"))
    })

    private fun plainLrclib(): LrclibApiClient = LrclibApiClient(openConnection = {
        connection(200, JSONObjectBody("Synthetic", "Singer", null, plain = "Community plain"))
    })

    private fun render(official: TrackLyrics, lrclib: LrclibApiClient?, position: Long, onSeek: (Long) -> Unit) {
        val officialSource = LyricsSource { official }
        val community = lrclib ?: LrclibApiClient(openConnection = { connection(404, "") })
        composeRule.setContent {
            PlayTheme {
                Surface(Modifier.fillMaxSize()) {
                    LyricsTabbedRoute(track, Playback(item = track, progressMs = position), officialSource,
                        community, onSeek)
                }
            }
        }
    }

    private companion object {
        fun JSONObjectBody(track: String, artist: String, synced: String? = null,
            plain: String? = null): String {
            val parts = mutableListOf("\"trackName\":\"$track\"", "\"artistName\":\"$artist\"")
            if (synced != null) parts += "\"syncedLyrics\":\"${synced.replace("\n", "\\n")}\""
            if (plain != null) parts += "\"plainLyrics\":\"$plain\""
            return "{${parts.joinToString(",")}}"
        }

        fun connection(status: Int, body: String): HttpURLConnection =
            object : HttpURLConnection(URI("https://lrclib.net/api/get").toURL()) {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getResponseCode() = status
                override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
                override fun getHeaderField(name: String): String? =
                    if (status == 200 && name.equals("Content-Type", true)) "application/json" else null
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
    }
}
