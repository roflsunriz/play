package io.github.playmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import io.github.playmusic.R
import io.github.playmusic.data.audio.AudioEffectsState
import io.github.playmusic.data.audio.EqualizerBands
import io.github.playmusic.data.audio.EqualizerPreset
import io.github.playmusic.data.audio.EqualizerSettings
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioEffectsScreen(
    state: AudioEffectsState,
    onSettingsChanged: (EqualizerSettings) -> Unit,
    onPresetSelected: (EqualizerPreset) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onSaveSlot: (Int, String) -> Unit,
    onRenameSlot: (Int, String) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    onRetrySave: () -> Unit,
    onBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentSettings by rememberUpdatedState(state.settings)
    var section by rememberSaveable { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    val adjustmentScroll = rememberLazyListState()
    val slotsScroll = rememberLazyListState()
    var nameSlot by rememberSaveable { mutableIntStateOf(-1) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var confirmSlot by rememberSaveable { mutableIntStateOf(-1) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var overwriteName by rememberSaveable { mutableStateOf("") }

    if (nameSlot in 0 until EqualizerBands.SLOT_COUNT) {
        AudioEffectsNameEditor(nameSlot, rename, draftName, state.isReady, state.storageFailed,
            onNameChanged = { draftName = it }, onRetrySave = onRetrySave,
            onCancel = { nameSlot = -1 }, onSubmit = { validName ->
                if (rename) {
                    onRenameSlot(nameSlot, validName)
                    nameSlot = -1
                } else if (state.slots.getOrNull(nameSlot) != null) {
                    overwriteName = validName
                    deleting = false
                    confirmSlot = nameSlot
                } else {
                    onSaveSlot(nameSlot, validName)
                    nameSlot = -1
                }
            })
    } else {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize().testTag("audio-effects-screen"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("audio-effects-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Text(stringResource(R.string.audio_effects_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    val enabledLabel = stringResource(R.string.audio_effects_enable)
                    val statusLabel = stringResource(if (state.settings.enabled) R.string.audio_effects_on else R.string.audio_effects_off)
                    Switch(checked = state.settings.enabled, enabled = state.isReady,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSettingsChanged(currentSettings.copy(enabled = it))
                        }, modifier = Modifier.testTag("audio-effects-enable").semantics {
                            contentDescription = enabledLabel
                            stateDescription = statusLabel
                        })
                }
                if (state.storageFailed) AudioEffectsStorageError(onRetrySave)
                PrimaryTabRow(selectedTabIndex = section) {
                    Tab(selected = section == 0, onClick = { section = 0 },
                        text = { Text(stringResource(R.string.audio_effects_tab_adjust)) },
                        modifier = Modifier.testTag("audio-effects-tab-adjust"))
                    Tab(selected = section == 1, onClick = { section = 1 },
                        text = { Text(stringResource(R.string.audio_effects_tab_slots)) },
                        modifier = Modifier.testTag("audio-effects-tab-slots"))
                }
                if (!state.isReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val loading = stringResource(R.string.audio_effects_loading)
                        CircularProgressIndicator(Modifier.testTag("audio-effects-loading").semantics { contentDescription = loading })
                    }
                } else if (section == 0) {
                    val selectedGroup = group.coerceIn(0, GROUP_COUNT - 1)
                    AudioEffectsGroupSelector(selectedGroup, onGroup = { group = it })
                    val start = selectedGroup * BANDS_PER_GROUP
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("audio-effects-adjust-list"), state = adjustmentScroll,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item("preamp") {
                            AudioEffectsGainControl(stringResource(R.string.audio_effects_preamp), state.settings.preampDb,
                                EqualizerBands.MIN_PREAMP_DB..EqualizerBands.MAX_PREAMP_DB, enabled = state.settings.enabled,
                                sliderTag = "audio-effects-preamp-slider", valueTag = "audio-effects-preamp-value",
                                resetTag = "audio-effects-preamp-reset", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                onChange = { onSettingsChanged(currentSettings.copy(preampDb = it)) })
                        }
                        item("presets") { AudioEffectsPresetMenu(onPresetSelected) }
                        item("overview") { AudioEffectsOverview(state.settings, start until start + BANDS_PER_GROUP) }
                        items((start until minOf(start + BANDS_PER_GROUP, EqualizerBands.COUNT)).toList(), key = { it }) { index ->
                            val frequency = audioEffectsFrequency(EqualizerBands.frequenciesHz[index])
                            AudioEffectsGainControl(frequency, state.settings.bandGainsDb[index],
                                EqualizerBands.MIN_GAIN_DB..EqualizerBands.MAX_GAIN_DB, enabled = state.settings.enabled,
                                sliderTag = "audio-effects-band-slider-$index", valueTag = "audio-effects-band-value-$index",
                                resetTag = "audio-effects-band-reset-$index",
                                modifier = Modifier.padding(horizontal = 16.dp).testTag("audio-effects-band-$index"),
                                onChange = { gain ->
                                    val latest = currentSettings
                                    onSettingsChanged(latest.copy(bandGainsDb = latest.bandGainsDb.mapIndexed { band, value ->
                                        if (band == index) gain else value
                                    }))
                                })
                        }
                        item("footer") { Text(stringResource(if (state.settings.enabled) R.string.audio_effects_live_hint else R.string.audio_effects_off_hint),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("audio-effects-slots-list"), state = slotsScroll,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item("intro") { Text(stringResource(R.string.audio_effects_slots_hint), modifier = Modifier.padding(16.dp)) }
                        items((0 until EqualizerBands.SLOT_COUNT).toList(), key = { it }) { index ->
                            AudioEffectsSlotCard(index, state.slots.getOrNull(index),
                                onLoad = { onLoadSlot(index) },
                                onSave = { nameSlot = index; rename = false; draftName = "" },
                                onRename = { nameSlot = index; rename = true; draftName = state.slots.getOrNull(index)?.name.orEmpty() },
                                onOverwrite = {
                                    overwriteName = state.slots.getOrNull(index)?.name.orEmpty()
                                    deleting = false
                                    confirmSlot = index
                                }, onDelete = { deleting = true; confirmSlot = index })
                        }
                        item("bottom") { Box(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
    if (confirmSlot in 0 until EqualizerBands.SLOT_COUNT) {
        AudioEffectsSlotConfirmation(confirmSlot, state.slots.getOrNull(confirmSlot)?.name.orEmpty(), deleting,
            isReady = state.isReady, onCancel = { confirmSlot = -1 }, onConfirm = {
                if (deleting) onDeleteSlot(confirmSlot) else onSaveSlot(confirmSlot, overwriteName)
                confirmSlot = -1
                nameSlot = -1
            })
    }
}

@Composable
private fun AudioEffectsGroupSelector(group: Int, onGroup: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onGroup(group - 1) }, enabled = group > 0, modifier = Modifier.testTag("audio-effects-group-previous")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.audio_effects_previous_bands))
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("audio-effects-group-menu")) {
                Text(audioEffectsGroupLabel(group), modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                repeat(GROUP_COUNT) { index ->
                    DropdownMenuItem(text = { Text(audioEffectsGroupLabel(index)) },
                        onClick = { expanded = false; onGroup(index) }, modifier = Modifier.testTag("audio-effects-group-$index"))
                }
            }
        }
        IconButton(onClick = { onGroup(group + 1) }, enabled = group < GROUP_COUNT - 1,
            modifier = Modifier.testTag("audio-effects-group-next")) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.audio_effects_next_bands))
        }
    }
}

