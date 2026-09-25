package io.github.playmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.playmusic.R

@Composable
internal fun WebSessionDialog(
    input: String,
    invalid: Boolean,
    saveFailed: Boolean,
    saved: Boolean,
    onInput: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("web-session-dialog"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.web_session_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.web_session_description))
                if (saved) Text(stringResource(R.string.web_session_saved),
                    modifier = Modifier.testTag("web-session-saved"))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth().testTag("web-session-input"),
                    label = { Text(stringResource(R.string.web_session_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    maxLines = 4,
                    isError = invalid,
                    supportingText = {
                        if (invalid) Text(stringResource(R.string.web_session_invalid),
                            modifier = Modifier.testTag("web-session-error")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    },
                )
                if (saveFailed) Text(stringResource(R.string.web_session_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("web-session-save-error")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                if (saved) TextButton(onClick = onRemove, modifier = Modifier.testTag("web-session-remove")) {
                    Text(stringResource(R.string.web_session_remove))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = input.isNotBlank(),
                modifier = Modifier.testTag("web-session-save")) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("web-session-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
