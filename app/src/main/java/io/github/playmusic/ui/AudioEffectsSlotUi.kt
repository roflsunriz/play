package io.github.playmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.playmusic.R
import io.github.playmusic.data.audio.EqualizerBands
import io.github.playmusic.data.audio.EqualizerSlot

@Composable
internal fun AudioEffectsStorageError(onRetrySave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag("audio-effects-storage-error").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.audio_effects_storage_failed), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onRetrySave, modifier = Modifier.testTag("audio-effects-retry-save")) {
                Text(stringResource(R.string.audio_effects_retry_save))
            }
        }
    }
}

@Composable
internal fun AudioEffectsSlotCard(
    index: Int,
    slot: EqualizerSlot?,
    onLoad: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val slotLabel = stringResource(R.string.audio_effects_slot_number, index + 1)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("audio-effects-slot-$index")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(slotLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(slot?.name ?: stringResource(R.string.audio_effects_slot_empty), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("audio-effects-slot-name-$index"))
                }
                if (slot != null) Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("audio-effects-slot-menu-$index")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.audio_effects_slot_actions, slotLabel))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_effects_overwrite)) },
                            onClick = { expanded = false; onOverwrite() }, modifier = Modifier.testTag("audio-effects-slot-overwrite-$index"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_effects_rename)) },
                            onClick = { expanded = false; onRename() }, modifier = Modifier.testTag("audio-effects-slot-rename-$index"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_effects_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { expanded = false; onDelete() }, modifier = Modifier.testTag("audio-effects-slot-delete-$index"))
                    }
                }
            }
            if (slot != null) {
                val status = stringResource(if (slot.settings.enabled) R.string.audio_effects_on else R.string.audio_effects_off)
                Text(stringResource(R.string.audio_effects_slot_summary, status, audioEffectsGain(slot.settings.preampDb),
                    slot.settings.bandGainsDb.count { it != 0f }), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onLoad() },
                    modifier = Modifier.fillMaxWidth().testTag("audio-effects-slot-load-$index")) {
                    Text(stringResource(R.string.audio_effects_apply))
                }
            } else {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag("audio-effects-slot-save-$index")) {
                    Text(stringResource(R.string.audio_effects_save_current))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioEffectsNameEditor(
    index: Int,
    rename: Boolean,
    name: String,
    isReady: Boolean,
    storageFailed: Boolean,
    onNameChanged: (String) -> Unit,
    onRetrySave: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val showToolbar = windowHeight >= 480.dp || WindowInsets.ime.getBottom(density) == 0
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    var attempted by rememberSaveable(index, rename) { mutableStateOf(false) }
    val trimmedName = name.trim()
    val nameLength = trimmedName.codePointCount(0, trimmedName.length)
    val error = when {
        trimmedName.isEmpty() -> R.string.audio_effects_name_required
        nameLength > EqualizerBands.MAX_NAME_LENGTH -> R.string.audio_effects_name_too_long
        trimmedName.any { Character.isISOControl(it.code) } -> R.string.audio_effects_name_control
        else -> null
    }
    val submit = {
        attempted = true
        if (isReady && error == null) {
            keyboard?.hide()
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSubmit(trimmedName)
        }
    }
    BackHandler(onBack = onCancel)
    Scaffold(modifier = Modifier.fillMaxSize().imePadding().testTag("audio-effects-name-editor"), topBar = {
        if (showToolbar) TopAppBar(title = {
            Text(stringResource(if (rename) R.string.audio_effects_rename else R.string.audio_effects_save_current))
        }, navigationIcon = {
            IconButton(onClick = onCancel, modifier = Modifier.testTag("audio-effects-name-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("audio-effects-name-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.audio_effects_slot_number, index + 1), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = onNameChanged, enabled = isReady,
                label = { Text(stringResource(R.string.audio_effects_name)) }, singleLine = true,
                isError = attempted && error != null,
                supportingText = {
                    if (attempted && error != null) Text(stringResource(error, EqualizerBands.MAX_NAME_LENGTH),
                        modifier = Modifier.testTag("audio-effects-name-error").semantics { liveRegion = LiveRegionMode.Polite })
                    else Text(stringResource(R.string.audio_effects_name_count, nameLength, EqualizerBands.MAX_NAME_LENGTH))
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("audio-effects-name-input"))
            if (storageFailed) AudioEffectsStorageError(onRetrySave)
            Button(onClick = submit, enabled = isReady, modifier = Modifier.fillMaxWidth().testTag("audio-effects-name-submit")) {
                Text(stringResource(R.string.save))
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("audio-effects-name-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
internal fun AudioEffectsSlotConfirmation(
    index: Int,
    name: String,
    deleting: Boolean,
    isReady: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(modifier = Modifier.testTag("audio-effects-confirm"), onDismissRequest = onCancel,
        title = { Text(stringResource(if (deleting) R.string.audio_effects_delete else R.string.audio_effects_overwrite)) },
        text = { Text(stringResource(if (deleting) R.string.audio_effects_delete_confirmation else R.string.audio_effects_overwrite_confirmation,
            index + 1, name), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = isReady, modifier = Modifier.testTag("audio-effects-confirm-submit")) {
                Text(stringResource(if (deleting) R.string.audio_effects_delete else R.string.audio_effects_overwrite),
                    color = if (deleting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }, dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("audio-effects-confirm-cancel")) { Text(stringResource(R.string.cancel)) }
        })
}
