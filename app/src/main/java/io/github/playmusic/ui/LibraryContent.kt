package io.github.playmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun LibraryContent(
    section: LibrarySection,
    items: List<SpotifyContent>,
    query: String,
    sort: LibrarySort,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (LibrarySort) -> Unit,
    onPlay: (SpotifyContent) -> Unit,
    onOpen: (SpotifyContent) -> Unit,
    onViewportChanged: (List<SpotifyContent>) -> Unit,
    showControls: Boolean = true,
) {
    Column(Modifier.fillMaxSize()) {
        if (showControls) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryFilterInput(query, onQueryChanged, Modifier.weight(1f), section)
                LibrarySortMenu(checkNotNull(section.kind), sort, onSortChanged)
            }
        }
        ContentList(items, onPlay, onOpen, onViewportChanged, emptyText = if (query.isNotBlank())
            stringResource(R.string.no_filter_results) else stringResource(R.string.empty_library),
            presentationKey = "$query\u0000${sort.name}")
    }
}

@Composable
internal fun LibraryFilterInput(query: String, onQueryChanged: (String) -> Unit, modifier: Modifier = Modifier,
    section: LibrarySection = LibrarySection.PLAYLISTS) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = query, onValueChange = onQueryChanged, singleLine = true,
        placeholder = { Text(stringResource(when (section) {
            LibrarySection.ALBUMS -> R.string.album_filter_hint
            LibrarySection.TRACKS -> R.string.track_filter_hint
            else -> R.string.library_filter_hint
        }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQueryChanged("") }, modifier = Modifier.testTag("clear-library-filter")) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_filter))
            }
        }) else null,
        modifier = modifier.testTag(when (section) {
            LibrarySection.ALBUMS -> "album-filter-input"
            LibrarySection.TRACKS -> "track-filter-input"
            else -> "playlist-filter-input"
        }))
}

@Composable
internal fun CatalogSearchInput(query: String, onQueryChanged: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = query, onValueChange = onQueryChanged,
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { keyboard?.hide(); onSearch() } }),
        modifier = modifier.testTag("search-input"))
}

@Composable
internal fun LibrarySortMenu(kind: ContentKind, sort: LibrarySort, onSelected: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("library-sort-button")) {
            Icon(Icons.Default.Sort, stringResource(R.string.sort_by))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.options(kind).forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.label(kind))) },
                    leadingIcon = { if (option == sort) Icon(Icons.Default.Check, stringResource(R.string.sort_selected)) },
                    onClick = { expanded = false; onSelected(option) },
                    modifier = Modifier.testTag("sort-${option.name.lowercase()}"))
            }
        }
    }
}

private fun LibrarySort.label(kind: ContentKind): Int = when (this) {
    LibrarySort.LIBRARY_ORDER -> R.string.sort_library_order
    LibrarySort.TITLE -> R.string.sort_title
    LibrarySort.TITLE_DESCENDING -> R.string.sort_title_descending
    LibrarySort.CREATOR -> if (kind == ContentKind.PLAYLIST) R.string.sort_creator else R.string.sort_artist
    LibrarySort.TRACK_COUNT -> R.string.sort_track_count
    LibrarySort.DURATION -> R.string.sort_duration
    LibrarySort.DURATION_DESCENDING -> R.string.sort_duration_descending
}

@Composable
internal fun SearchContent(
    query: String,
    items: List<SpotifyContent>,
    suggestions: List<SpotifyContent>,
    matchingSuggestions: List<SpotifyContent>,
    previewFailed: Boolean,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (SpotifyContent) -> Unit,
    onOpen: (SpotifyContent) -> Unit,
    onViewportChanged: (List<SpotifyContent>) -> Unit,
    showInput: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSearch() }
    Column(Modifier.fillMaxSize()) {
        if (showInput) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogSearchInput(query, onQueryChanged, submit, Modifier.weight(1f))
            FilledIconButton(onClick = submit, enabled = query.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).testTag("search-button")) {
                Icon(Icons.Default.Search, stringResource(R.string.search_action))
            }
        }
        val suggesting = query.isBlank()
        if (previewFailed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.request_failed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = submit, modifier = Modifier.testTag("search-retry-button")) { Text(stringResource(R.string.refresh)) }
        }
        if (suggesting) Text(stringResource(R.string.suggested_content),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("suggested-heading"))
        ContentList(if (suggesting) suggestions else items, onPlay, onOpen, onViewportChanged,
            suggestions = if (suggesting) emptyList() else matchingSuggestions,
            resultHeading = if (suggesting) null else stringResource(R.string.search_results),
            suggestionHeading = stringResource(R.string.matching_suggestions), presentationKey = query)
    }
}

