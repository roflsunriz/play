package io.github.playmusic.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.playmusic.R
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.DetailSort
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent

@Composable
fun PlayRoute(viewModel: PlayViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val routeStates = rememberSaveableStateHolder()
    val context = LocalContext.current
    var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }
    val pickPlaylistImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.loadPlaylistImage(context.contentResolver, uri)
    }
    val completedMessage = stringResource(R.string.browser_login_complete)
    val onLogin: () -> Unit = { viewModel.beginBrowserLogin(completedMessage) }
    val pending = state.loginPending
    if (pending != null) {
        CodeChallengeScreen(
            maskedTarget = pending.maskedTarget,
            isLoading = state.isLoading,
            onSubmit = viewModel::submitCode,
            onCancel = viewModel::cancelLogin,
        )
    } else if (!state.isLoggedIn || state.isAuthorizing) {
        BrowserLoginScreen(
            pending = state.browserAuthorization,
            isAuthorizing = state.isAuthorizing,
            onBegin = onLogin,
            onCancel = viewModel::cancelBrowserLogin,
            onFailure = viewModel::reportLoginFailure,
            onBrowserOpened = viewModel::markBrowserOpened,
        )
    } else if (state.playbackSettingsOpen) {
        val settings by viewModel.playbackTransitionsState.collectAsStateWithLifecycle()
        PlaybackSettingsScreen(settings, viewModel::updatePlaybackSettings, viewModel::retryPlaybackSettingsSave,
            viewModel::closePlaybackSettings)
    } else if (state.audioEffectsOpen) {
        val effects by viewModel.audioEffectsState.collectAsStateWithLifecycle()
        AudioEffectsScreen(effects, viewModel::updateAudioEffects, viewModel::applyEqualizerPreset,
            viewModel::loadEqualizerSlot, viewModel::saveEqualizerSlot, viewModel::renameEqualizerSlot,
            viewModel::deleteEqualizerSlot, viewModel::retryAudioEffectsSave, viewModel::closeAudioEffects)
    } else if (state.playlistEditor != null) {
        PlaylistEditorScreen(
            state = checkNotNull(state.playlistEditor),
            onNameChanged = viewModel::updatePlaylistName,
            onDescriptionChanged = viewModel::updatePlaylistDescription,
            onChooseImage = { pickPlaylistImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onUndoImage = viewModel::undoPlaylistImage,
            onRemoveImage = viewModel::removePlaylistImage,
            onSave = viewModel::savePlaylist,
            onCancel = viewModel::closePlaylistEditor,
        )
    } else {
        routeStates.SaveableStateProvider("home:${state.username}") {
            HomeScreen(
                state = state,
                onSectionSelected = viewModel::selectSection,
                onSearchChanged = viewModel::updateSearchQuery,
                onSearch = viewModel::search,
                onRefresh = viewModel::refreshAll,
                onLogout = viewModel::logout,
                onPlay = viewModel::play,
                onPlayPause = viewModel::togglePlayPause,
                onStop = viewModel::stopPlayback,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seek,
                onShuffle = viewModel::toggleShuffle,
                onRepeat = viewModel::cycleRepeat,
                onBrowserLogin = onLogin,
                onOpenContent = viewModel::openDetail,
                onBack = viewModel::closeDetail,
                onPlayDetailTrack = viewModel::playDetailTrack,
                onCreatePlaylist = viewModel::createPlaylist,
                onEditPlaylist = viewModel::editPlaylist,
                onDeletePlaylist = viewModel::requestPlaylistDeletion,
                onLibraryQueryChanged = viewModel::updateLibraryQuery,
                onLibrarySortChanged = viewModel::updateLibrarySort,
                onViewportChanged = viewModel::prefetchDetails,
                onPlaylistSyncRetry = viewModel::retryPlaylistSync,
                onAudioEffects = viewModel::openAudioEffects,
                onSleepTimer = { sleepTimerOpen = true },
                onContentActions = viewModel::openContentActions,
                onSearchFilter = viewModel::selectSearchFilter,
                onPlaybackSettings = viewModel::openPlaybackSettings,
                onArtistFollow = viewModel::toggleArtistFollow,
                onArtistRadio = viewModel::openArtistRadio,
                lyricsContent = { content -> LyricsRoute(content, state.playback, viewModel.lyricsApi,
                    onSeek = { viewModel.seekLyrics(content, it) }) },
                visibleDetail = viewModel.visibleDetail(),
                detailSort = viewModel.detailSort(),
                detailSortOptions = viewModel.detailSortOptions(),
                onDetailSortChanged = viewModel::updateDetailSort,
            )
        }
    }
    state.playlistToDelete?.let {
        DeletePlaylistDialog(it, state.isDeletingPlaylist, state.playlistDeletionFailed,
            viewModel::deletePlaylist, viewModel::cancelPlaylistDeletion)
    }
    if (sleepTimerOpen) SleepTimerDialog(viewModel.sleepTimer) { sleepTimerOpen = false }
    state.contentActions?.let { action ->
        ContentActionsDialog(action, viewModel::toggleFavorite, viewModel::choosePlaylists, viewModel::togglePlaylist,
            viewModel::openSongRadio, viewModel::openArtists, viewModel::retryContentActions, viewModel::closeContentActions)
    }
    if (state.artistChoices.isNotEmpty()) ArtistPicker(state.artistChoices,
        { viewModel.closeArtistChoices(); viewModel.openDetail(it) }, viewModel::closeArtistChoices)
    state.error?.let { ErrorDialog(it, viewModel::clearError, onLogin) }
}

