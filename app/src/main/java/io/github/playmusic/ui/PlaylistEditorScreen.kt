package io.github.playmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.PlaylistLimits

data class PlaylistEditorState(
    val content: MusicContent? = null,
    val name: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val imageJpeg: ByteArray? = null,
    val removeImage: Boolean = false,
    val creationNeedsCompletion: Boolean = false,
    val isSaving: Boolean = false,
    val isLoadingImage: Boolean = false,
    val failure: PlaylistEditorFailure? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && name.length <= PlaylistLimits.MAX_NAME_LENGTH &&
        description.length <= PlaylistLimits.MAX_DESCRIPTION_LENGTH && !isSaving && !isLoadingImage
}

enum class PlaylistEditorFailure { SAVE, IMAGE, PARTIAL_SAVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistEditorScreen(
    state: PlaylistEditorState,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onChooseImage: () -> Unit,
    onUndoImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // A landscape keyboard can leave less than one touch target below the fixed toolbar.
    val showToolbar = windowHeight >= 480.dp || WindowInsets.ime.getBottom(density) == 0
    BackHandler { if (!state.isSaving) onCancel() }
    Scaffold(
        modifier = Modifier.imePadding().testTag("playlist-editor"),
        topBar = {
            if (showToolbar) TopAppBar(
                title = { Text(stringResource(if (state.content == null) R.string.create_playlist else R.string.edit_playlist)) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !state.isSaving,
                        modifier = Modifier.testTag("playlist-editor-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.content == null) Text(stringResource(R.string.playlist_private_description))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val image = if (state.removeImage) null else state.imageJpeg ?: state.imageUrl
                if (image != null) {
                    AsyncImage(model = image, contentDescription = stringResource(R.string.playlist_image),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(144.dp).clip(RoundedCornerShape(12.dp)).testTag("playlist-editor-artwork"))
                } else {
                    Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LibraryMusic, stringResource(R.string.playlist_image), Modifier.size(48.dp))
                    }
                }
                if (state.isLoadingImage) CircularProgressIndicator(Modifier.testTag("playlist-image-loading"))
            }
            TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChooseImage() },
                enabled = !state.isSaving && !state.isLoadingImage,
                modifier = Modifier.fillMaxWidth().testTag("playlist-choose-image")) {
                Text(stringResource(R.string.playlist_choose_image))
            }
            if (state.imageJpeg != null || state.removeImage) {
                TextButton(onClick = onUndoImage, enabled = !state.isSaving && !state.isLoadingImage,
                    modifier = Modifier.fillMaxWidth().testTag("playlist-undo-image")) {
                    Text(stringResource(R.string.playlist_undo_image))
                }
            }
            if (state.imageUrl != null && !state.removeImage) {
                TextButton(onClick = onRemoveImage, enabled = !state.isSaving && !state.isLoadingImage,
                    modifier = Modifier.fillMaxWidth().testTag("playlist-remove-image")) {
                    Text(stringResource(R.string.playlist_remove_image))
                }
            }
            OutlinedTextField(value = state.name, onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true,
                isError = state.name.length > PlaylistLimits.MAX_NAME_LENGTH,
                supportingText = { if (state.name.length > PlaylistLimits.MAX_NAME_LENGTH)
                    Text(stringResource(R.string.playlist_text_limit, PlaylistLimits.MAX_NAME_LENGTH)) },
                enabled = !state.isSaving, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("playlist-name-input"))
            OutlinedTextField(value = state.description, onValueChange = onDescriptionChanged,
                label = { Text(stringResource(R.string.playlist_description)) },
                minLines = if (windowHeight < 480.dp) 1 else 3, maxLines = 6,
                isError = state.description.length > PlaylistLimits.MAX_DESCRIPTION_LENGTH,
                supportingText = { if (state.description.length > PlaylistLimits.MAX_DESCRIPTION_LENGTH)
                    Text(stringResource(R.string.playlist_text_limit, PlaylistLimits.MAX_DESCRIPTION_LENGTH)) },
                enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().testTag("playlist-description-input"))
            state.failure?.let { failure ->
                Text(stringResource(when (failure) {
                    PlaylistEditorFailure.SAVE -> R.string.playlist_save_failed
                    PlaylistEditorFailure.IMAGE -> R.string.playlist_image_failed
                    PlaylistEditorFailure.PARTIAL_SAVE -> R.string.playlist_partially_saved
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("playlist-editor-error")
                    .semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSave() },
                enabled = state.canSave, modifier = Modifier.fillMaxWidth().testTag("playlist-save-button")) {
                if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (state.content == null) R.string.create_playlist else R.string.save))
            }
            TextButton(onClick = onCancel, enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().testTag("playlist-cancel-button")) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun DeletePlaylistDialog(
    content: MusicContent,
    isDeleting: Boolean,
    failed: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("playlist-delete-dialog"),
        onDismissRequest = { if (!isDeleting) onCancel() },
        title = { Text(stringResource(R.string.delete_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.delete_playlist_confirmation,
                    content.title.ifBlank { stringResource(R.string.untitled_playlist) }))
                if (failed) Text(stringResource(R.string.playlist_delete_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("playlist-delete-error").semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete, enabled = !isDeleting, modifier = Modifier.testTag("playlist-delete-confirm")) {
                if (isDeleting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.delete_playlist), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isDeleting, modifier = Modifier.testTag("playlist-delete-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
