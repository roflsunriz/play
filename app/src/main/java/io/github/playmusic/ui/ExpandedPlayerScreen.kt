package io.github.playmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent
import java.util.Locale

@Composable
internal fun ExpandedPlayerScreen(
    playback: Playback,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onContentActions: (SpotifyContent) -> Unit = {},
    savedUris: Set<String> = emptySet(),
    onStop: () -> Unit = {},
    lyricsContent: @Composable (SpotifyContent) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val haptics = LocalHapticFeedback.current
    val itemUri = playback.item?.uri
    val currentItemUri by rememberUpdatedState(itemUri)
    val seekCurrentItem: (Long) -> Unit = { position -> if (currentItemUri == itemUri) onSeek(position) }
    val window = LocalWindowInfo.current.containerSize
    val horizontal = with(LocalDensity.current) { window.width.toDp() >= 480.dp && window.width > window.height }
    Surface(modifier.fillMaxSize().testTag("expanded-player-screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onBack() },
                    modifier = Modifier.testTag("expanded-player-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_close))
                }
                Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("expanded-player-heading"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                playback.item?.let { ContentAddButton(it, it.uri in savedUris, onContentActions) }
                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onStop() },
                    modifier = Modifier.testTag("expanded-player-stop")) {
                    Icon(Icons.Default.Stop, stringResource(R.string.playback_stop))
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (horizontal) {
                    val availableWidth = (maxWidth - 52.dp).coerceAtLeast(0.dp)
                    val controlsWidth = (availableWidth * .55f).coerceAtLeast(264.dp).coerceAtMost(availableWidth)
                    val artWidth = (availableWidth - controlsWidth).coerceAtLeast(0.dp)
                    val artSize = minOf(artWidth, (maxHeight - 24.dp).coerceAtLeast(0.dp))
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("expanded-player-landscape"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(artWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            PlayerArtwork(playback.item, Modifier.size(artSize))
                        }
                        Spacer(Modifier.width(20.dp))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PlayerMetadata(playback, compact = true)
                                key(itemUri) { PlayerTimeline(playback, seekCurrentItem) }
                                PlayerControls(playback, 64.dp, onPlayPause, onNext, onPrevious, onShuffle, onRepeat)
                                playback.item?.let { lyricsContent(it) }
                            }
                        }
                    }
                } else {
                    val artSize = minOf((maxWidth - 48.dp).coerceAtLeast(0.dp), 520.dp,
                        (maxHeight - 320.dp).coerceAtLeast(96.dp))
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp).testTag("expanded-player-portrait"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        PlayerArtwork(playback.item, Modifier.size(artSize))
                        PlayerMetadata(playback, compact = false)
                        key(itemUri) { PlayerTimeline(playback, seekCurrentItem) }
                        PlayerControls(playback, 72.dp, onPlayPause, onNext, onPrevious, onShuffle, onRepeat)
                        playback.item?.let { lyricsContent(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerArtwork(item: SpotifyContent?, modifier: Modifier) {
    val label = stringResource(R.string.player_artwork)
    val imageUrl = item?.imageUrl
    Surface(modifier.testTag("expanded-artwork").semantics { contentDescription = label },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp) {
        if (imageUrl.isNullOrBlank()) {
            ArtworkPlaceholder()
        } else {
            SubcomposeAsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), loading = { ArtworkPlaceholder() }, error = { ArtworkPlaceholder() })
        }
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerMetadata(playback: Playback, compact: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(playback.item?.title?.ifBlank { stringResource(R.string.player_untitled_track) }
            ?: stringResource(R.string.player_no_track),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("expanded-title"))
        playback.item?.subtitle?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("expanded-artist"))
        }
        playback.deviceName?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("expanded-device"))
        }
        if (playback.isBuffering) {
            Row(Modifier.testTag("expanded-buffering"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.player_buffering), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PlayerTimeline(playback: Playback, onSeek: (Long) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val locale = LocalConfiguration.current.locales[0]
    val duration = playback.durationMs.coerceAtLeast(0)
    val available = playback.item != null && duration > 0
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    val reportedPosition = (scrubPosition?.toLong() ?: playback.progressMs).coerceAtLeast(0)
    val displayedPosition = if (duration > 0) reportedPosition.coerceAtMost(duration) else reportedPosition
    val elapsed = playbackTime(displayedPosition, locale)
    val total = if (duration > 0) playbackTime(duration, locale) else stringResource(R.string.player_unknown_time)
    val positionDescription = stringResource(R.string.player_position_description, elapsed, total)
    val seekLabel = stringResource(R.string.player_seek)
    // Time and transport keep their direction while surrounding metadata follows the locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            Slider(value = (scrubPosition ?: playback.progressMs.toFloat()).coerceIn(0f, duration.toFloat()),
                onValueChange = { scrubPosition = it },
                onValueChangeFinished = {
                    scrubPosition?.let {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSeek(it.toLong().coerceIn(0, duration))
                    }
                    scrubPosition = null
                }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = available,
                modifier = Modifier.fillMaxWidth().testTag("expanded-seek-slider").semantics {
                    contentDescription = seekLabel
                    stateDescription = positionDescription
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val elapsedLabel = stringResource(R.string.player_elapsed, elapsed)
                val durationLabel = stringResource(R.string.player_duration, total)
                Text(elapsed, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("expanded-elapsed").semantics { contentDescription = elapsedLabel })
                Text(total, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("expanded-duration").semantics { contentDescription = durationLabel })
            }
        }
    }
}

@Composable
private fun PlayerControls(
    playback: Playback,
    playButtonSize: Dp,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val available = playback.item != null
    val action: (() -> Unit) -> Unit = { callback -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); callback() }
    val shuffleDescription = stringResource(if (playback.shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off)
    val repeatDescription = stringResource(when (playback.repeatMode) {
        RepeatMode.OFF -> R.string.repeat_off
        RepeatMode.CONTEXT -> R.string.repeat_context
        RepeatMode.TRACK -> R.string.repeat_track
    })
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().testTag("expanded-controls"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { action(onShuffle) }, enabled = available,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (playback.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(48.dp).testTag("expanded-shuffle").semantics { stateDescription = shuffleDescription }) {
                Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle))
            }
            IconButton(onClick = { action(onPrevious) }, enabled = available,
                modifier = Modifier.size(48.dp).testTag("expanded-previous")) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(30.dp))
            }
            FilledIconButton(onClick = { action(onPlayPause) }, enabled = available, shape = CircleShape,
                modifier = Modifier.size(playButtonSize).testTag("expanded-play-pause")) {
                Icon(if (playback.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playback.playWhenReady) R.string.pause else R.string.play), Modifier.size(36.dp))
            }
            IconButton(onClick = { action(onNext) }, enabled = available,
                modifier = Modifier.size(48.dp).testTag("expanded-next")) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(30.dp))
            }
            IconButton(onClick = { action(onRepeat) }, enabled = available,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (playback.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(48.dp).testTag("expanded-repeat").semantics { stateDescription = repeatDescription }) {
                Icon(if (playback.repeatMode == RepeatMode.TRACK) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    stringResource(R.string.player_repeat))
            }
        }
    }
}

private fun playbackTime(milliseconds: Long, locale: Locale): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(locale, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(locale, "%d:%02d", seconds / 60, seconds % 60)
}