@Composable
private fun CodeChallengeScreen(
    maskedTarget: String,
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
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
        Text(
            if (maskedTarget.isNotBlank()) {
                stringResource(R.string.code_challenge_description, maskedTarget)
            } else {
                stringResource(R.string.code_challenge_description_generic)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.verification_code)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("code-input"),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSubmit(code) },
            enabled = code.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("code-submit-button"),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.verify))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onCancel,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("code-cancel-button"),
        ) {
            Text(stringResource(R.string.cancel))
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
    onBrowserLogin: () -> Unit = {},
    onOpenContent: (SpotifyContent) -> Unit = onPlay,
    onBack: () -> Unit = {},
    onPlayDetailTrack: (Int) -> Unit = { index -> state.detail?.tracks?.getOrNull(index)?.let(onPlay) },
    onCreatePlaylist: () -> Unit = {},
    onEditPlaylist: () -> Unit = {},
    onDeletePlaylist: () -> Unit = {},
    onLibraryQueryChanged: (String) -> Unit = {},
    onLibrarySortChanged: (LibrarySort) -> Unit = {},
    onViewportChanged: (List<SpotifyContent>) -> Unit = {},
    onPlaylistSyncRetry: () -> Unit = onRefresh,
    onAudioEffects: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    onContentActions: (SpotifyContent) -> Unit = {},
    onSearchFilter: (io.github.playmusic.data.model.SearchFilter) -> Unit = {},
    onPlaybackSettings: () -> Unit = {},
    onArtistFollow: () -> Unit = {},
    onArtistRadio: (SpotifyContent) -> Unit = {},
    lyricsContent: @Composable (SpotifyContent) -> Unit = {},
    onStop: () -> Unit = {},
    visibleDetail: ContentDetail? = null,
    detailSort: DetailSort = DetailSort.TRACK_ORDER,
    detailSortOptions: List<DetailSort> = listOf(DetailSort.TRACK_ORDER),
    onDetailSortChanged: (DetailSort) -> Unit = {},
) {
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val listStates = rememberSaveableStateHolder()
    val savedUris = remember(state.libraries) { listOf(LibrarySection.TRACKS, LibrarySection.ALBUMS)
        .flatMap { state.libraries[it].orEmpty() }.map { it.uri }.toSet() }
    if (playerExpanded && state.playback.item != null) {
        ExpandedPlayerScreen(state.playback, onPlayPause, onNext, onPrevious, onSeek, onShuffle, onRepeat,
            onBack = { playerExpanded = false }, onContentActions = onContentActions, savedUris = savedUris, onStop = onStop,
            lyricsContent = lyricsContent)
        return
    }
    BackHandler(enabled = state.selectedContent != null, onBack = onBack)
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current.containerSize
    val compactHeader = with(LocalDensity.current) { window.height.toDp() < 480.dp && window.width.toDp() >= 600.dp }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val syncFailureMessage = stringResource(R.string.playlist_sync_failed)
    val retryLabel = stringResource(R.string.refresh)
    LaunchedEffect(state.playlistSyncFailed) {
        if (state.playlistSyncFailed && snackbarHost.showSnackbar(syncFailureMessage, retryLabel, withDismissAction = true) == SnackbarResult.ActionPerformed)
            onPlaylistSyncRetry()
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    if (compactHeader && state.selectedContent == null && state.selectedSection.kind != null)
                        LibraryFilterInput(state.queryFor(state.selectedSection), onLibraryQueryChanged, Modifier.fillMaxWidth(), state.selectedSection)
                    else if (compactHeader && state.selectedContent == null && state.selectedSection == LibrarySection.SEARCH)
                        CatalogSearchInput(state.searchQuery, onSearchChanged, onSearch, Modifier.fillMaxWidth())
                    else Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    if (state.selectedContent != null) IconButton(onClick = onBack, modifier = Modifier.testTag("detail-back-button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (compactHeader && state.selectedContent == null) {
                        when (state.selectedSection) {
                            LibrarySection.PLAYLISTS, LibrarySection.ALBUMS, LibrarySection.TRACKS ->
                                LibrarySortMenu(checkNotNull(state.selectedSection.kind), state.sortFor(state.selectedSection), onLibrarySortChanged)
                            LibrarySection.SEARCH -> IconButton(onClick = { keyboard?.hide(); onSearch() }, enabled = state.searchQuery.isNotBlank(),
                                modifier = Modifier.testTag("search-button")) { Icon(Icons.Default.Search, stringResource(R.string.search_action)) }
                        }
                    }
                    if (state.selectedSection == LibrarySection.PLAYLISTS && state.selectedContent == null) {
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCreatePlaylist()
                        }, modifier = Modifier.testTag("create-playlist-button")) {
                            Icon(Icons.Default.Add, stringResource(R.string.create_playlist))
                        }
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh-button")) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("settings-button")) {
                        Icon(Icons.Default.Menu, stringResource(R.string.settings))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playback_settings_title)) },
                            leadingIcon = { Icon(Icons.Default.Tune, null) }, modifier = Modifier.testTag("playback-settings-menu-item"),
                            onClick = { menuExpanded = false; onPlaybackSettings() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sleep_timer_title)) },
                            leadingIcon = { Icon(Icons.Default.Timer, null) },
                            modifier = Modifier.testTag("sleep-timer-menu-item"),
                            onClick = { menuExpanded = false; onSleepTimer() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_effects_title)) },
                            leadingIcon = { Icon(Icons.Default.Equalizer, null) },
                            modifier = Modifier.testTag("audio-effects-menu-item"),
                            onClick = { menuExpanded = false; onAudioEffects() })
                        if (!state.isLoggedIn) DropdownMenuItem(
                            modifier = Modifier.testTag("account-login-button"),
                            text = { Text(stringResource(R.string.browser_login)) },
                            onClick = { menuExpanded = false; onBrowserLogin() },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("logout-button"),
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
                        onOpenPlayer = { playerExpanded = true },
                    )
                }
                AppNavigation(state.selectedSection, onSectionSelected)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.selectedContent != null) {
                listStates.SaveableStateProvider("detail:${state.selectedContent.uri}") {
                    if (state.selectedContent.kind == io.github.playmusic.data.model.ContentKind.ARTIST)
                        ArtistPageScreen(state.selectedContent, state.detail, state.isLoading, state.artistFollowBusy,
                            state.artistRadioBusy, onPlay, onPlayDetailTrack, onOpenContent, onArtistFollow, onArtistRadio,
                            onContentActions, savedUris, onRefresh)
                    else ContentDetailScreen(state.selectedContent, visibleDetail, state.isLoading, onPlay, onOpenContent,
                        onRefresh, onPlayDetailTrack, onEditPlaylist, onDeletePlaylist, onContentActions, savedUris,
                        sort = detailSort, sortOptions = detailSortOptions, onSortChanged = onDetailSortChanged)
                }
            } else {
                listStates.SaveableStateProvider(state.selectedSection.name) {
                    if (state.selectedSection == LibrarySection.SEARCH) SearchContent(
                        query = state.searchQuery, items = state.items, suggestions = state.suggestedItems,
                        matchingSuggestions = state.searchSuggestions,
                        previewFailed = state.searchPreviewFailed,
                        onQueryChanged = onSearchChanged, onSearch = onSearch, onPlay = onPlay,
                        onOpen = onOpenContent, onViewportChanged = onViewportChanged, showInput = !compactHeader,
                        searchFilter = state.searchFilter, onSearchFilter = onSearchFilter,
                        onContentActions = onContentActions, savedUris = savedUris)
                    else LibraryContent(state.selectedSection, state.items,
                        state.queryFor(state.selectedSection), state.sortFor(state.selectedSection),
                        onLibraryQueryChanged, onLibrarySortChanged, onPlay, onOpenContent, onViewportChanged, showControls = !compactHeader,
                        onContentActions = onContentActions, savedUris = savedUris)
                }
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
private fun PlaybackBar(
    playback: Playback,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val windowSize = LocalWindowInfo.current.containerSize
    val horizontalControls = with(LocalDensity.current) {
        windowSize.width.toDp() >= 600.dp && windowSize.height.toDp() < 480.dp
    }
    var scrubPosition by remember(playback.item?.uri) { mutableStateOf<Float?>(null) }
    val seekControl: @Composable (Modifier) -> Unit = { modifier ->
        Slider(
            value = (scrubPosition ?: playback.progressMs.toFloat()).coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = {
                scrubPosition = it
            },
            onValueChangeFinished = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scrubPosition?.let { onSeek(it.toLong()) }
                scrubPosition = null
            },
            valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
            enabled = playback.durationMs > 0,
            modifier = modifier.testTag("seek-slider"),
        )
    }
    val modeControls: @Composable () -> Unit = {
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
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.expand_player), onClick = onOpenPlayer)
        .testTag("mini-player").padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).testTag("mini-player-info")) {
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
                    if (playback.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playback.playWhenReady) R.string.pause else R.string.play),
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onNext()
            }, modifier = Modifier.testTag("next-button")) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next))
            }
            if (horizontalControls) {
                seekControl(Modifier.weight(1f))
                modeControls()
            }
        }
        if (!horizontalControls) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                seekControl(Modifier.weight(1f))
                modeControls()
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ErrorDialog(error: UiError, onDismiss: () -> Unit, onLogin: () -> Unit) {
    val message = when (error.kind) {
        ErrorKind.LOGIN -> stringResource(R.string.login_failed)
        ErrorKind.LOGIN_REQUIRED -> stringResource(R.string.login_required)
        ErrorKind.VERIFICATION_CODE -> stringResource(R.string.verification_code_failed)
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
        confirmButton = {
            Row {
                if (error.kind == ErrorKind.LOGIN_REQUIRED) {
                    TextButton(onClick = onLogin, modifier = Modifier.testTag("error-login-button")) {
                        Text(stringResource(R.string.browser_login))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("error-dismiss-button")) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        },
        icon = { Icon(Icons.Default.Close, contentDescription = null) },
    )
}