@Composable
private fun AudioEffectsPresetMenu(onSelect: (EqualizerPreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("audio-effects-preset-menu")) {
                Text(stringResource(R.string.audio_effects_presets))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                EqualizerPreset.entries.forEach { preset ->
                    DropdownMenuItem(text = { Text(stringResource(audioEffectsPresetLabel(preset))) }, onClick = {
                        expanded = false
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(preset)
                    }, modifier = Modifier.testTag("audio-effects-preset-${preset.name.lowercase(Locale.ROOT)}"))
                }
            }
        }
        TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(EqualizerPreset.FLAT) },
            modifier = Modifier.testTag("audio-effects-flat")) { Text(stringResource(R.string.audio_effects_flat)) }
    }
}

@Composable
private fun AudioEffectsGainControl(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    sliderTag: String, valueTag: String, resetTag: String, modifier: Modifier, onChange: (Float) -> Unit,
) {
    val gain = audioEffectsGain(value)
    val haptics = LocalHapticFeedback.current
    val reset = stringResource(R.string.audio_effects_reset_control, label)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(gain, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(valueTag))
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(0f) },
                enabled = enabled && value != 0f, modifier = Modifier.size(40.dp).testTag(resetTag)) {
                Icon(Icons.Default.Refresh, reset, Modifier.size(20.dp))
            }
        }
        Slider(value = value, onValueChange = {
            if (it.isFinite()) onChange(((it.coerceIn(range.start, range.endInclusive) * 2).roundToInt() / 2f)
                .coerceIn(range.start, range.endInclusive))
        }, onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            valueRange = range, steps = ((range.endInclusive - range.start) * 2).toInt() - 1, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(sliderTag).semantics { contentDescription = label; stateDescription = gain })
    }
}

