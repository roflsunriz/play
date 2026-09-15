package io.github.playmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.playmusic.R
import io.github.playmusic.data.api.LyricsSource
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.TrackLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface LyricsState {
    data object Loading : LyricsState
    data object Unavailable : LyricsState
    data object Failed : LyricsState
    data class Loaded(val lyrics: TrackLyrics) : LyricsState
}

/** No persistent cache: leaving the player/account also releases the response text. */
@Composable
internal fun LyricsRoute(
    content: SpotifyContent,
    playback: Playback,
    client: LyricsSource,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(content.uri, client) {
        var attempt by remember { mutableIntStateOf(0) }
        var state by remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
        LaunchedEffect(content.uri, client, attempt) {
            state = LyricsState.Loading
            state = try {
                val lyrics = client.lyrics(content.uri)
                require(lyrics == null || lyrics.trackUri == content.uri)
                lyrics?.let { LyricsState.Loaded(it) } ?: LyricsState.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LyricsState.Failed
            }
        }
        LyricsPanel(content.uri, state, playback.item?.uri, playback.progressMs, onSeek,
            onRetry = { attempt++ }, modifier = modifier)
    }
}

@Composable
internal fun LyricsPanel(
    trackUri: String,
    state: LyricsState,
    activeTrackUri: String?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("lyrics-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lyrics_title), style = MaterialTheme.typography.titleLarge)
        when (state) {
            LyricsState.Loading -> CircularProgressIndicator(Modifier.testTag("lyrics-loading"))
            LyricsState.Unavailable -> Text(stringResource(R.string.lyrics_unavailable), Modifier.testTag("lyrics-unavailable"))
            LyricsState.Failed -> {
                Text(stringResource(R.string.lyrics_failed), Modifier.testTag("lyrics-failed"))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("lyrics-retry")) {
                    Text(stringResource(R.string.lyrics_retry))
                }
            }
            is LyricsState.Loaded -> if (state.lyrics.trackUri == trackUri) {
                key(trackUri) {
                    LyricsLines(state.lyrics, activeTrackUri, positionMs, onSeek)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LyricsLines(lyrics: TrackLyrics, activeTrackUri: String?, positionMs: Long, onSeek: (Long) -> Unit) {
    val synced = lyrics.isTimeSynced
    val isCurrent = lyrics.trackUri == activeTrackUri
    val activeLine = if (isCurrent) lyrics.activeLine(positionMs) else -1
    var follow by rememberSaveable { mutableStateOf(true) }
    var ownAutoScroll by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val haptics = LocalHapticFeedback.current
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) follow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && !ownAutoScroll }.collect { manual ->
            if (manual) follow = false
        }
    }
    LaunchedEffect(activeLine, follow, isCurrent) {
        if (follow && isCurrent && activeLine >= 0) {
            ownAutoScroll = true
            try {
                list.animateScrollToItem(activeLine, -list.layoutInfo.viewportSize.height / 3)
            } finally {
                ownAutoScroll = false
            }
        }
    }
    if (lyrics.isCapped) Text(stringResource(R.string.lyrics_limited), Modifier.testTag("lyrics-limited"))
    if (lyrics.lines.isEmpty() || lyrics.lines.all { it.words.isBlank() }) {
        Text(stringResource(R.string.lyrics_empty), Modifier.testTag("lyrics-empty"))
    } else {
        if (!synced) Text(stringResource(R.string.lyrics_unsynced), Modifier.testTag("lyrics-unsynced"))
        if (synced && !follow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { follow = true }, modifier = Modifier.testTag("lyrics-sync")) {
                    Text(stringResource(R.string.lyrics_sync))
                }
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp)
                .nestedScroll(scrollConnection).semantics {
                    // LazyLayoutSemantics uses an immediate scrollToItem for this action, so
                    // isScrollInProgress can toggle twice before a snapshot observer runs.
                    scrollToIndex { index ->
                        if (index !in lyrics.lines.indices) false else {
                            follow = false
                            scrollScope.launch { list.scrollToItem(index) }
                            true
                        }
                    }
                }.testTag("lyrics-lines"),
        ) {
            itemsIndexed(lyrics.lines, key = { index, _ -> index }) { index, line ->
                Text(
                    text = line.words,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == activeLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("lyrics-line-$index").semantics { selected = index == activeLine }
                        .clickable(enabled = synced && line.startTimeMs != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSeek(checkNotNull(line.startTimeMs))
                            follow = true
                        }.padding(vertical = 12.dp, horizontal = 8.dp),
                )
            }
        }
    }
    if (lyrics.providerDisplayName.isNotBlank()) {
        Text(stringResource(R.string.lyrics_provider, lyrics.providerDisplayName),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("lyrics-provider"))
    }
}
