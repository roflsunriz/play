package io.github.playmusic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent

data class PlaylistChoice(val playlist: MusicContent, val containsAll: Boolean)

data class ContentActionsState(
    val content: MusicContent,
    val saved: Boolean? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val choosingPlaylist: Boolean = false,
    val playlists: List<PlaylistChoice> = emptyList(),
    val failure: Boolean = false,
)

@Composable
internal fun ContentAddButton(content: MusicContent, saved: Boolean, onActions: (MusicContent) -> Unit) {
    if (content.kind !in setOf(ContentKind.TRACK, ContentKind.ALBUM)) return
    IconButton(onClick = { onActions(content) }, modifier = Modifier.testTag("add-${content.kind.name.lowercase()}-${content.id}")) {
        Icon(if (saved) Icons.Default.Check else Icons.Default.AddCircleOutline,
            stringResource(if (saved) R.string.remove_favorite else R.string.add_favorite))
    }
}

@Composable
internal fun ContentActionsDialog(state: ContentActionsState, onFavorite: () -> Unit, onPlaylists: () -> Unit,
    onPlaylist: (PlaylistChoice) -> Unit, onRadio: () -> Unit, onArtists: () -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable(state.content.uri) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(if (state.choosingPlaylist) stringResource(R.string.add_to_playlist) else state.content.title) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("content-actions-dialog")) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("content-actions-loading"))
                if (state.failure) {
                    Text(stringResource(R.string.request_failed), Modifier.testTag("content-actions-error"))
                    TextButton(onClick = onRetry, enabled = !state.busy, modifier = Modifier.testTag("content-actions-retry")) {
                        Text(stringResource(R.string.refresh))
                    }
                }
                if (state.choosingPlaylist) {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth().testTag("playlist-picker-search"))
                    val choices = state.playlists.filter { it.playlist.title.contains(query, ignoreCase = true) }
                    if (!state.loading && choices.isEmpty()) Text(stringResource(R.string.no_filter_results),
                        Modifier.padding(top = 12.dp).testTag("playlist-picker-empty"))
                    LazyColumn(Modifier.heightIn(max = 340.dp).testTag("playlist-picker-list")) {
                        items(choices, key = { it.playlist.uri }) { choice ->
                            TextButton(onClick = { onPlaylist(choice) }, enabled = !state.busy && !state.loading,
                                modifier = Modifier.fillMaxWidth().testTag("playlist-choice-${choice.playlist.id}")) {
                                Text(choice.playlist.title.ifBlank { stringResource(R.string.untitled_playlist) }, Modifier.weight(1f))
                                if (choice.containsAll) Icon(Icons.Default.Check, stringResource(R.string.already_in_playlist),
                                    Modifier.testTag("playlist-member-${choice.playlist.id}"))
                            }
                        }
                    }
                } else Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = onFavorite, enabled = state.saved != null && !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("favorite-toggle")) {
                        Icon(if (state.saved == true) Icons.Default.Check else Icons.Default.AddCircleOutline, null,
                            Modifier.testTag(if (state.saved == true) "favorite-icon-saved" else "favorite-icon-unsaved"))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.saved == true) R.string.remove_favorite else R.string.add_favorite))
                    }
                    TextButton(onClick = onPlaylists, enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("choose-playlists")) { Text(stringResource(R.string.add_to_playlist)) }
                    if (state.content.kind == ContentKind.TRACK) TextButton(onClick = onRadio, enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("open-song-radio")) { Text(stringResource(R.string.go_to_song_radio)) }
                    TextButton(onClick = onArtists, enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("open-artists")) { Text(stringResource(R.string.go_to_artist)) }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy,
            modifier = Modifier.testTag("content-actions-close")) { Text(stringResource(R.string.close)) } })
}

@Composable
internal fun ArtistPicker(artists: List<MusicContent>, onOpen: (MusicContent) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.go_to_artist)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            artists.forEach { artist -> TextButton(onClick = { onOpen(artist) },
                modifier = Modifier.fillMaxWidth().testTag("artist-choice-${artist.id}")) { Text(artist.title) } }
        } }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("artist-picker-close")) {
            Text(stringResource(R.string.close)) } })
}
