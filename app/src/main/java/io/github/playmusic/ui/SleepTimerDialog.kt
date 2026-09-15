package io.github.playmusic.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.playmusic.R
import io.github.playmusic.data.playback.SleepTimerManager
import io.github.playmusic.data.playback.SleepTimerMode
import io.github.playmusic.data.playback.SleepTimerRequest
import io.github.playmusic.data.playback.SleepTimerState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SleepTimerDialog(timer: SleepTimerManager, onDismiss: () -> Unit) {
    val state by timer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    var busy by remember { mutableStateOf(false) }
    var unavailable by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { timer.refresh() }
    }
    DisposableEffect(timer, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { timer.refresh() }
        }
        lifecycle.addObserver(observer)
        scope.launch { timer.refresh() }
        onDispose { lifecycle.removeObserver(observer) }
    }
    SleepTimerDialogContent(
        state = if (unavailable) state.copy(error = R.string.sleep_timer_settings_unavailable) else state,
        busy = busy, onDismiss = onDismiss,
        onSchedule = { request ->
            busy = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { try { if (timer.schedule(request)) onDismiss() } finally { busy = false } }
        },
        onCancel = {
            busy = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { try { timer.cancel() } finally { busy = false } }
        },
        onLockPermission = {
            try { permission.launch(timer.lockPermissionIntent()) }
            catch (_: ActivityNotFoundException) { unavailable = true }
        },
        onAlarmPermission = {
            try { permission.launch(timer.alarmPermissionIntent()) }
            catch (_: ActivityNotFoundException) { unavailable = true }
        },
    )
}

@Composable
internal fun SleepTimerDialogContent(
    state: SleepTimerState,
    onDismiss: () -> Unit,
    onSchedule: (SleepTimerRequest) -> Unit,
    onCancel: () -> Unit,
    onLockPermission: () -> Unit,
    onAlarmPermission: () -> Unit,
    busy: Boolean = false,
) {
    var clock by rememberSaveable { mutableStateOf(false) }
    var hours by rememberSaveable { mutableStateOf("0") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    val request = SleepTimerRequest(if (clock) SleepTimerMode.CLOCK else SleepTimerMode.DURATION,
        hours.toIntOrNull() ?: -1, minutes.toIntOrNull() ?: -1)
    val windowSize = LocalWindowInfo.current.containerSize
    val compact = with(LocalDensity.current) { windowSize.height.toDp() < 480.dp }
    AlertDialog(
        modifier = Modifier.testTag("sleep-timer-dialog"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = if (compact) null else ({ Text(stringResource(R.string.sleep_timer_title)) }),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sleep_timer_description))
                state.deadlineMillis?.let {
                    Text(if (state.fading) stringResource(R.string.sleep_timer_fading) else
                        stringResource(R.string.sleep_timer_scheduled,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))),
                        modifier = Modifier.testTag("sleep-timer-status"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !clock, onClick = { clock = false },
                        label = { Text(stringResource(R.string.sleep_timer_duration)) }, modifier = Modifier.testTag("sleep-timer-duration"))
                    FilterChip(selected = clock, onClick = { clock = true },
                        label = { Text(stringResource(R.string.sleep_timer_clock)) }, modifier = Modifier.testTag("sleep-timer-clock"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = hours, onValueChange = { hours = numericTimeInput(it) },
                        label = { Text(stringResource(if (clock) R.string.sleep_timer_hour_of_day else R.string.sleep_timer_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        isError = hours.toIntOrNull()?.let { it !in 0..(if (clock) 23 else 99) } ?: true,
                        modifier = Modifier.weight(1f).testTag("sleep-timer-hours"))
                    OutlinedTextField(value = minutes, onValueChange = { minutes = numericTimeInput(it) },
                        label = { Text(stringResource(R.string.sleep_timer_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        isError = minutes.toIntOrNull()?.let { it !in 0..59 } ?: true,
                        modifier = Modifier.weight(1f).testTag("sleep-timer-minutes"))
                }
                if (clock) Text(stringResource(R.string.sleep_timer_next_day), style = MaterialTheme.typography.bodySmall)
                if (!state.lockSupported) Text(stringResource(R.string.sleep_timer_lock_unsupported))
                else if (!state.canLock) {
                    Text(stringResource(R.string.sleep_timer_lock_explanation))
                    TextButton(onClick = onLockPermission, modifier = Modifier.testTag("sleep-timer-lock-permission")) {
                        Text(stringResource(R.string.sleep_timer_allow_lock))
                    }
                }
                if (!state.canSchedule) {
                    Text(stringResource(R.string.sleep_timer_alarm_required))
                    TextButton(onClick = onAlarmPermission, modifier = Modifier.testTag("sleep-timer-alarm-permission")) {
                        Text(stringResource(R.string.sleep_timer_allow_alarm))
                    }
                }
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("sleep-timer-error")) }
                if (state.deadlineMillis != null) TextButton(onClick = onCancel,
                    enabled = !busy, modifier = Modifier.testTag("sleep-timer-cancel")) {
                    Text(stringResource(R.string.sleep_timer_cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSchedule(request) }, enabled = request.isValid && state.canLock && state.canSchedule && !busy,
                modifier = Modifier.testTag("sleep-timer-start")) { Text(stringResource(R.string.sleep_timer_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy,
            modifier = Modifier.testTag("sleep-timer-close")) { Text(stringResource(R.string.sleep_timer_close)) } },
    )
}

private fun numericTimeInput(value: String): String = value.filter(Char::isDigit).take(2)