@Composable
internal fun ContentList(
    items: List<SpotifyContent>,
    onPlay: (SpotifyContent) -> Unit,
    onOpen: (SpotifyContent) -> Unit,
    onViewportChanged: (List<SpotifyContent>) -> Unit,
    emptyText: String = stringResource(R.string.empty_library),
    suggestions: List<SpotifyContent> = emptyList(),
    resultHeading: String? = null,
    suggestionHeading: String? = null,
    presentationKey: String = "",
) {
    val listState = rememberLazyListState()
    var previousPresentation by rememberSaveable { mutableStateOf(presentationKey) }
    LaunchedEffect(presentationKey) {
        if (previousPresentation != presentationKey) {
            previousPresentation = presentationKey
            listState.scrollToItem(0)
        }
    }
    val reportViewport by rememberUpdatedState(onViewportChanged)
    val allItems = remember(items, suggestions) { (suggestions + items).distinctBy { it.uri } }
    val suggestionUris = remember(suggestions) { suggestions.map { it.uri }.toSet() }
    val resultItems = remember(items, suggestionUris) { items.filterNot { it.uri in suggestionUris } }
    LaunchedEffect(listState, allItems) {
        val indicesByUri = allItems.mapIndexed { index, item -> item.uri to index }.toMap()
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { indicesByUri[it.key] } }
            .distinctUntilChanged().collectLatest { indices ->
                // A fast fling should not start a request for every row it passes.
                delay(160)
                reportViewport(viewportPrefetchItems(allItems, indices))
            }
    }
    if (allItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyText) }
    } else LazyColumn(state = listState, modifier = Modifier.testTag("content-list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestions.isNotEmpty()) {
            if (suggestionHeading != null) item(key = "suggestion-heading") {
                Text(suggestionHeading, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(4.dp).testTag("matching-suggestions-heading"))
            }
            items(suggestions, key = { it.uri }) { item -> ContentCard(item, onPlay, onOpen) }
        }
        if (resultItems.isNotEmpty() && resultHeading != null) item(key = "results-heading") {
            Text(resultHeading, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(4.dp).testTag("search-results-heading"))
        }
        items(resultItems, key = { it.uri }) { item -> ContentCard(item, onPlay, onOpen) }
    }
}

@Composable
private fun ContentCard(item: SpotifyContent, onPlay: (SpotifyContent) -> Unit, onOpen: (SpotifyContent) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Card(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onOpen(item) },
        modifier = Modifier.fillMaxWidth().testTag("content-${item.kind.name.lowercase()}-${item.id}")) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.imageUrl != null) AsyncImage(model = item.imageUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(6.dp)))
            else Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.LibraryMusic, null) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title.ifBlank { stringResource(R.string.untitled_playlist) }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("title-${item.kind.name.lowercase()}-${item.id}"))
                if (item.kind == ContentKind.PLAYLIST) {
                    val metadata = listOfNotNull(item.ownerName?.takeIf(String::isNotBlank),
                        item.trackCount?.let { pluralStringResource(R.plurals.track_count, it, it) }).joinToString(" · ")
                    if (metadata.isNotBlank()) Text(metadata, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    item.description?.takeIf(String::isNotBlank)?.let {
                        Text(remember(it) { readableDescription(it) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    val extra = when (item.kind) {
                        ContentKind.ALBUM -> listOfNotNull(item.releaseDate, item.trackCount?.let {
                            pluralStringResource(R.plurals.track_count, it, it)
                        })
                        ContentKind.TRACK -> listOfNotNull(item.albumTitle, item.durationMs.takeIf { it > 0 }?.let {
                            "%d:%02d".format(it / 60_000, it / 1_000 % 60)
                        })
                        else -> emptyList()
                    }.joinToString(" · ")
                    if (extra.isNotBlank()) Text(extra, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onPlay(item) },
                enabled = item.isPlayable != false, modifier = Modifier.testTag("play-${item.kind.name.lowercase()}-${item.id}")) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
            }
        }
    }
}

internal fun readableDescription(value: String): String =
    androidx.core.text.HtmlCompat.fromHtml(value, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
