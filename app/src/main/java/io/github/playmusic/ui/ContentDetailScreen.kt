package io.github.playmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import java.util.Locale

@Composable
internal fun ContentDetailScreen(
    selected: SpotifyContent,
    detail: ContentDetail?,
    isLoading: Boolean,
    onPlay: (SpotifyContent) -> Unit,
    onOpen: (SpotifyContent) -> Unit,
    onRetry: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onEditPlaylist: () -> Unit = {},
    onDeletePlaylist: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val content = detail?.content ?: selected
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("content-detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                content.imageUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(156.dp).clip(RoundedCornerShape(10.dp)).testTag("detail-artwork"))
                    Spacer(Modifier.height(16.dp))
                }
                Text(content.title.ifBlank { stringResource(R.string.untitled_playlist) },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("detail-title"))
                if (content.subtitle.isNotBlank()) Text(content.subtitle, modifier = Modifier.testTag("detail-artists"))
                detail?.playlistMetadata?.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail-description"))
                }
                detail?.releaseDate?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (detail != null && content.kind != ContentKind.TRACK) {
                    Text(pluralStringResource(R.plurals.track_count, detail.totalTracks, detail.totalTracks),
                        modifier = Modifier.testTag("detail-track-count"))
                }
                if (content.durationMs > 0) Text(durationLabel(content.durationMs), modifier = Modifier.testTag("detail-duration"))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlay(content) },
                    enabled = detail != null && content.isPlayable != false &&
                        (content.kind == ContentKind.TRACK || detail.tracks.any { it.isPlayable != false }),
                    modifier = Modifier.testTag("detail-play-button")) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.play))
                }
                if (content.isPlayable == false) Text(stringResource(R.string.track_unavailable))
                if (detail?.playlistMetadata?.canEdit == true) {
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onEditPlaylist() },
                        modifier = Modifier.testTag("edit-playlist-button")) {
                        Text(stringResource(R.string.edit_playlist))
                    }
                }
                if (detail?.playlistMetadata?.canDelete == true) {
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDeletePlaylist() },
                        modifier = Modifier.testTag("delete-playlist-button")) {
                        Text(stringResource(R.string.delete_playlist), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (content.albumUri != null && content.albumTitle != null) {
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpen(SpotifyContent(content.albumUri.substringAfterLast(':'), content.albumUri,
                        content.albumTitle, content.subtitle, content.imageUrl, ContentKind.ALBUM)) },
                        modifier = Modifier.testTag("detail-album-button")) {
                        Text(stringResource(R.string.open_album, content.albumTitle))
                    }
                }
                if (detail == null && !isLoading) {
                    Text(stringResource(R.string.detail_not_loaded))
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("detail-retry-button")) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
        }
        itemsIndexed(detail?.tracks.orEmpty(), key = { index, item -> "$index:${item.uri}" }) { index, track ->
            Card(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlayTrack(index) },
                enabled = track.isPlayable != false,
                modifier = Modifier.fillMaxWidth().testTag("detail-track-$index")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.Medium)
                        Text(track.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    if (track.durationMs > 0) Text(durationLabel(track.durationMs), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun durationLabel(milliseconds: Long): String =
    String.format(Locale.getDefault(), "%d:%02d", milliseconds / 60_000, milliseconds / 1_000 % 60)
