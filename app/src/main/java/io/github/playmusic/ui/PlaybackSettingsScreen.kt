package io.github.playmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.playmusic.R
import io.github.playmusic.data.playback.PlaybackTransitionSettings
import io.github.playmusic.data.playback.PlaybackTransitionState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettingsScreen(state: PlaybackTransitionState,
    onChange: (PlaybackTransitionSettings) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val current by rememberUpdatedState(state.settings)
    Scaffold(modifier = Modifier.fillMaxSize().testTag("playback-settings-screen"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.playback_settings_title)) }, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("playback-settings-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("playback-settings-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.isReady) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.storageFailed) item("error") {
                Text(stringResource(R.string.playback_settings_save_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, modifier = Modifier.testTag("playback-settings-retry")) {
                    Text(stringResource(R.string.refresh))
                }
            }
            item("fade-in") {
                FadeSetting(stringResource(R.string.playback_fade_in), current.fadeInEnabled, current.fadeInSeconds,
                    state.isReady, "fade-in", { onChange(current.copy(fadeInEnabled = it)) },
                    { onChange(current.copy(fadeInSeconds = it)) })
            }
            item("fade-out") {
                FadeSetting(stringResource(R.string.playback_fade_out), current.fadeOutEnabled, current.fadeOutSeconds,
                    state.isReady, "fade-out", { onChange(current.copy(fadeOutEnabled = it)) },
                    { onChange(current.copy(fadeOutSeconds = it)) })
            }
            item("crossfade") {
                FadeSetting(stringResource(R.string.playback_crossfade), current.crossfadeEnabled, current.crossfadeSeconds,
                    state.isReady, "crossfade", { onChange(current.copy(crossfadeEnabled = it)) },
                    { onChange(current.copy(crossfadeSeconds = it)) })
            }
            item("seek-crossfade") {
                FadeSetting(stringResource(R.string.playback_seek_crossfade), current.seekCrossfadeEnabled, current.seekCrossfadeSeconds,
                    state.isReady, "seek-crossfade", { onChange(current.copy(seekCrossfadeEnabled = it)) },
                    { onChange(current.copy(seekCrossfadeSeconds = it)) })
            }
            item("music-cache") {
                CacheSetting(stringResource(R.string.playback_music_cache), current.musicCacheGb,
                    state.isReady, "music-cache", { onChange(current.copy(musicCacheGb = it)) })
                Text(stringResource(R.string.playback_music_cache_hint), style = MaterialTheme.typography.bodySmall)
            }
            item("normalizer") {
                PlaybackSettingSwitch(stringResource(R.string.playback_peak_normalizer), current.peakNormalizationEnabled,
                    state.isReady, "peak-normalizer") { onChange(current.copy(peakNormalizationEnabled = it)) }
            }
            item("automix") {
                PlaybackSettingSwitch(stringResource(R.string.playback_automix), current.automixEnabled,
                    state.isReady, "automix") { onChange(current.copy(automixEnabled = it)) }
                Text(stringResource(R.string.playback_automix_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FadeSetting(label: String, checked: Boolean, seconds: Int, ready: Boolean, tag: String,
    onToggle: (Boolean) -> Unit, onSeconds: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column {
        PlaybackSettingSwitch(label, checked, ready, tag, onToggle)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playback_fade_seconds, seconds), modifier = Modifier.testTag("$tag-seconds"))
            Slider(value = seconds.toFloat(), onValueChange = { if (it.isFinite()) onSeconds(it.roundToInt().coerceIn(1, 12)) },
                onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                valueRange = 1f..12f, steps = 10, enabled = ready && checked,
                modifier = Modifier.weight(1f).padding(start = 16.dp).testTag("$tag-slider").semantics { contentDescription = label })
        }
    }
}

@Composable
private fun CacheSetting(label: String, gigabytes: Int, ready: Boolean, tag: String, onGigabytes: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column {
        Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playback_music_cache_gb, gigabytes), modifier = Modifier.testTag("$tag-gb"))
            Slider(value = gigabytes.toFloat(), onValueChange = { if (it.isFinite()) onGigabytes(it.roundToInt().coerceIn(1, 64)) },
                onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                valueRange = 1f..64f, steps = 62, enabled = ready,
                modifier = Modifier.weight(1f).padding(start = 16.dp).testTag("$tag-slider").semantics { contentDescription = label })
        }
    }
}

@Composable
private fun PlaybackSettingSwitch(label: String, checked: Boolean, ready: Boolean, tag: String, onChange: (Boolean) -> Unit) {    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(it)
        }, enabled = ready, modifier = Modifier.testTag("$tag-switch").semantics { contentDescription = label })
    }
}
