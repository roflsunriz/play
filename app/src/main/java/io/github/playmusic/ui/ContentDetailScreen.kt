package io.github.playmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.playmusic.data.model.DetailSort
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
    onContentActions: (SpotifyContent) -> Unit = {},
    savedUris: Set<String> = emptySet(),
    sort: DetailSort = DetailSort.TRACK_ORDER,
    sortOptions: List<DetailSort> = listOf(DetailSort.TRACK_ORDER),
    onSortChanged: (DetailSort) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val content = detail?.content ?: selected
    val playlistMetadata = detail?.playlistMetadata?.takeIf { content.kind == ContentKind.PLAYLIST }
    val owner = content.ownerName?.takeIf(String::isNotBlank)
    val description = (playlistMetadata?.description ?: content.description)?.takeIf(String::isNotBlank)
    val trackCount = detail?.totalTracks ?: content.trackCount
    val releaseDate = (detail?.releaseDate ?: content.releaseDate)?.takeIf(String::isNotBlank)
    val playable = detail != null && content.isPlayable != false &&
        (content.kind == ContentKind.TRACK || detail.tracks.any { it.isPlayable != false })
    val play = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPlay(content) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("content-detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content.imageUrl?.let { url ->
            item(key = "artwork") {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(156.dp).clip(RoundedCornerShape(10.dp)).testTag("detail-artwork"))
            }
        }
        item(key = "title") {
            Column(Modifier.fillMaxWidth()) {
                Text(content.title.ifBlank { stringResource(R.string.untitled_playlist) },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("detail-title"))
                if (content.kind == ContentKind.PLAYLIST) {
                    owner?.let {
                        Text(stringResource(R.string.detail_creator, it), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("detail-creator"))
                    }
                } else if (content.subtitle.isNotBlank()) {
                    Text(content.subtitle, modifier = Modifier.testTag("detail-artists"))
                }
            }
        }
        description?.let {
            item(key = "description") {
                Text(remember(it) { readableDescription(it) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail-description"))
            }
        }
        releaseDate?.let {
            item(key = "release-date") { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail-release-date")) }
        }
        if (trackCount != null && content.kind in setOf(ContentKind.ALBUM, ContentKind.PLAYLIST, ContentKind.ARTIST)) {
            item(key = "track-count") {
                Text(pluralStringResource(R.plurals.track_count, trackCount, trackCount), modifier = Modifier.testTag("detail-track-count"))
            }
        }
        if (content.durationMs > 0) {
            item(key = "duration") { Text(durationLabel(content.durationMs), modifier = Modifier.testTag("detail-duration")) }
        }
        item(key = "actions") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 240.dp && (playlistMetadata?.canEdit == true || playlistMetadata?.canDelete == true)
                Row(Modifier.fillMaxWidth().testTag("detail-actions"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (maxWidth < 176.dp) 0.dp else 8.dp)) {
                    if (compact) {
                        FilledIconButton(onClick = play, enabled = playable,
                            modifier = Modifier.size(48.dp).testTag("detail-play-button")) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play))
                        }
                    } else {
                        Button(onClick = play, enabled = playable,
                            modifier = Modifier.weight(1f, fill = false).testTag("detail-play-button")) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.play), modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                    ContentAddButton(content, content.uri in savedUris, onContentActions)
                    if (playlistMetadata?.canEdit == true) {
                        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onEditPlaylist() },
                            modifier = Modifier.size(48.dp).testTag("edit-playlist-button")) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_playlist))
                        }
                    }
                    if (playlistMetadata?.canDelete == true) {
                        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDeletePlaylist() },
                            modifier = Modifier.size(48.dp).testTag("delete-playlist-button")) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.delete_playlist),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (content.isPlayable == false) {
            item(key = "unavailable") { Text(stringResource(R.string.track_unavailable)) }
        }
        if (content.albumUri != null && content.albumTitle != null) {
            item(key = "album") {
                TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpen(SpotifyContent(content.albumUri.substringAfterLast(':'), content.albumUri,
                    content.albumTitle, content.subtitle, content.imageUrl, ContentKind.ALBUM)) },
                    modifier = Modifier.testTag("detail-album-button")) {
                    Text(stringResource(R.string.open_album, content.albumTitle))
                }
            }
        }
        if (detail == null && !isLoading) {
            item(key = "retry") {
                Column {
                    Text(stringResource(R.string.detail_not_loaded))
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("detail-retry-button")) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
        }
        if (sortOptions.size > 1) {
            item(key = "sort") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DetailSortMenu(sort, sortOptions, onSortChanged)
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
                        if (track.subtitle.isNotBlank()) Text(track.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    if (track.durationMs > 0) Text(durationLabel(track.durationMs), style = MaterialTheme.typography.labelMedium)
                    ContentAddButton(track, track.uri in savedUris, onContentActions)
                }
            }
        }
        items(detail?.relatedContent.orEmpty(), key = { "related:${it.uri}" }) { related ->
            Card(onClick = { onOpen(related) }, modifier = Modifier.fillMaxWidth().testTag("related-${related.id}")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    related.imageUrl?.let { AsyncImage(it, null, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(related.title, fontWeight = FontWeight.Medium)
                        if (related.subtitle.isNotBlank()) Text(related.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    ContentAddButton(related, related.uri in savedUris, onContentActions)
                }
            }
        }
    }
}

private fun durationLabel(milliseconds: Long): String =
    String.format(Locale.getDefault(), "%d:%02d", milliseconds / 60_000, milliseconds / 1_000 % 60)

@Composable
internal fun DetailSortMenu(sort: DetailSort, options: List<DetailSort>, onSelected: (DetailSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("detail-sort-button")) {
            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort_by))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.label())) },
                    leadingIcon = { if (option == sort) Icon(Icons.Default.Check, stringResource(R.string.sort_selected)) },
                    onClick = { expanded = false; onSelected(option) },
                    modifier = Modifier.testTag("detail-sort-${option.name.lowercase()}"))
            }
        }
    }
}

private fun DetailSort.label(): Int = when (this) {
    DetailSort.TRACK_ORDER -> R.string.sort_album_order
    DetailSort.TITLE -> R.string.sort_title
    DetailSort.ARTIST -> R.string.sort_artist
    DetailSort.ALBUM -> R.string.sort_album
    DetailSort.ADDED_NEWEST -> R.string.sort_date_added
    DetailSort.PLAYCOUNT -> R.string.sort_plays
}
