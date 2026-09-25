package io.github.playmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.playmusic.AppContainer
import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.auth.LoginVerificationRequiredException
import io.github.playmusic.data.auth.AuthException
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.PlaybackAuthorizationDiagnostics
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.DetailSort
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.audio.EqualizerPreset
import io.github.playmusic.data.audio.settings
import io.github.playmusic.data.playback.StreamingApiClient
import io.github.playmusic.data.playback.PlaybackErrorReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI

enum class LibrarySection(val kind: ContentKind?) {
    PLAYLISTS(ContentKind.PLAYLIST),
    ALBUMS(ContentKind.ALBUM),
    TRACKS(ContentKind.TRACK),
    SEARCH(null),
}

enum class ErrorKind {
    LOGIN,
    LOGIN_REQUIRED,
    VERIFICATION_CODE,
    REQUEST,
}

data class UiError(val kind: ErrorKind, val detail: String? = null)

data class LoginPending(
    val username: String,
    val deviceId: String,
    val loginContext: ByteArray,
    val maskedTarget: String,
)

data class PlayUiState(
    val username: String = "",
    val isLoggedIn: Boolean = false,
    val selectedSection: LibrarySection = LibrarySection.PLAYLISTS,
    val items: List<MusicContent> = emptyList(),
    val playback: Playback = Playback(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val searchFilter: SearchFilter = SearchFilter.ALL,
    val libraryQuery: String = "",
    val albumQuery: String = "",
    val trackQuery: String = "",
    val playlistSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val albumSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val trackSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val playlistDetailSort: DetailSort = DetailSort.ADDED_NEWEST,
    val albumDetailSort: DetailSort = DetailSort.TRACK_ORDER,
    val libraries: Map<LibrarySection, List<MusicContent>> = emptyMap(),
    val suggestedItems: List<MusicContent> = emptyList(),
    val searchSuggestions: List<MusicContent> = emptyList(),
    val searchResults: List<MusicContent> = emptyList(),
    val searchPreviewFailed: Boolean = false,
    val playlistSyncFailed: Boolean = false,
    val error: UiError? = null,
    val errorReport: PlaybackErrorReport? = null,
    val loginPending: LoginPending? = null,
    val browserAuthorization: BrowserAuthorizationUi? = null,
    val isAuthorizing: Boolean = false,
    val isBrowserAuthorized: Boolean = false,
    val selectedContent: MusicContent? = null,
    val detail: ContentDetail? = null,
    val playlistEditor: PlaylistEditorState? = null,
    val playlistToDelete: MusicContent? = null,
    val isDeletingPlaylist: Boolean = false,
    val playlistDeletionFailed: Boolean = false,
    val audioEffectsOpen: Boolean = false,
    val contentActions: ContentActionsState? = null,
    val artistChoices: List<MusicContent> = emptyList(),
    val playbackSettingsOpen: Boolean = false,
    val artistFollowBusy: Boolean = false,
    val artistRadioBusy: Boolean = false,
)

class PlayViewModel(private val container: AppContainer) : ViewModel() {
    private val initialSession = runCatching { container.sessionStore.loadSession() }
    private val mutableState = MutableStateFlow(
        PlayUiState(
            username = initialSession.getOrNull()?.username.orEmpty(),
            isLoggedIn = initialSession.getOrNull() != null,
            isBrowserAuthorized = initialSession.getOrNull()?.refreshToken != null,
            error = initialSession.exceptionOrNull()?.let { UiError(ErrorKind.REQUEST, it.message) },
        ),
    )
    val state: StateFlow<PlayUiState> = mutableState.asStateFlow()
    val audioEffectsState get() = container.audioEffects.state
    val sleepTimer get() = container.sleepTimer
    val lyricsApi get() = container.lyricsApi
    val lrclibApi get() = container.lrclibApi
    val playbackTransitionsState get() = container.playbackTransitions.state
    private var actionsJob: Job? = null
    private var contentRequestJob: Job? = null
    private val libraryJobs = mutableMapOf<LibrarySection, Job>()
    private val detailPrefetcher = DetailPrefetcher(viewModelScope,
        isCached = { container.repository.peekDetail(it) != null },
        load = { container.repository.detail(it) })
    private var accountGeneration = 0L
    private val searchCache = object : LinkedHashMap<String, List<MusicContent>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MusicContent>>?): Boolean = size > 24
    }
    private var browserAuthorizationJob: Job? = null
    private var pendingAuthorization: BrowserAuthorizationClient.Pending? = null
    private var authorizationGeneration = 0L
    private var legacyLoginJob: Job? = null
    private var playbackRequestJob: Job? = null
    private var playbackSnapshotJob: Job? = null
    private var playbackWarmupJob: Job? = null
    private val detailHistory = ArrayDeque<ContentDetail>()
    private var playlistWriteJob: Job? = null
    private var playlistImageJob: Job? = null
    private var playlistEditorGeneration = 0L

    init {
        viewModelScope.launch { container.localPlayback.state.collect { mutableState.value = mutableState.value.copy(playback = it) } }
        viewModelScope.launch { container.localPlayback.errors.collect {
            if (mutableState.value.isLoggedIn) mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.REQUEST, it))
        } }
        viewModelScope.launch { container.localPlayback.latestReport.collect {
            if (it != null && mutableState.value.isLoggedIn) mutableState.value = mutableState.value.copy(errorReport = it)
        } }
        viewModelScope.launch { container.repository.playlistCacheFailures.collect {
            if (mutableState.value.isLoggedIn) mutableState.value = mutableState.value.copy(playlistSyncFailed = true)
        } }
        viewModelScope.launch {
            val settings = container.detailSorts.state.first { it.isReady }.settings
            mutableState.value = mutableState.value.copy(
                playlistDetailSort = settings.playlistDetailSort, albumDetailSort = settings.albumDetailSort)
        }
        if (mutableState.value.isLoggedIn) {
            prefetchLibraries()
            refreshPlayback()
            warmPlaybackAuthorization()
        }
    }

    fun openAudioEffects() { mutableState.value = mutableState.value.copy(audioEffectsOpen = true) }

    fun openPlaybackSettings() { mutableState.value = mutableState.value.copy(playbackSettingsOpen = true) }
    fun closePlaybackSettings() {
        container.playbackTransitions.requestSave()
        mutableState.value = mutableState.value.copy(playbackSettingsOpen = false)
    }
    fun updatePlaybackSettings(settings: io.github.playmusic.data.playback.PlaybackTransitionSettings) {
        container.playbackTransitions.setSettings(settings)
    }
    fun retryPlaybackSettingsSave() { container.playbackTransitions.requestSave() }

    fun closeAudioEffects() {
        container.audioEffects.requestSave()
        mutableState.value = mutableState.value.copy(audioEffectsOpen = false)
    }

    fun updateAudioEffects(settings: EqualizerSettings) { container.audioEffects.setSettings(settings) }
    fun applyEqualizerPreset(preset: EqualizerPreset) { container.audioEffects.setSettings(preset.settings()) }
    fun loadEqualizerSlot(index: Int) { container.audioEffects.loadSlot(index) }
    fun saveEqualizerSlot(index: Int, name: String) { container.audioEffects.saveSlot(index, name) }
    fun renameEqualizerSlot(index: Int, name: String) { container.audioEffects.renameSlot(index, name) }
    fun deleteEqualizerSlot(index: Int) { container.audioEffects.deleteSlot(index) }
    fun retryAudioEffectsSave() { container.audioEffects.requestSave() }

    fun beginBrowserLogin(completedMessage: String) {
        if (mutableState.value.isAuthorizing) return
        val generation = ++authorizationGeneration
        cancelBrowsing()
        contentRequestJob?.cancel()
        legacyLoginJob?.cancel()
        playbackRequestJob?.cancel()
        playbackWarmupJob?.cancel()
        mutableState.value = mutableState.value.copy(isAuthorizing = true, isLoading = false,
            browserAuthorization = null, loginPending = null, error = null)
        browserAuthorizationJob = viewModelScope.launch {
            var ownedAuthorization: BrowserAuthorizationClient.Pending? = null
            try {
                container.startLoginWaiting()
                val authorization = container.browserAuthorizationClient.begin()
                ownedAuthorization = authorization
                pendingAuthorization = authorization
                mutableState.value = mutableState.value.copy(browserAuthorization =
                    BrowserAuthorizationUi(authorization.authorizationUrl))
                val tokens = container.browserAuthorizationClient.awaitAuthorization(authorization, completedMessage, container::returnToApp)
                val username = container.accessPointIdentity.username(tokens.accessToken, container.sessionStore.loadDeviceId())
                val previousUsername = mutableState.value.username
                check(previousUsername.isBlank() || previousUsername == username) { "Login returned a different account" }
                if (mutableState.value.username != username) container.localPlayback.clear()
                container.sessionManager.replaceSession(io.github.playmusic.data.model.AuthSession(
                    username = username,
                    accessToken = tokens.accessToken,
                    storedCredential = null,
                    expiresAtEpochMs = System.currentTimeMillis() + tokens.expiresInSeconds * 1_000,
                    refreshToken = checkNotNull(tokens.refreshToken),
                ))
                mutableState.value = PlayUiState(username = username, isLoggedIn = true, isBrowserAuthorized = true)
                prefetchLibraries()
                refreshPlayback()
                warmPlaybackAuthorization()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                mutableState.value = mutableState.value.copy(isAuthorizing = false, browserAuthorization = null,
                    error = UiError(ErrorKind.LOGIN, exception.message))
            } finally {
                ownedAuthorization?.close()
                if (authorizationGeneration == generation) {
                    if (pendingAuthorization === ownedAuthorization) pendingAuthorization = null
                    container.stopLoginWaiting()
                }
            }
        }
    }

    fun cancelBrowserLogin() {
        authorizationGeneration++
        browserAuthorizationJob?.cancel()
        pendingAuthorization?.close()
        pendingAuthorization = null
        container.stopLoginWaiting()
        mutableState.value = mutableState.value.copy(isAuthorizing = false, browserAuthorization = null, error = null)
    }

    fun submitCode(code: String) {
        if (mutableState.value.isLoading) return
        val pending = mutableState.value.loginPending ?: return
        val trimmed = code.trim()
        if (trimmed.isBlank()) return
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        legacyLoginJob = viewModelScope.launch {
            try {
                val session = container.sessionStore.loadSession()?.takeIf { it.username == pending.username }
                val credential = session?.storedCredential ?: throw AuthException("Saved login information is unavailable")
                val outcome = container.login5Client.loginWithStoredCredentialAndCode(
                    username = pending.username, storedCredential = credential, deviceId = pending.deviceId,
                    code = trimmed, loginContext = pending.loginContext,
                )
                when (outcome) {
                    is io.github.playmusic.data.auth.Login5Client.LoginOutcome.Success -> completeLogin(outcome)
                    is io.github.playmusic.data.auth.Login5Client.LoginOutcome.CodeChallengeRequired -> {
                        mutableState.value = mutableState.value.copy(
                            isLoading = false,
                            loginPending = pending.copy(
                                loginContext = outcome.loginContext,
                                maskedTarget = outcome.maskedTarget,
                            ),
                            error = UiError(ErrorKind.VERIFICATION_CODE),
                        )
                    }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = UiError(ErrorKind.LOGIN, exception.message),
                )
            }
        }
    }

    fun cancelLogin() {
        legacyLoginJob?.cancel()
        contentRequestJob?.cancel()
        mutableState.value = mutableState.value.copy(loginPending = null, isLoading = false)
    }

    private fun completeLogin(
        success: io.github.playmusic.data.auth.Login5Client.LoginOutcome.Success,
    ) {
        val pending = mutableState.value.loginPending
        val previousCredential = if (pending != null) {
            container.sessionStore.loadSession()?.takeIf { it.username == pending.username }?.storedCredential
        } else null
        val session = io.github.playmusic.data.model.AuthSession(
            username = success.username,
            accessToken = success.accessToken,
            storedCredential = success.storedCredential ?: previousCredential,
            expiresAtEpochMs = System.currentTimeMillis() + success.accessTokenExpiresIn * 1_000L,
        )
        container.sessionManager.replaceSession(session)
        mutableState.value = mutableState.value.copy(
            username = success.username,
            isLoggedIn = true,
            isLoading = false,
            loginPending = null,
        )
        prefetchLibraries()
        refreshPlayback()
        warmPlaybackAuthorization()
    }

    fun selectSection(section: LibrarySection) {
        if (section == mutableState.value.selectedSection && mutableState.value.selectedContent == null) return
        contentRequestJob?.cancel()
        detailPrefetcher.update(emptyList())
        detailHistory.clear()
        val current = mutableState.value
        val cached = current.libraries[section]
        mutableState.value = current.copy(selectedSection = section,
            items = if (section == LibrarySection.SEARCH) current.searchResults else visibleItems(section, cached.orEmpty()),
            isLoading = cached == null && section != LibrarySection.SEARCH,
            error = null, selectedContent = null, detail = null)
        if (section != LibrarySection.SEARCH) loadLibrary(section)
        else if (current.searchQuery.isNotBlank() && !searchCache.containsKey(searchKey(current.searchQuery.trim()))) search()
    }

    fun updateLibraryQuery(query: String) {
        val current = mutableState.value
        mutableState.value = when (current.selectedSection) {
            LibrarySection.PLAYLISTS -> current.copy(libraryQuery = query)
            LibrarySection.ALBUMS -> current.copy(albumQuery = query)
            LibrarySection.TRACKS -> current.copy(trackQuery = query)
            LibrarySection.SEARCH -> current
        }
        updateVisibleItems()
    }

    fun updateLibrarySort(sort: LibrarySort) {
        val current = mutableState.value
        val kind = current.selectedSection.kind ?: return
        if (sort !in LibrarySort.options(kind)) return
        mutableState.value = when (current.selectedSection) {
            LibrarySection.PLAYLISTS -> current.copy(playlistSort = sort)
            LibrarySection.ALBUMS -> current.copy(albumSort = sort)
            LibrarySection.TRACKS -> current.copy(trackSort = sort)
            else -> current
        }
        updateVisibleItems()
    }

    fun updateDetailSort(sort: DetailSort) {
        val current = mutableState.value
        val kind = current.selectedContent?.kind ?: return
        if (sort !in DetailSort.options(kind)) return
        mutableState.value = when (kind) {
            ContentKind.PLAYLIST -> current.copy(playlistDetailSort = sort)
            ContentKind.ALBUM -> current.copy(albumDetailSort = sort)
            else -> return
        }
        container.detailSorts.setSettings(io.github.playmusic.data.playback.DetailSortSettings(
            mutableState.value.playlistDetailSort, mutableState.value.albumDetailSort))
    }

    private fun detailSortFor(kind: ContentKind): DetailSort = when (kind) {
        ContentKind.PLAYLIST -> mutableState.value.playlistDetailSort
        ContentKind.ALBUM -> mutableState.value.albumDetailSort
        else -> DetailSort.TRACK_ORDER
    }

    /** Tracks in the order currently displayed, so playback follows the chosen sort. */
    private fun orderedDetailTracks(detail: ContentDetail?): List<MusicContent> {
        if (detail == null) return emptyList()
        val sort = detailSortFor(detail.content.kind).takeIf { it in DetailSort.options(detail.content.kind) }
            ?: DetailSort.TRACK_ORDER
        return presentDetailTracks(detail.tracks, sort)
    }

    internal fun visibleDetail(): ContentDetail? {
        val detail = mutableState.value.detail ?: return null
        return detail.copy(tracks = orderedDetailTracks(detail))
    }

    internal fun detailSort(): DetailSort {
        val kind = mutableState.value.selectedContent?.kind ?: return DetailSort.TRACK_ORDER
        return detailSortFor(kind).takeIf { it in DetailSort.options(kind) } ?: DetailSort.TRACK_ORDER
    }

    internal fun detailSortOptions(): List<DetailSort> =
        DetailSort.options(mutableState.value.selectedContent?.kind ?: return listOf(DetailSort.TRACK_ORDER))

    fun updateSearchQuery(query: String) {
        contentRequestJob?.cancel()
        val cached = searchCache[searchKey(query.trim())].orEmpty()
        mutableState.value = mutableState.value.copy(searchQuery = query, searchResults = cached,
            items = cached, isLoading = false, searchPreviewFailed = false, searchSuggestions = searchSuggestions(query))
        if (query.isNotBlank()) search(waitForTyping = true)
    }

    fun search() = search(waitForTyping = false)

    private fun searchKey(query: String) = "${mutableState.value.searchFilter.name}:$query"

    fun selectSearchFilter(filter: SearchFilter) {
        if (mutableState.value.searchFilter == filter) return
        contentRequestJob?.cancel()
        mutableState.value = mutableState.value.copy(searchFilter = filter, items = emptyList(),
            searchResults = emptyList(), searchPreviewFailed = false)
        search()
    }

    private fun search(waitForTyping: Boolean, forceRefresh: Boolean = false) {
        contentRequestJob?.cancel()
        val query = mutableState.value.searchQuery.trim()
        if (query.isBlank()) return
        val filter = mutableState.value.searchFilter
        val key = searchKey(query)
        val cached = searchCache[key]
        if (cached != null && !forceRefresh) {
            mutableState.value = mutableState.value.copy(items = cached, searchResults = cached, isLoading = false)
            return
        }
        val generation = accountGeneration
        contentRequestJob = viewModelScope.launch {
            if (waitForTyping) delay(350)
            mutableState.value = mutableState.value.copy(isLoading = true, error = null, searchPreviewFailed = false)
            try {
                val items = container.repository.search(query, filter)
                if (generation == accountGeneration && mutableState.value.searchQuery.trim() == query &&
                    mutableState.value.searchFilter == filter &&
                    mutableState.value.selectedSection == LibrarySection.SEARCH) {
                    searchCache[key] = items
                    mutableState.value = mutableState.value.copy(items = items, searchResults = items, isLoading = false)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Typing previews keep local matches useful when the network is unavailable.
                if (!waitForTyping || exception is AuthException && exception.requiresLogin) handleRequestFailure(exception)
                else mutableState.value = mutableState.value.copy(isLoading = false, searchPreviewFailed = true)
            }
        }
    }

    fun refreshAll() {
        val selectedContent = mutableState.value.selectedContent
        if (selectedContent != null) loadDetail(selectedContent, rememberCurrent = false, forceRefresh = true)
        else if (mutableState.value.selectedSection == LibrarySection.SEARCH) search(waitForTyping = false, forceRefresh = true)
        else loadLibrary(mutableState.value.selectedSection, forceRefresh = true)
        refreshPlayback()
    }

    fun retryPlaylistSync() = loadLibrary(LibrarySection.PLAYLISTS, forceRefresh = true)

    fun openDetail(content: MusicContent) = loadDetail(content, rememberCurrent = true)

    fun toggleArtistFollow() {
        val detail = mutableState.value.detail ?: return
        val page = detail.artistPage ?: return
        if (mutableState.value.artistFollowBusy) return
        mutableState.value = mutableState.value.copy(artistFollowBusy = true)
        viewModelScope.launch {
            try {
                val followed = container.repository.setArtistFollowed(detail.content, !page.isFollowed)
                val latest = mutableState.value.detail
                if (latest?.content?.uri == detail.content.uri) mutableState.value = mutableState.value.copy(
                    detail = latest.copy(artistPage = latest.artistPage?.copy(isFollowed = followed)))
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                handleRequestFailure(exception, showLoading = false)
            } finally { mutableState.value = mutableState.value.copy(artistFollowBusy = false) }
        }
    }

    fun openArtistRadio(track: MusicContent) {
        if (mutableState.value.artistRadioBusy) return
        val origin = mutableState.value.selectedContent?.uri
        val generation = accountGeneration
        mutableState.value = mutableState.value.copy(artistRadioBusy = true)
        viewModelScope.launch {
            try {
                val radio = container.repository.radio(track)
                if (generation == accountGeneration && mutableState.value.selectedContent?.uri == origin) openDetail(radio)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (generation == accountGeneration) handleRequestFailure(exception, showLoading = false)
            } finally { mutableState.value = mutableState.value.copy(artistRadioBusy = false) }
        }
    }

    fun seekLyrics(content: MusicContent, positionMs: Long) = executePlaybackRequest {
        runCatching { ensurePlaybackAuthorization() }
        if (container.localPlayback.snapshot().item?.uri == content.uri) container.localPlayback.seekAndPlay(positionMs)
        else container.localPlayback.play(listOf(content), startPositionMs = positionMs)
    }

    fun openContentActions(content: MusicContent) {
        if (content.kind !in setOf(ContentKind.TRACK, ContentKind.ALBUM)) return
        actionsJob?.cancel()
        mutableState.value = mutableState.value.copy(contentActions = ContentActionsState(content))
        actionsJob = viewModelScope.launch {
            try {
                val saved = container.repository.isSaved(content, forceRefresh = true)
                mutableState.value = mutableState.value.copy(contentActions = ContentActionsState(content, saved, loading = false))
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun closeContentActions() {
        if (mutableState.value.contentActions?.busy == true) return
        actionsJob?.cancel()
        mutableState.value = mutableState.value.copy(contentActions = null)
    }

    fun toggleFavorite() {
        val action = mutableState.value.contentActions?.takeUnless { it.busy || it.loading || it.saved == null } ?: return
        mutableState.value = mutableState.value.copy(contentActions = action.copy(busy = true, failure = false))
        actionsJob = viewModelScope.launch {
            try {
                container.repository.setSaved(action.content, action.saved != true)
                mutableState.value = mutableState.value.copy(contentActions = action.copy(saved = action.saved != true, busy = false))
                val section = if (action.content.kind == ContentKind.TRACK) LibrarySection.TRACKS else LibrarySection.ALBUMS
                loadLibrary(section, forceRefresh = true)
                if (mutableState.value.selectedContent?.let(io.github.playmusic.data.api.MusicRepository::isLikedSongs) == true)
                    loadDetail(checkNotNull(mutableState.value.selectedContent), rememberCurrent = false, forceRefresh = true)
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun choosePlaylists() {
        val action = mutableState.value.contentActions?.takeUnless { it.busy || it.loading } ?: return
        actionsJob?.cancel()
        mutableState.value = mutableState.value.copy(contentActions = action.copy(choosingPlaylist = true, loading = true, failure = false))
        actionsJob = viewModelScope.launch {
            try {
                val choices = container.repository.playlistMembership(action.content).map { PlaylistChoice(it.playlist, it.containsAll) }
                mutableState.value = mutableState.value.copy(contentActions = action.copy(choosingPlaylist = true, loading = false, playlists = choices))
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun togglePlaylist(choice: PlaylistChoice) {
        val action = mutableState.value.contentActions?.takeUnless { it.busy || it.loading } ?: return
        mutableState.value = mutableState.value.copy(contentActions = action.copy(busy = true, failure = false))
        actionsJob = viewModelScope.launch {
            try {
                container.repository.setPlaylistMembership(choice.playlist, action.content, !choice.containsAll)
                val choices = container.repository.playlistMembership(action.content).map { PlaylistChoice(it.playlist, it.containsAll) }
                mutableState.value = mutableState.value.copy(contentActions = action.copy(playlists = choices, busy = false))
                loadLibrary(LibrarySection.PLAYLISTS, forceRefresh = true, refreshOwnerNames = false)
                mutableState.value.selectedContent?.takeIf { it.uri == choice.playlist.uri }?.let {
                    loadDetail(it, rememberCurrent = false, forceRefresh = true)
                }
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun retryContentActions() {
        val action = mutableState.value.contentActions ?: return
        if (action.choosingPlaylist) choosePlaylists() else openContentActions(action.content)
    }

    fun openSongRadio() {
        val action = mutableState.value.contentActions?.takeUnless { it.busy || it.loading } ?: return
        actionsJob?.cancel()
        mutableState.value = mutableState.value.copy(contentActions = action.copy(busy = true, failure = false))
        actionsJob = viewModelScope.launch {
            try {
                val playlist = container.repository.radio(action.content)
                mutableState.value = mutableState.value.copy(contentActions = null)
                openDetail(playlist)
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun openArtists() {
        val action = mutableState.value.contentActions?.takeUnless { it.busy || it.loading } ?: return
        actionsJob?.cancel()
        mutableState.value = mutableState.value.copy(contentActions = action.copy(busy = true, failure = false))
        actionsJob = viewModelScope.launch {
            try {
                val content = container.repository.detail(action.content).content
                val artists = content.artists.map { MusicContent(it.uri.substringAfterLast(':'), it.uri,
                    it.name, "", null, ContentKind.ARTIST) }
                check(artists.isNotEmpty()) { "Artist information is unavailable" }
                mutableState.value = mutableState.value.copy(contentActions = null, artistChoices = if (artists.size > 1) artists else emptyList())
                if (artists.size == 1) openDetail(artists.single())
            } catch (exception: Exception) { actionsFailed(exception) }
        }
    }

    fun closeArtistChoices() { mutableState.value = mutableState.value.copy(artistChoices = emptyList()) }

    private fun actionsFailed(exception: Exception) {
        if (exception is CancellationException) throw exception
        val action = mutableState.value.contentActions ?: return
        mutableState.value = mutableState.value.copy(contentActions = action.copy(loading = false, busy = false, failure = true))
        if (exception is AuthException && exception.requiresLogin || exception is BrowserAuthorizationRequiredException)
            handleRequestFailure(exception, showLoading = false)
    }

    private fun loadDetail(content: MusicContent, rememberCurrent: Boolean, forceRefresh: Boolean = false) {
        contentRequestJob?.cancel()
        val current = mutableState.value.detail
        if (rememberCurrent && current != null && current.content.uri != content.uri) detailHistory.addLast(current)
        // The repository's cache lookup validates the account via Keystore; keep it in the IO request below.
        val cached = current?.takeIf { it.content.uri == content.uri }
        mutableState.value = mutableState.value.copy(selectedContent = content, detail = cached, error = null,
            isLoading = cached == null)
        if (cached != null && !forceRefresh) return
        contentRequestJob = executeRequest {
            val detail = container.repository.detail(content, forceRefresh)
            mutableState.value = mutableState.value.copy(detail = detail, selectedContent = detail.content)
        }
    }

    fun closeDetail() {
        contentRequestJob?.cancel()
        val previous = detailHistory.removeLastOrNull()
        mutableState.value = mutableState.value.copy(selectedContent = previous?.content, detail = previous,
            isLoading = false, error = null)
        updateVisibleItems()
    }

    fun createPlaylist() {
        if (!mutableState.value.isLoggedIn || playlistWriteJob?.isActive == true) return
        playlistEditorGeneration++
        mutableState.value = mutableState.value.copy(playlistEditor = PlaylistEditorState(), error = null)
    }

    fun editPlaylist() {
        if (playlistWriteJob?.isActive == true) return
        val detail = mutableState.value.detail ?: return
        val metadata = detail.playlistMetadata?.takeIf { it.canEdit } ?: return
        playlistEditorGeneration++
        mutableState.value = mutableState.value.copy(playlistEditor = PlaylistEditorState(
            content = detail.content, name = metadata.name, description = metadata.description, imageUrl = metadata.imageUrl,
        ), error = null)
    }

    fun updatePlaylistName(name: String) = updatePlaylistEditor { it.copy(name = name) }

    fun updatePlaylistDescription(description: String) = updatePlaylistEditor { it.copy(description = description) }

    private fun updatePlaylistEditor(update: (PlaylistEditorState) -> PlaylistEditorState) {
        val editor = mutableState.value.playlistEditor?.takeUnless { it.isSaving } ?: return
        mutableState.value = mutableState.value.copy(playlistEditor = update(editor))
    }

    fun loadPlaylistImage(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val editor = mutableState.value.playlistEditor?.takeUnless { it.isSaving } ?: return
        val generation = playlistEditorGeneration
        playlistImageJob?.cancel()
        mutableState.value = mutableState.value.copy(playlistEditor = editor.copy(isLoadingImage = true, failure = null))
        playlistImageJob = viewModelScope.launch {
            try {
                val bytes = PlaylistArtwork.read(resolver, uri)
                if (playlistEditorGeneration == generation) updatePlaylistEditor {
                    it.copy(imageJpeg = bytes, removeImage = false, isLoadingImage = false, failure = null)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (playlistEditorGeneration == generation) updatePlaylistEditor {
                    it.copy(isLoadingImage = false, failure = PlaylistEditorFailure.IMAGE)
                }
            }
        }
    }

    fun undoPlaylistImage() = updatePlaylistEditor { it.copy(imageJpeg = null, removeImage = false, failure = null) }

    fun removePlaylistImage() = updatePlaylistEditor { it.copy(imageJpeg = null, removeImage = true, failure = null) }

    fun closePlaylistEditor() {
        if (mutableState.value.playlistEditor?.isSaving == true) return
        playlistImageJob?.cancel()
        playlistEditorGeneration++
        mutableState.value = mutableState.value.copy(playlistEditor = null)
    }

    fun savePlaylist() {
        val editor = mutableState.value.playlistEditor?.takeIf { it.canSave } ?: return
        mutableState.value = mutableState.value.copy(playlistEditor = editor.copy(isSaving = true, failure = null))
        playlistWriteJob = viewModelScope.launch {
            var written: MusicContent? = null
            try {
                val target = editor.content ?: container.repository.createPlaylist(editor.name.trim(), editor.description).also {
                    written = it
                    val current = mutableState.value.playlistEditor ?: return@launch
                    // Retain the new URI before artwork upload so retries cannot create a duplicate.
                    mutableState.value = mutableState.value.copy(playlistEditor = current.copy(content = it))
                }
                if (editor.creationNeedsCompletion) container.repository.completePlaylistCreation(target)
                val updated = if (editor.content != null || editor.imageJpeg != null) {
                    container.repository.updatePlaylistMetadata(target, editor.name.trim(), editor.description, editor.imageJpeg, editor.removeImage)
                } else target
                written = updated
                updatePlaylistInLibrary(updated)
                mutableState.value = mutableState.value.copy(playlistEditor = null)
                searchCache.clear()
                loadDetail(updated, rememberCurrent = false, forceRefresh = true)
                loadLibrary(LibrarySection.PLAYLISTS, forceRefresh = true, refreshOwnerNames = false)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val current = mutableState.value.playlistEditor ?: return@launch
                val partialCreation = exception as? io.github.playmusic.data.api.PlaylistCreationException
                mutableState.value = mutableState.value.copy(playlistEditor = current.copy(
                    content = partialCreation?.createdContent ?: written ?: current.content, isSaving = false,
                    creationNeedsCompletion = partialCreation != null || current.creationNeedsCompletion,
                    failure = if (written != null || partialCreation != null) PlaylistEditorFailure.PARTIAL_SAVE else PlaylistEditorFailure.SAVE,
                ))
            }
        }
    }

    fun requestPlaylistDeletion() {
        val detail = mutableState.value.detail ?: return
        if (detail.playlistMetadata?.canDelete != true || playlistWriteJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(playlistToDelete = detail.content, playlistDeletionFailed = false)
    }

    fun cancelPlaylistDeletion() {
        if (mutableState.value.isDeletingPlaylist) return
        mutableState.value = mutableState.value.copy(playlistToDelete = null, playlistDeletionFailed = false)
    }

    fun deletePlaylist() {
        val content = mutableState.value.playlistToDelete ?: return
        if (mutableState.value.isDeletingPlaylist) return
        mutableState.value = mutableState.value.copy(isDeletingPlaylist = true, playlistDeletionFailed = false)
        playlistWriteJob = viewModelScope.launch {
            try {
                container.repository.deletePlaylist(content)
                detailHistory.clear()
                searchCache.clear()
                val libraries = mutableState.value.libraries.mapValues { (_, items) -> items.filterNot { it.uri == content.uri } }
                mutableState.value = mutableState.value.copy(playlistToDelete = null, isDeletingPlaylist = false,
                    selectedContent = null, detail = null, libraries = libraries,
                    searchResults = mutableState.value.searchResults.filterNot { it.uri == content.uri },
                    suggestedItems = librarySuggestions(libraries))
                updateVisibleItems()
                loadLibrary(LibrarySection.PLAYLISTS, forceRefresh = true, refreshOwnerNames = false)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                mutableState.value = mutableState.value.copy(isDeletingPlaylist = false, playlistDeletionFailed = true)
            }
        }
    }

    fun refreshPlayback(): Job {
        playbackSnapshotJob?.cancel()
        return executeRequest(showLoading = false) {
            val playback = container.localPlayback.snapshot()
            mutableState.value = mutableState.value.copy(playback = playback)
        }.also { playbackSnapshotJob = it }
    }

    /**
     * Warms the derived playback credentials in the background right after sign-in, so the
     * first license request does not pay the multi-round-trip cost on the DRM thread.
     */
    private fun warmPlaybackAuthorization() {
        playbackWarmupJob?.cancel()
        playbackWarmupJob = viewModelScope.launch {
            runCatching { PlaybackAuthorizationDiagnostics.run(container) }.onFailure {
                android.util.Log.i("PlayAuthDiag", "probe-failed ${it.javaClass.simpleName}")
            }
            runCatching { ensurePlaybackAuthorization() }
        }
    }

    /** Awaits warmed credentials before ExoPlayer prepares a new track. Failures are left to the DRM callback. */
    private suspend fun ensurePlaybackAuthorization() {
        withContext(Dispatchers.IO) {
            container.playbackAuthorization.prepare(URI(StreamingApiClient.LICENSE_URL))
        }
    }

    fun play(content: MusicContent) {
        if (!mutableState.value.isBrowserAuthorized) {
            mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.LOGIN_REQUIRED))
            return
        }
        executePlaybackRequest {
            val detail = mutableState.value.detail
            val tracks = when (content.kind) {
                ContentKind.TRACK -> mutableState.value.items.filter { it.kind == ContentKind.TRACK }.takeIf { list ->
                    list.any { it.uri == content.uri }
                } ?: listOf(content)
                ContentKind.ALBUM, ContentKind.PLAYLIST, ContentKind.ARTIST ->
                    orderedDetailTracks(detail?.takeIf { it.content.uri == content.uri })
                        .ifEmpty { container.repository.detail(content).let { orderedDetailTracks(it) } }
                else -> throw IllegalArgumentException("Unsupported playback item")
            }.filter { it.isPlayable != false }
            check(tracks.isNotEmpty()) { "No playable tracks are available" }
            val index = if (content.kind == ContentKind.TRACK) tracks.indexOfFirst { it.uri == content.uri }.coerceAtLeast(0) else 0
            runCatching { ensurePlaybackAuthorization() }
            container.localPlayback.play(tracks, index, contextUri = content.takeUnless { it.kind == ContentKind.TRACK }?.uri)
        }
    }

    fun playDetailTrack(index: Int) {
        val source = orderedDetailTracks(mutableState.value.detail)
        if (index !in source.indices || source[index].isPlayable == false) return
        val tracks = source.filter { it.isPlayable != false }
        val selectedIndex = source.take(index).count { it.isPlayable != false }
        val contextUri = mutableState.value.detail?.content?.uri
        executePlaybackRequest {
            runCatching { ensurePlaybackAuthorization() }
            container.localPlayback.play(tracks, selectedIndex, contextUri = contextUri)
        }
    }

    fun togglePlayPause() = executePlaybackRequest {
        if (mutableState.value.playback.playWhenReady) container.localPlayback.pause() else container.localPlayback.resume()
    }

    fun stopPlayback() = executePlaybackRequest { container.localPlayback.stop() }

    fun next() = executePlaybackRequest { container.localPlayback.next() }

    fun previous() = executePlaybackRequest { container.localPlayback.previous() }

    fun seek(positionMs: Long) = executePlaybackRequest { container.localPlayback.seek(positionMs) }

    fun toggleShuffle() = executePlaybackRequest {
        container.localPlayback.setShuffle(!mutableState.value.playback.shuffle)
    }

    fun cycleRepeat() = executePlaybackRequest {
        container.localPlayback.setRepeat(mutableState.value.playback.repeatMode.next())
    }

    fun logout() {
        val previousAccount = mutableState.value.username
        authorizationGeneration++
        cancelBrowsing()
        contentRequestJob?.cancel()
        browserAuthorizationJob?.cancel()
        pendingAuthorization?.close()
        pendingAuthorization = null
        container.stopLoginWaiting()
        legacyLoginJob?.cancel()
        playbackRequestJob?.cancel()
        playbackSnapshotJob?.cancel()
        playbackWarmupJob?.cancel()
        val clearFailure = runCatching { container.sessionManager.clearSession() }.exceptionOrNull()
        if (clearFailure == null) container.repository.clearCache()
        playlistWriteJob?.cancel()
        playlistImageJob?.cancel()
        playlistEditorGeneration++
        detailHistory.clear()
        mutableState.value = if (clearFailure == null) PlayUiState()
            else mutableState.value.copy(isAuthorizing = false, browserAuthorization = null, loginPending = null,
                isLoading = false, error = UiError(ErrorKind.REQUEST, clearFailure.message))
        viewModelScope.launch {
            if (clearFailure == null && previousAccount.isNotBlank()) container.repository.clearPlaylistDiskCache(previousAccount)
            try { container.localPlayback.clear() }
            catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.REQUEST, exception.message))
            }
        }
    }

    fun clearError() {
        container.localPlayback.clearReport()
        mutableState.value = mutableState.value.copy(error = null, errorReport = null)
    }

    /** Shares the anonymized report through the user's own apps; never uploads automatically. */
    fun shareErrorReport(context: android.content.Context) {
        val report = mutableState.value.errorReport ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, report.toShareText())
        val chooser = android.content.Intent.createChooser(send,
            context.getString(io.github.playmusic.R.string.diagnostics_share))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Opens a prefilled issue page in the user's browser/app; posting stays a manual step. */
    fun openErrorIssue(context: android.content.Context) {
        val report = mutableState.value.errorReport ?: return
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(report.issueUrl()))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
    }

    fun reportLoginFailure(message: String?) {
        cancelBrowserLogin()
        mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.LOGIN, message))
    }

    private fun prefetchLibraries() {
        val section = mutableState.value.selectedSection.takeUnless { it == LibrarySection.SEARCH } ?: LibrarySection.PLAYLISTS
        loadLibrary(section, forceRefresh = true, warmOthers = true, refreshOwnerNames = false)
    }

    private fun visibleItems(section: LibrarySection, source: List<MusicContent>): List<MusicContent> {
        val current = mutableState.value
        return presentLibrary(source, current.queryFor(section), current.sortFor(section))
    }

    private fun updateVisibleItems() {
        val current = mutableState.value
        mutableState.value = current.copy(items = if (current.selectedSection == LibrarySection.SEARCH) current.searchResults
            else visibleItems(current.selectedSection, current.libraries[current.selectedSection].orEmpty()))
    }

    private fun searchSuggestions(query: String): List<MusicContent> = if (query.isBlank()) emptyList() else
        presentLibrary(mutableState.value.libraries.values.flatten().distinctBy { it.uri }, query, LibrarySort.LIBRARY_ORDER).take(6)

    private fun updatePlaylistInLibrary(content: MusicContent) {
        val current = mutableState.value
        val old = current.libraries[LibrarySection.PLAYLISTS].orEmpty()
        val entries = if (old.any { it.uri == content.uri }) old.map { if (it.uri == content.uri) content else it }
            else listOf(content) + old
        val updated = entries.sortedBy { !io.github.playmusic.data.api.MusicRepository.isLikedSongs(it) }
        val libraries = current.libraries + (LibrarySection.PLAYLISTS to updated)
        mutableState.value = current.copy(libraries = libraries, suggestedItems = librarySuggestions(libraries),
            searchResults = current.searchResults.map { if (it.uri == content.uri) content else it })
        updateVisibleItems()
    }

    private fun loadLibrary(section: LibrarySection, forceRefresh: Boolean = false, warmOthers: Boolean = false,
        refreshOwnerNames: Boolean = forceRefresh) {
        val kind = section.kind ?: return
        if (libraryJobs[section]?.isActive == true && !forceRefresh) return
        // Already published tabs are ready synchronously. Other cache reads belong to repository.library on IO.
        val cached = mutableState.value.libraries[section]
        if (cached != null && !forceRefresh) {
            if (warmOthers) LibrarySection.entries.filter { it.kind != null && it != section }.forEach { loadLibrary(it) }
            return
        }
        libraryJobs[section]?.cancel()
        if (mutableState.value.selectedSection == section && mutableState.value.selectedContent == null)
            mutableState.value = mutableState.value.copy(isLoading = cached == null, error = null)
        if (kind == ContentKind.PLAYLIST) mutableState.value = mutableState.value.copy(playlistSyncFailed = false)
        val generation = accountGeneration
        libraryJobs[section] = viewModelScope.launch {
            var hasCachedItems = cached != null
            try {
                if (kind == ContentKind.PLAYLIST && !hasCachedItems) {
                    val saved = container.repository.cachedPlaylists()
                    if (generation != accountGeneration) return@launch
                    if (saved != null) {
                        hasCachedItems = true
                        publishLibrary(section, saved)
                        if (warmOthers) LibrarySection.entries.filter { it.kind != null && it != section }.forEach { loadLibrary(it) }
                    }
                }
                val items = container.repository.library(kind, forceRefresh, refreshOwnerNames)
                if (generation != accountGeneration) return@launch
                publishLibrary(section, items)
                if (kind == ContentKind.PLAYLIST) mutableState.value = mutableState.value.copy(playlistSyncFailed = false)
                if (warmOthers) LibrarySection.entries.filter { it.kind != null && it != section }.forEach { loadLibrary(it) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                if (generation != accountGeneration) return@launch
                if (kind == ContentKind.PLAYLIST && hasCachedItems && exception !is BrowserAuthorizationRequiredException &&
                    exception !is LoginVerificationRequiredException && exception !is AuthException) {
                    mutableState.value = mutableState.value.copy(playlistSyncFailed = true,
                        isLoading = if (mutableState.value.selectedSection == section && mutableState.value.selectedContent == null)
                            false else mutableState.value.isLoading)
                } else if (mutableState.value.selectedSection == section &&
                    mutableState.value.selectedContent == null) handleRequestFailure(exception)
            }
        }
    }

    private fun publishLibrary(section: LibrarySection, source: List<MusicContent>) {
        val items = if (section == LibrarySection.PLAYLISTS)
            listOf(io.github.playmusic.data.api.MusicRepository.likedSongsContent(container.likedSongsTitle)) +
                source.filterNot(io.github.playmusic.data.api.MusicRepository::isLikedSongs) else source
        val current = mutableState.value
        val libraries = current.libraries + (section to items)
        mutableState.value = current.copy(libraries = libraries, suggestedItems = librarySuggestions(libraries),
            items = if (current.selectedSection == section) visibleItems(section, items) else current.items,
            isLoading = if (current.selectedSection == section && current.selectedContent == null) false else current.isLoading)
        mutableState.value = mutableState.value.copy(searchSuggestions = searchSuggestions(mutableState.value.searchQuery))
    }

    fun prefetchDetails(contents: List<MusicContent>) {
        val current = mutableState.value
        detailPrefetcher.update(if (current.isLoggedIn && !current.isAuthorizing) contents else emptyList())
    }

    private fun cancelBrowsing() {
        accountGeneration++
        contentRequestJob?.cancel()
        libraryJobs.values.forEach { it.cancel() }
        libraryJobs.clear()
        detailPrefetcher.cancel()
        searchCache.clear()
        actionsJob?.cancel()
    }

    private fun executePlaybackRequest(block: suspend () -> Unit): Job {
        playbackRequestJob?.cancel()
        return executeRequest(showLoading = false) {
            block()
            val playback = container.localPlayback.snapshot()
            mutableState.value = mutableState.value.copy(playback = playback)
        }.also { playbackRequestJob = it }
    }

    private fun executeRequest(showLoading: Boolean = true, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            if (showLoading) mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                block()
                if (showLoading) mutableState.value = mutableState.value.copy(isLoading = false)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                handleRequestFailure(exception, showLoading)
            }
        }

    private fun handleRequestFailure(exception: Exception, showLoading: Boolean = true) {
        if (exception is BrowserAuthorizationRequiredException ||
            exception is AuthException && exception.requiresLogin) {
            mutableState.value = mutableState.value.copy(isLoading = false, error = UiError(ErrorKind.LOGIN_REQUIRED))
        } else if (exception is LoginVerificationRequiredException) {
            mutableState.value = mutableState.value.copy(isLoading = false, error = null,
                loginPending = LoginPending(exception.username, container.sessionStore.loadDeviceId(),
                    exception.challenge.loginContext, exception.challenge.maskedTarget))
        } else mutableState.value = mutableState.value.copy(
            isLoading = if (showLoading) false else mutableState.value.isLoading,
            error = UiError(ErrorKind.REQUEST, exception.message))
    }

    override fun onCleared() {
        cancelBrowsing()
        authorizationGeneration++
        pendingAuthorization?.close()
        container.stopLoginWaiting()
    }

    fun markBrowserOpened() {
        val pending = mutableState.value.browserAuthorization ?: return
        mutableState.value = mutableState.value.copy(browserAuthorization = pending.copy(browserOpened = true))
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return PlayViewModel(container) as T
        }
    }
}
