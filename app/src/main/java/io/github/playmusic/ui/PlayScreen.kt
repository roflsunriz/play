package io.github.playmusic.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.delay

@Composable
fun PlayRoute(viewModel: PlayViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.isLoggedIn) {
        HomeScreen(
            state = state,
            onSectionSelected = viewModel::selectSection,
            onSearchChanged = viewModel::updateSearchQuery,
            onSearch = viewModel::search,
            onRefresh = viewModel::refreshAll,
            onLogout = viewModel::logout,
            onPlay = viewModel::play,
            onPlayPause = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onSeek = viewModel::seek,
            onShuffle = viewModel::toggleShuffle,
            onRepeat = viewModel::cycleRepeat,
            onPreview = viewModel::preview,
        )
    } else {
        SetupScreen(
            initialUsername = state.username,
            isLoading = state.isLoading,
            onLogin = viewModel::beginLogin,
            onRunDiagnostics = viewModel::runDiagnostics,
            diagnosticsReport = state.diagnosticsReport,
        )
    }
    state.error?.let { ErrorDialog(it, viewModel::clearError) }
}

@Composable
private fun SetupScreen(
    initialUsername: String,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onRunDiagnostics: () -> Unit,
    diagnosticsReport: String?,
) {
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_description), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("username-input"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("password-input"),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onLogin(username, password) },
            enabled = username.isNotBlank() && password.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("login-button"),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.login))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.premium_required), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.artwork_cache_note), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onRunDiagnostics,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("diagnostics-button"),
        ) {
            Text("Run diagnostics")
        }
        diagnosticsReport?.let { report ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("diagnostics-report"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: PlayUiState,
    onSectionSelected: (LibrarySection) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onPlay: (SpotifyContent) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPreview: (SpotifyContent) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh-button")) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.settings))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logout)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onLogout()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (state.playback.item != null) {
                    PlaybackBar(
                        playback = state.playback,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onSeek = onSeek,
                        onShuffle = onShuffle,
                        onRepeat = onRepeat,
                    )
                }
                AppNavigation(state.selectedSection, onSectionSelected)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.selectedSection == LibrarySection.SEARCH) {
                SearchContent(
                    query = state.searchQuery,
                    items = state.items,
                    onQueryChanged = onSearchChanged,
                    onSearch = onSearch,
                    onPlay = onPlay,
                    onPreview = onPreview,
                )
            } else {
                ContentList(state.items, onPlay, onPreview)
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("loading-indicator"))
            }
        }
    }
}

@Composable
private fun AppNavigation(selected: LibrarySection, onSelected: (LibrarySection) -> Unit) {
    NavigationBar {
        val entries = listOf(
            Triple(LibrarySection.PLAYLISTS, R.string.playlists, Icons.AutoMirrored.Filled.QueueMusic),
            Triple(LibrarySection.ALBUMS, R.string.albums, Icons.Default.Album),
            Triple(LibrarySection.TRACKS, R.string.tracks, Icons.Default.MusicNote),
            Triple(LibrarySection.SEARCH, R.string.search, Icons.Default.Search),
        )
        entries.forEach { (section, label, icon) ->
            NavigationBarItem(
                selected = selected == section,
                onClick = { onSelected(section) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("section-${section.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    items: List<SpotifyContent>,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (SpotifyContent) -> Unit,
    onPreview: (SpotifyContent) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("search-input"),
            )
            FilledIconButton(
                onClick = onSearch,
                enabled = query.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).testTag("search-button"),
            ) {
                Icon(Icons.Default.Search, stringResource(R.string.search_action))
            }
        }
        ContentList(items, onPlay, onPreview)
    }
}

@Composable
private fun ContentList(
    items: List<SpotifyContent>,
    onPlay: (SpotifyContent) -> Unit,
    onPreview: (SpotifyContent) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_library))
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { "${it.kind}-${it.id}" }) { item ->
            ContentCard(item, onPlay, onPreview)
        }
    }
}

@Composable
private fun ContentCard(
    item: SpotifyContent,
    onPlay: (SpotifyContent) -> Unit,
    onPreview: (SpotifyContent) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Card(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onPlay(item)
        },
        modifier = Modifier.fillMaxWidth().testTag("content-${item.kind.name.lowercase()}-${item.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.imageUrl != null) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.supplied_by_spotify), style = MaterialTheme.typography.labelSmall)
            }
            if (item.kind == ContentKind.TRACK && item.previewUrl != null) {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPreview(item)
                    },
                    modifier = Modifier.testTag("preview-${item.id}"),
                ) {
                    Icon(Icons.Default.MusicNote, stringResource(R.string.preview))
                }
            }
            IconButton(
                onClick = {
                    val type = item.kind.name.lowercase()
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://open.spotify.com/$type/${item.id}".toUri()))
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_on_spotify))
            }
        }
    }
}

@Composable
private fun PlaybackBar(
    playback: Playback,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var progress by remember(playback.progressMs) { mutableFloatStateOf(playback.progressMs.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(playback.isPlaying, playback.progressMs, playback.durationMs, isDragging) {
        if (!playback.isPlaying || isDragging) return@LaunchedEffect
        while (progress < playback.durationMs) {
            delay(1_000)
            progress = (progress + 1_000).coerceAtMost(playback.durationMs.toFloat())
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(playback.item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(playback.item?.subtitle, playback.deviceName).filter(String::isNotBlank).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPrevious()
            }, modifier = Modifier.testTag("previous-button")) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous))
            }
            FilledIconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayPause()
            }, modifier = Modifier.testTag("play-pause-button")) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playback.isPlaying) R.string.pause else R.string.play),
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onNext()
            }, modifier = Modifier.testTag("next-button")) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next))
            }
        }
        Slider(
            value = progress.coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = {
                isDragging = true
                progress = it
            },
            onValueChangeFinished = {
                isDragging = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSeek(progress.toLong())
            },
            valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
            enabled = playback.durationMs > 0,
            modifier = Modifier.fillMaxWidth().testTag("seek-slider"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onShuffle()
            }, modifier = Modifier.testTag("shuffle-button")) {
                Icon(
                    Icons.Default.Shuffle,
                    stringResource(R.string.shuffle),
                    tint = if (playback.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onRepeat()
            }, modifier = Modifier.testTag("repeat-button")) {
                Icon(
                    if (playback.repeatMode == RepeatMode.TRACK) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    stringResource(
                        when (playback.repeatMode) {
                            RepeatMode.OFF -> R.string.repeat_off
                            RepeatMode.CONTEXT -> R.string.repeat_context
                            RepeatMode.TRACK -> R.string.repeat_track
                        },
                    ),
                    tint = if (playback.repeatMode == RepeatMode.OFF) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ErrorDialog(error: UiError, onDismiss: () -> Unit) {
    val message = when (error.kind) {
        ErrorKind.CREDENTIALS_REQUIRED -> stringResource(R.string.credentials_required)
        ErrorKind.NO_ACTIVE_DEVICE -> stringResource(R.string.no_active_device)
        ErrorKind.LOGIN -> stringResource(R.string.login_failed)
        ErrorKind.REQUEST -> stringResource(R.string.request_failed)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = {
            Column {
                Text(message)
                error.detail?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
        icon = { Icon(Icons.Default.Close, contentDescription = null) },
    )
}