@Composable
private fun AudioEffectsOverview(settings: EqualizerSettings, selected: IntRange) {
    val primary = if (settings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val positions = remember { EqualizerBands.frequenciesHz.map { frequency ->
        (ln(frequency / EqualizerBands.frequenciesHz.first()) / ln(EqualizerBands.frequenciesHz.last() / EqualizerBands.frequenciesHz.first()))
    } }
    val description = stringResource(R.string.audio_effects_overview)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(description, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(96.dp).testTag("audio-effects-overview").semantics { contentDescription = description }) {
            val inset = 5.dp.toPx()
            val availableHeight = (size.height - inset * 2).coerceAtLeast(0f)
            val availableWidth = (size.width - inset * 2).coerceAtLeast(0f)
            for (level in listOf(0f, .5f, 1f)) {
                val y = inset + availableHeight * level
                drawLine(grid, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path()
            settings.bandGainsDb.forEachIndexed { index, gain ->
                val fraction = positions[index]
                val x = inset + availableWidth * (if (rtl) 1f - fraction else fraction)
                val y = inset + availableHeight * (EqualizerBands.MAX_GAIN_DB - gain) /
                    (EqualizerBands.MAX_GAIN_DB - EqualizerBands.MIN_GAIN_DB)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(primary.copy(alpha = if (index in selected) 1f else .4f),
                    radius = if (index in selected) 3.dp.toPx() else 1.5.dp.toPx(), center = Offset(x, y))
            }
            drawPath(path, primary, style = Stroke(width = 2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(audioEffectsFrequency(EqualizerBands.frequenciesHz.first()), style = MaterialTheme.typography.labelSmall)
            Text(audioEffectsFrequency(EqualizerBands.frequenciesHz.last()), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun audioEffectsGain(value: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val formatted = remember(value, locale, rtl) {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1; maximumFractionDigits = 1; isGroupingUsed = false
        }
        val number = (if (value > 0) "+" else "") + formatter.format(if (value == 0f) 0f else value)
        // Keep the sign with its number when the unit and surrounding text run right to left.
        BidiFormatter.getInstance(rtl).unicodeWrap(number, TextDirectionHeuristicsCompat.LTR)
    }
    return stringResource(R.string.audio_effects_gain_db, formatted)
}

@Composable
private fun audioEffectsFrequency(hz: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatted = remember(hz, locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; isGroupingUsed = false }
            .format(if (hz >= 1_000) hz / 1_000 else hz)
    }
    return stringResource(if (hz >= 1_000) R.string.audio_effects_frequency_khz else R.string.audio_effects_frequency_hz, formatted)
}

@Composable
private fun audioEffectsGroupLabel(group: Int): String {
    val first = group * BANDS_PER_GROUP
    return stringResource(R.string.audio_effects_frequency_range, audioEffectsFrequency(EqualizerBands.frequenciesHz[first]),
        audioEffectsFrequency(EqualizerBands.frequenciesHz[minOf(first + BANDS_PER_GROUP, EqualizerBands.COUNT) - 1]))
}

private fun audioEffectsPresetLabel(preset: EqualizerPreset): Int = when (preset) {
    EqualizerPreset.FLAT -> R.string.audio_effects_preset_flat
    EqualizerPreset.BASS_BOOST -> R.string.audio_effects_preset_bass
    EqualizerPreset.TREBLE_BOOST -> R.string.audio_effects_preset_treble
    EqualizerPreset.VOCAL -> R.string.audio_effects_preset_vocal
    EqualizerPreset.ROCK -> R.string.audio_effects_preset_rock
    EqualizerPreset.POP -> R.string.audio_effects_preset_pop
    EqualizerPreset.JAZZ -> R.string.audio_effects_preset_jazz
}

private const val BANDS_PER_GROUP = 6
private const val GROUP_COUNT = (EqualizerBands.COUNT + BANDS_PER_GROUP - 1) / BANDS_PER_GROUP
