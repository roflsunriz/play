package io.github.playmusic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.PlaylistLimits
import io.github.playmusic.data.model.PlaylistMetadata
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.ui.ContentDetailScreen
import io.github.playmusic.ui.DeletePlaylistDialog
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.LibrarySection
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.PlaylistEditorFailure
import io.github.playmusic.ui.PlaylistEditorScreen
import io.github.playmusic.ui.PlaylistEditorState
import io.github.playmusic.ui.theme.PlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaylistEditorScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private val playlist = SpotifyContent("0000000000000000000001", "spotify:playlist:0000000000000000000001",
        "Playlist", "Owner", null, ContentKind.PLAYLIST)

    @Test fun formValidatesInputAndKeepsChangesAfterFailedSave() {
        var state by mutableStateOf(PlaylistEditorState())
        var saved = 0
        var picked = 0
        var cancelled = false
        render {
            PlaylistEditorScreen(state,
                onNameChanged = { state = state.copy(name = it) },
                onDescriptionChanged = { state = state.copy(description = it) },
                onChooseImage = { picked++ }, onUndoImage = {}, onRemoveImage = {},
                onSave = { saved++; state = state.copy(isSaving = true) }, onCancel = { cancelled = true })
        }
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-name-input").performScrollTo().performTextInput("New playlist")
        composeRule.onNodeWithTag("playlist-description-input").performScrollTo().performTextInput("A description\nwith two lines")
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("playlist-name-input").performScrollTo()
            .performTextReplacement("x".repeat(PlaylistLimits.MAX_NAME_LENGTH + 1))
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-name-input").performScrollTo().performTextReplacement("New playlist")
        composeRule.onNodeWithTag("playlist-description-input").performScrollTo()
            .performTextReplacement("x".repeat(PlaylistLimits.MAX_DESCRIPTION_LENGTH + 1))
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-description-input").performScrollTo().performTextReplacement("A description\nwith two lines")
        composeRule.onNodeWithTag("playlist-choose-image").performScrollTo().performClick()
        composeRule.onNodeWithTag("playlist-description-input").performScrollTo().performTouchInput { click() }
        val save = composeRule.onNodeWithTag("playlist-save-button")
        try {
            // Wait for IME insets and focus scrolling while keeping the full-height visibility requirement.
            composeRule.waitUntil(5_000) {
                save.performScrollTo()
                val full = save.getUnclippedBoundsInRoot()
                val visible = save.getBoundsInRoot()
                val node = save.fetchSemanticsNode()
                val screen = visibleDisplayFrame()
                full.bottom > full.top && full.bottom - full.top == visible.bottom - visible.top &&
                    node.positionOnScreen.y >= screen.top && node.positionOnScreen.y + node.size.height <= screen.bottom
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val node = save.fetchSemanticsNode()
            throw AssertionError("Save is outside the visible display: position=${node.positionOnScreen}, " +
                "size=${node.size}, display=${visibleDisplayFrame()}, root=${composeRule.onRoot().fetchSemanticsNode().boundsInWindow}", failure)
        } finally {
            captureScreen(composeRule.onRoot(), "playlist-editor")
            captureDeviceScreen("playlist-keyboard")
        }
        save.assertIsDisplayed()
        val settledFull = save.getUnclippedBoundsInRoot()
        val settledVisible = save.getBoundsInRoot()
        assertEquals(settledFull.bottom - settledFull.top, settledVisible.bottom - settledVisible.top)
        val button = save.fetchSemanticsNode()
        val screen = visibleDisplayFrame()
        assertTrue(button.positionOnScreen.y >= screen.top && button.positionOnScreen.y + button.size.height <= screen.bottom)
        save.performClick().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-name-input").performScrollTo().assertIsNotEnabled()
        composeRule.onAllNodesWithTag("playlist-editor-back").assertAll(isNotEnabled())
        composeRule.onNodeWithTag("playlist-cancel-button").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(isSaving = false, failure = PlaylistEditorFailure.SAVE) }
        composeRule.onNodeWithTag("playlist-editor-error").performScrollTo().assertIsDisplayed()
        captureScreen(composeRule.onRoot(), "playlist-save-error")
        composeRule.onNodeWithTag("playlist-name-input").assertTextContains("New playlist")
        composeRule.onNodeWithTag("playlist-description-input").assertTextContains("A description\nwith two lines")
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(2, saved)
            assertEquals(1, picked)
            state = state.copy(isSaving = false)
        }
        composeRule.onNodeWithTag("playlist-cancel-button").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun imageRemovalCanBeUndoneAndImageLoadingBlocksSave() {
        var state by mutableStateOf(PlaylistEditorState(content = playlist, name = playlist.title,
            imageUrl = "content://io.github.playmusic.test/missing-image"))
        render {
            PlaylistEditorScreen(state, {}, {}, {},
                onUndoImage = { state = state.copy(removeImage = false, imageJpeg = null) },
                onRemoveImage = { state = state.copy(removeImage = true, imageJpeg = null) }, onSave = {}, onCancel = {})
        }
        composeRule.onNodeWithTag("playlist-remove-image").performScrollTo().performClick()
        composeRule.onNodeWithTag("playlist-editor-artwork").assertDoesNotExist()
        composeRule.onNodeWithTag("playlist-remove-image").assertDoesNotExist()
        composeRule.onNodeWithTag("playlist-undo-image").performScrollTo().performClick()
        composeRule.onNodeWithTag("playlist-remove-image").performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { state = state.copy(isLoadingImage = true) }
        composeRule.onNodeWithTag("playlist-choose-image").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { state = state.copy(isLoadingImage = false, failure = PlaylistEditorFailure.IMAGE) }
        composeRule.onNodeWithTag("playlist-editor-error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-save-button").performScrollTo().assertIsEnabled()
    }

    @Test fun playlistCreationIsAvailableFromItsLibrarySection() {
        var section by mutableStateOf(LibrarySection.PLAYLISTS)
        var created = 0
        render {
            HomeScreen(PlayUiState(isLoggedIn = true, selectedSection = section),
                onSectionSelected = { section = it }, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {},
                onPlay = {}, onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {},
                onCreatePlaylist = { created++ })
        }
        composeRule.onNodeWithTag("create-playlist-button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("section-albums").performClick()
        composeRule.onNodeWithTag("create-playlist-button").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, created) }
    }

    @Test fun editingAndDeletionAreAvailableOnlyWithAccountCapabilities() {
        var metadata by mutableStateOf(PlaylistMetadata(playlist.uri, playlist.title, "Description", null, "owner",
            canEdit = false, canDelete = false, isOwned = false))
        var edits = 0
        var deletions = 0
        render {
            ContentDetailScreen(playlist, ContentDetail(playlist, playlistMetadata = metadata), false,
                {}, {}, {}, {}, { edits++ }, { deletions++ })
        }
        composeRule.onNodeWithTag("edit-playlist-button").assertDoesNotExist()
        composeRule.onNodeWithTag("delete-playlist-button").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-play-button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("detail-description").performScrollTo().assertTextEquals("Description")
        composeRule.runOnIdle { metadata = metadata.copy(canEdit = true, canDelete = true, isOwned = true) }
        composeRule.onNodeWithTag("edit-playlist-button").performScrollTo().performClick()
        composeRule.onNodeWithTag("delete-playlist-button").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, edits); assertEquals(1, deletions) }
    }

    @Test fun deleteRequiresConfirmationAndFailureCanBeRetried() {
        var deleting by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var confirmations = 0
        var cancellations = 0
        render {
            DeletePlaylistDialog(playlist, deleting, failed,
                onDelete = { confirmations++; deleting = true }, onCancel = { cancellations++ })
        }
        composeRule.onNodeWithTag("playlist-delete-cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations); assertEquals(0, confirmations) }
        captureScreen(composeRule.onNodeWithTag("playlist-delete-dialog"), "playlist-delete")
        composeRule.onNodeWithTag("playlist-delete-confirm").performClick().assertIsNotEnabled()
        composeRule.onNodeWithTag("playlist-delete-cancel").assertIsNotEnabled()
        composeRule.runOnIdle { deleting = false; failed = true }
        composeRule.onNodeWithTag("playlist-delete-error").assertIsDisplayed()
        captureScreen(composeRule.onNodeWithTag("playlist-delete-dialog"), "playlist-delete-error")
        composeRule.onNodeWithTag("playlist-delete-confirm").performClick()
        composeRule.runOnIdle { assertEquals(2, confirmations) }
    }

    private fun captureDeviceScreen(name: String) {
        val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshotPrefix") ?: return
        require(prefix.matches(Regex("[a-z0-9-]+")))
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = instrumentation.targetContext.filesDir.resolve("ui-verification").apply { mkdirs() }
            directory.resolve("$prefix-$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun visibleDisplayFrame(): android.graphics.Rect = android.graphics.Rect().also { frame ->
        composeRule.runOnUiThread { composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(frame) }
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize(), content = content) } }
    }
}
