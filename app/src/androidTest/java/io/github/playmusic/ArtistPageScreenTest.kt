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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.playmusic.data.model.*
import io.github.playmusic.ui.ArtistPageScreen
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.text.NumberFormat

class ArtistPageScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val artist = item(ContentKind.ARTIST, "artist", "An artist with a long descriptive name")
    private val suggested = item(ContentKind.ARTIST, "suggested", "Suggested artist")
    private val track = item(ContentKind.TRACK, "track", "Popular track").copy(albumTitle = "Parent album")
    private val unavailable = item(ContentKind.TRACK, "unavailable", "Unavailable popular track").copy(isPlayable = false)
    private val album = item(ContentKind.ALBUM, "album", "Full album")
    private val single = item(ContentKind.ALBUM, "single", "Single release")
    private val ep = item(ContentKind.ALBUM, "ep", "EP release")
    private val compilation = item(ContentKind.ALBUM, "compilation", "Compilation")
    private val appears = item(ContentKind.ALBUM, "appears", "Guest appearance")
    private val featuring = item(ContentKind.PLAYLIST, "featuring", "Featuring playlist")
    private val discovered = item(ContentKind.PLAYLIST, "discovered", "Discovered on playlist")
    private val page = ArtistPage(isFollowed = false, monthlyListeners = 12345678, followers = 876543, worldRank = 27,
        biography = "<p>Service biography &amp; artist details.</p>", biographySource = "BIOGRAPHY",
        discography = listOf(ArtistRelease(album, ArtistReleaseType.ALBUM), ArtistRelease(single, ArtistReleaseType.SINGLE),
            ArtistRelease(ep, ArtistReleaseType.EP), ArtistRelease(compilation, ArtistReleaseType.COMPILATION)),
        appearsOn = listOf(appears), featuringPlaylists = listOf(featuring), discoveredOnPlaylists = listOf(discovered),
        suggestedArtists = listOf(suggested), songRadioSeeds = listOf(track, unavailable))
    private val detail get() = ContentDetail(artist, listOf(track, unavailable), artistPage = page)

    @Test fun discographyFiltersKeepEveryEligibleReleaseAndResetForAnotherArtist() {
        var selected by mutableStateOf(artist)
        render { Screen(detail = detail.copy(content = selected), selected = selected) }
        val expected = linkedMapOf("all" to listOf(album, single, ep, compilation),
            "albums" to listOf(album, compilation), "singles" to listOf(single, ep))
        for ((filter, contents) in expected) {
            scroll("discography-title", "artist-discography-$filter").performClick().assertIsSelected()
            for (content in contents) scroll("release:${content.uri}", "artist-release-${content.id}").assertIsDisplayed()
            // Searching the lazy list's complete key map detects excluded rows even when neither row is composed.
            for (content in listOf(album, single, ep, compilation) - contents.toSet()) {
                assertTrue("Excluded release must be absent from the complete list", runCatching {
                    composeRule.onNodeWithTag("artist-page").performScrollToKey("release:${content.uri}")
                }.isFailure)
            }
        }
        composeRule.runOnIdle { selected = suggested }
        scroll("discography-title", "artist-discography-all").assertIsSelected()
        scroll("release:${album.uri}", "artist-release-${album.id}").assertIsDisplayed()
    }

    @Test fun allPlayableWorksAndFavoriteButtonsDispatchTheirExactContentWithoutOpeningTheirCards() {
        val played = mutableListOf<SpotifyContent>()
        val opened = mutableListOf<SpotifyContent>()
        val actions = mutableListOf<SpotifyContent>()
        val positions = mutableListOf<Int>()
        render { Screen(onPlay = played::add, onOpen = opened::add, onActions = actions::add,
            onPlayTrack = positions::add, savedUris = setOf(track.uri, appears.uri)) }
        scroll("header", "artist-play").performClick()
        scroll("popular:0:${track.uri}", "artist-popular-play-0").performClick()
        scroll("popular:1:${unavailable.uri}", "artist-popular-play-1").assertIsNotEnabled().performClick()
        scroll("popular:0:${track.uri}", "add-track-${track.id}")
            .assertContentDescriptionEquals(text(R.string.remove_favorite)).performClick()
        scroll("popular:0:${track.uri}", "artist-popular-0").performClick()
        val sections = listOf("release" to album, "release" to single, "release" to ep, "release" to compilation,
            "appears" to appears, "featuring" to featuring, "discovered" to discovered)
        for ((section, content) in sections) {
            val key = "$section:${content.uri}"
            val tag = "artist-$section-${content.id}"
            scroll(key, "$tag-play").assertIsEnabled().performClick()
            if (content.kind == ContentKind.ALBUM) scroll(key, "add-album-${content.id}")
                .assertContentDescriptionEquals(text(if (content == appears) R.string.remove_favorite else R.string.add_favorite)).performClick()
            scroll(key, tag).performClick()
        }
        scroll("suggested:${suggested.uri}", "artist-suggested-${suggested.id}").performClick()
        composeRule.onNodeWithTag("artist-suggested-${suggested.id}-play").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(artist) + sections.map { it.second }, played)
            assertEquals(listOf(0), positions)
            assertEquals(listOf(track) + sections.map { it.second } + suggested, opened)
            assertEquals(listOf(track, album, single, ep, compilation, appears), actions)
        }
    }

    @Test fun followRadioLoadingAndRetryReflectTheCurrentStateAndRejectRepeatedActions() {
        var current by mutableStateOf<ContentDetail?>(null)
        var loading by mutableStateOf(true)
        var followingBusy by mutableStateOf(false)
        var radioBusy by mutableStateOf(false)
        var follows = 0
        var retries = 0
        val radios = mutableListOf<SpotifyContent>()
        render { Screen(detail = current, loading = loading, followBusy = followingBusy, radioBusy = radioBusy,
            onFollow = { follows++; followingBusy = true }, onRetry = { retries++ },
            onRadio = { radios += it; radioBusy = true }) }
        scroll("header", "artist-play").assertIsNotEnabled()
        scroll("header", "artist-follow").assertIsNotEnabled()
        composeRule.onNodeWithTag("artist-retry").assertDoesNotExist()
        composeRule.runOnIdle { loading = false }
        scroll("retry", "artist-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries); current = detail }
        scroll("header", "artist-follow").assertContentDescriptionEquals(text(R.string.artist_follow)).performClick()
        scroll("header", "artist-follow").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, follows); followingBusy = false; current = detail.copy(artistPage = page.copy(isFollowed = true)) }
        scroll("header", "artist-follow").assertIsEnabled()
            .assertContentDescriptionEquals(text(R.string.artist_unfollow)).assertTextEquals(text(R.string.artist_following)).performClick()
        composeRule.runOnIdle { assertEquals(2, follows) }
        scroll("radio:${track.uri}", "artist-radio-${track.id}").performClick()
        scroll("radio:${unavailable.uri}", "artist-radio-${unavailable.id}").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf(track), radios); radioBusy = false }
        scroll("radio:${unavailable.uri}", "artist-radio-${unavailable.id}").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf(track, unavailable), radios) }
    }

    @Test fun serviceStatisticsAndBiographyUseLocalizedNumbersAndReadableText() {
        render { Screen() }
        val numbers = NumberFormat.getIntegerInstance(composeRule.activity.resources.configuration.locales[0])
        scroll("header", "artist-listeners").assertTextEquals(
            composeRule.activity.getString(R.string.artist_monthly_listeners, numbers.format(page.monthlyListeners)))
        scroll("about", "artist-biography").assertTextEquals("Service biography & artist details.")
        assertFullyVisible("artist-biography")
        composeRule.onNodeWithTag("artist-about").onChildren().filter(hasText(
            composeRule.activity.getString(R.string.artist_followers, numbers.format(page.followers)))).assertCountEquals(1)
        composeRule.onNodeWithTag("artist-about").onChildren().filter(hasText(
            composeRule.activity.getString(R.string.artist_world_rank, numbers.format(page.worldRank)))).assertCountEquals(1)
        captureScreen(composeRule.onRoot(), "artist-about")
    }

    @Test fun partialRelatedFailureShowsTheMissingCountAndRetriesWithoutDiscardingValidContent() {
        var current by mutableStateOf(detail.copy(artistPage = page.copy(unavailableRelatedItems = 3)))
        var loading by mutableStateOf(false)
        var retries = 0
        val played = mutableListOf<SpotifyContent>()
        val opened = mutableListOf<SpotifyContent>()
        render { Screen(detail = current, loading = loading, onPlay = played::add, onOpen = opened::add,
            onRetry = { retries++; loading = true }) }
        scroll("related-error", "artist-related-error").assertTextEquals(
            composeRule.activity.getString(R.string.artist_partial_content, 3))
        assertFullyVisible("artist-related-error")
        captureScreen(composeRule.onRoot(), "artist-related-partial-failure")
        scroll("header", "artist-name").assertTextEquals(artist.title)
        scroll("header", "artist-play").assertIsEnabled().performClick()
        scroll("release:${album.uri}", "artist-release-${album.id}").performClick()
        scroll("featuring:${featuring.uri}", "artist-featuring-${featuring.id}").performClick()
        scroll("related-error", "artist-related-retry").assertIsEnabled().performClick()
        scroll("related-error", "artist-related-retry").assertIsNotEnabled().performClick()
        scroll("header", "artist-name").assertTextEquals(artist.title)
        scroll("popular:0:${track.uri}", "artist-popular-play-0").assertIsEnabled()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(listOf(artist), played)
            assertEquals(listOf(album, featuring), opened)
            current = detail
            loading = false
        }
        composeRule.onNodeWithTag("artist-related-error").assertDoesNotExist()
        composeRule.onNodeWithTag("artist-related-retry").assertDoesNotExist()
        assertTrue("Successful refresh must remove the partial-failure row", runCatching {
            composeRule.onNodeWithTag("artist-page").performScrollToKey("related-error")
        }.isFailure)
        scroll("featuring:${featuring.uri}", "artist-featuring-${featuring.id}").assertIsDisplayed()
    }

    @Test fun searchArtistNameOpensArtistPageAndSuggestedArtistUsesTheSameRoute() {
        var state by mutableStateOf(PlayUiState(isLoggedIn = true, selectedSection = LibrarySection.SEARCH,
            searchQuery = "artist", searchFilter = SearchFilter.ARTISTS, items = listOf(artist)))
        val opened = mutableListOf<SpotifyContent>()
        var played = 0
        render { HomeScreen(state, {}, {}, {}, {}, {}, { played++ }, {}, {}, {}, {}, {}, {},
            onOpenContent = { opened += it; state = state.copy(selectedContent = it, detail = detail.copy(content = it)) },
            onBack = { state = state.copy(selectedContent = null, detail = null) }) }
        composeRule.onNodeWithTag("content-list").performScrollToKey(artist.uri)
        composeRule.onNodeWithTag("title-artist-${artist.id}", useUnmergedTree = true).performTouchInput { click() }
        scroll("header", "artist-name").assertTextEquals(artist.title)
        composeRule.onNodeWithTag("content-detail").assertDoesNotExist()
        scroll("suggested:${suggested.uri}", "artist-suggested-${suggested.id}").performClick()
        scroll("header", "artist-name").assertTextEquals(suggested.title)
        captureScreen(composeRule.onRoot(), "artist-search-route")
        composeRule.onNodeWithTag("detail-back-button").performClick()
        composeRule.onNodeWithTag("artist-page").assertDoesNotExist()
        composeRule.onNodeWithTag("content-list").performScrollToKey(artist.uri)
        composeRule.onNodeWithTag("content-artist-${artist.id}").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(artist, suggested), opened); assertEquals(0, played) }
    }

    @Test fun narrowRtlAndLowHeightKeepFiltersActionsAndLongTitlesCompletelyVisible() {
        render(maxWidth = 260.dp, maxHeight = 240.dp, direction = LayoutDirection.Rtl) { Screen() }
        for (tag in listOf("artist-name", "artist-play", "artist-follow")) {
            scroll("header", tag)
            assertFullyVisible(tag)
        }
        captureScreen(composeRule.onRoot(), "artist-narrow-rtl-header")
        for (filter in listOf("all", "albums", "singles")) {
            scroll("discography-title", "artist-discography-$filter").performClick().assertIsSelected()
            assertFullyVisible("artist-discography-$filter")
        }
        captureScreen(composeRule.onRoot(), "artist-narrow-rtl-filters")
        scroll("radio:${track.uri}", "artist-radio-${track.id}")
        assertFullyVisible("artist-radio-${track.id}")
        scroll("about", "artist-biography")
        assertFullyVisible("artist-biography")
        scroll("suggested:${suggested.uri}", "artist-suggested-${suggested.id}")
        assertFullyVisible("artist-suggested-${suggested.id}")
    }

    @Composable private fun Screen(selected: SpotifyContent = artist, detail: ContentDetail? = this.detail,
        loading: Boolean = false, followBusy: Boolean = false, radioBusy: Boolean = false,
        onPlay: (SpotifyContent) -> Unit = {}, onPlayTrack: (Int) -> Unit = {}, onOpen: (SpotifyContent) -> Unit = {},
        onFollow: () -> Unit = {}, onRadio: (SpotifyContent) -> Unit = {}, onActions: (SpotifyContent) -> Unit = {},
        savedUris: Set<String> = emptySet(), onRetry: () -> Unit = {}) =
        ArtistPageScreen(selected, detail, loading, followBusy, radioBusy, onPlay, onPlayTrack, onOpen, onFollow,
            onRadio, onActions, savedUris, onRetry)

    private fun scroll(key: String, tag: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("artist-page").performScrollToKey(key)
        return composeRule.onNodeWithTag(tag).performScrollTo()
    }

    private fun assertFullyVisible(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        assertTrue(full.right > full.left && full.bottom > full.top)
        assertEquals(full.right - full.left, visible.right - visible.left)
        assertEquals(full.bottom - full.top, visible.bottom - visible.top)
    }

    private fun render(maxWidth: Dp? = null, maxHeight: Dp? = null, direction: LayoutDirection? = null,
        content: @Composable () -> Unit) = composeRule.setContent {
        CompositionLocalProvider(LocalLayoutDirection provides (direction ?: LocalLayoutDirection.current)) {
            PlayTheme { Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopStart) {
                    var bounds: Modifier = Modifier
                    if (maxWidth != null) bounds = bounds.widthIn(max = maxWidth)
                    if (maxHeight != null) bounds = bounds.heightIn(max = maxHeight)
                    Box(bounds.fillMaxSize()) { content() }
                }
            } }
        }
    }

    private fun text(id: Int) = composeRule.activity.getString(id)
    private fun item(kind: ContentKind, id: String, title: String) =
        SpotifyContent(id, "spotify:${kind.name.lowercase()}:$id", title, "", null, kind)
}
