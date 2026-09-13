package io.github.playmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.playmusic.AppContainer
import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.auth.LoginVerificationRequiredException
import io.github.playmusic.data.auth.SpotifyAuthException
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    val items: List<SpotifyContent> = emptyList(),
    val playback: Playback = Playback(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val libraryQuery: String = "",
    val albumQuery: String = "",
    val trackQuery: String = "",
    val playlistSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val albumSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val trackSort: LibrarySort = LibrarySort.LIBRARY_ORDER,
    val libraries: Map<LibrarySection, List<SpotifyContent>> = emptyMap(),
    val suggestedItems: List<SpotifyContent> = emptyList(),
    val searchSuggestions: List<SpotifyContent> = emptyList(),
    val searchResults: List<SpotifyContent> = emptyList(),
    val searchPreviewFailed: Boolean = false,
    val playlistSyncFailed: Boolean = false,
    val error: UiError? = null,
    val loginPending: LoginPending? = null,
    val browserAuthorization: BrowserAuthorizationUi? = null,
    val isAuthorizing: Boolean = false,
    val isBrowserAuthorized: Boolean = false,
    val selectedContent: SpotifyContent? = null,
    val detail: ContentDetail? = null,
    val playlistEditor: PlaylistEditorState? = null,
    val playlistToDelete: SpotifyContent? = null,
    val isDeletingPlaylist: Boolean = false,
    val playlistDeletionFailed: Boolean = false,
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
    private var contentRequestJob: Job? = null
    private val libraryJobs = mutableMapOf<LibrarySection, Job>()
    private val detailPrefetcher = DetailPrefetcher(viewModelScope,
        isCached = { container.repository.peekDetail(it) != null },
        load = { container.repository.detail(it) })
    private var accountGeneration = 0L
    private val searchCache = object : LinkedHashMap<String, List<SpotifyContent>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SpotifyContent>>?): Boolean = size > 24
    }
    private var browserAuthorizationJob: Job? = null
    private var pendingAuthorization: BrowserAuthorizationClient.Pending? = null
    private var authorizationGeneration = 0L
    private var legacyLoginJob: Job? = null
    private var playbackRequestJob: Job? = null
    private var playbackSnapshotJob: Job? = null
    private val detailHistory = ArrayDeque<ContentDetail>()
    private var playlistWriteJob: Job? = null
    private var playlistImageJob: Job? = null
    private var playlistEditorGeneration = 0L

    init {
        viewModelScope.launch { container.localPlayback.state.collect { mutableState.value = mutableState.value.copy(playback = it) } }
        viewModelScope.launch { container.localPlayback.errors.collect {
            if (mutableState.value.isLoggedIn) mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.REQUEST, it))
        } }
        viewModelScope.launch { container.repository.playlistCacheFailures.collect {
            if (mutableState.value.isLoggedIn) mutableState.value = mutableState.value.copy(playlistSyncFailed = true)
        } }
        if (mutableState.value.isLoggedIn) {
            prefetchLibraries()
            refreshPlayback()
        }
    }

    fun beginBrowserLogin(completedMessage: String) {
        if (mutableState.value.isAuthorizing) return
        val generation = ++authorizationGeneration
        cancelBrowsing()
        contentRequestJob?.cancel()
        legacyLoginJob?.cancel()
        playbackRequestJob?.cancel()
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
                val credential = session?.storedCredential ?: throw SpotifyAuthException("Saved login information is unavailable")
                val outcome = container.login5Client.loginWithStoredCredentialAndCode(
                    username = pending.username, storedCredential = credential, deviceId = pending.deviceId,
                    code = trimmed, loginContext = pending.loginContext,
                )
                when (outcome) {
                    is io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.Success -> completeLogin(outcome)
                    is io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.CodeChallengeRequired -> {
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
        success: io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.Success,
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
    }

    fun selectSection(section: LibrarySection) {
        if (section == mutableState.value.selectedSection && mutableState.value.selectedContent == null) return
        contentRequestJob?.cancel()
        detailPrefetcher.update(emptyList())
        detailHistory.clear()
        val current = mutableState.value
        val cached = current.libraries[section] ?: section.kind?.let(container.repository::peekLibrary)
        mutableState.value = current.copy(selectedSection = section,
            items = if (section == LibrarySection.SEARCH) current.searchResults else visibleItems(section, cached.orEmpty()),
            isLoading = cached == null && section != LibrarySection.SEARCH,
            error = null, selectedContent = null, detail = null)
        if (section != LibrarySection.SEARCH) loadLibrary(section)
        else if (current.searchQuery.isNotBlank() && !searchCache.containsKey(current.searchQuery.trim())) search()
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

    fun updateSearchQuery(query: String) {
        contentRequestJob?.cancel()
        val cached = searchCache[query.trim()].orEmpty()
        mutableState.value = mutableState.value.copy(searchQuery = query, searchResults = cached,
            items = cached, isLoading = false, searchPreviewFailed = false, searchSuggestions = searchSuggestions(query))
        if (query.isNotBlank()) search(waitForTyping = true)
    }

    fun search() = search(waitForTyping = false)

    private fun search(waitForTyping: Boolean, forceRefresh: Boolean = false) {
        contentRequestJob?.cancel()
        val query = mutableState.value.searchQuery.trim()
        if (query.isBlank()) return
        val cached = searchCache[query]
        if (cached != null && !forceRefresh) {
            mutableState.value = mutableState.value.copy(items = cached, searchResults = cached, isLoading = false)
            return
        }
        val generation = accountGeneration
        contentRequestJob = viewModelScope.launch {
            if (waitForTyping) delay(350)
            mutableState.value = mutableState.value.copy(isLoading = true, error = null, searchPreviewFailed = false)
            try {
                val items = container.repository.search(query)
                if (generation == accountGeneration && mutableState.value.searchQuery.trim() == query &&
                    mutableState.value.selectedSection == LibrarySection.SEARCH) {
                    searchCache[query] = items
                    mutableState.value = mutableState.value.copy(items = items, searchResults = items, isLoading = false)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Typing previews keep local matches useful when the network is unavailable.
                if (!waitForTyping) handleRequestFailure(exception)
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

    fun openDetail(content: SpotifyContent) = loadDetail(content, rememberCurrent = true)

    private fun loadDetail(content: SpotifyContent, rememberCurrent: Boolean, forceRefresh: Boolean = false) {
        contentRequestJob?.cancel()
        val current = mutableState.value.detail
        if (rememberCurrent && current != null && current.content.uri != content.uri) detailHistory.addLast(current)
        val cached = container.repository.peekDetail(content)
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
            var written: SpotifyContent? = null
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

    fun play(content: SpotifyContent) {
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
                ContentKind.ALBUM, ContentKind.PLAYLIST ->
                    (detail?.takeIf { it.content.uri == content.uri } ?: container.repository.detail(content)).tracks
                else -> throw IllegalArgumentException("Unsupported playback item")
            }.filter { it.isPlayable != false }
            check(tracks.isNotEmpty()) { "No playable tracks are available" }
            val index = if (content.kind == ContentKind.TRACK) tracks.indexOfFirst { it.uri == content.uri }.coerceAtLeast(0) else 0
            container.localPlayback.play(tracks, index)
        }
    }

    fun playDetailTrack(index: Int) {
        val source = mutableState.value.detail?.tracks ?: return
        if (index !in source.indices || source[index].isPlayable == false) return
        val tracks = source.filter { it.isPlayable != false }
        val selectedIndex = source.take(index).count { it.isPlayable != false }
        executePlaybackRequest { container.localPlayback.play(tracks, selectedIndex) }
    }

    fun togglePlayPause() = executePlaybackRequest {
        if (mutableState.value.playback.playWhenReady) container.localPlayback.pause() else container.localPlayback.resume()
    }

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
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun reportLoginFailure(message: String?) {
        cancelBrowserLogin()
        mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.LOGIN, message))
    }

    private fun prefetchLibraries() {
        val section = mutableState.value.selectedSection.takeUnless { it == LibrarySection.SEARCH } ?: LibrarySection.PLAYLISTS
        loadLibrary(section, forceRefresh = true, warmOthers = true, refreshOwnerNames = false)
    }

    private fun visibleItems(section: LibrarySection, source: List<SpotifyContent>): List<SpotifyContent> {
        val current = mutableState.value
        return presentLibrary(source, current.queryFor(section), current.sortFor(section))
    }

    private fun updateVisibleItems() {
        val current = mutableState.value
        mutableState.value = current.copy(items = if (current.selectedSection == LibrarySection.SEARCH) current.searchResults
            else visibleItems(current.selectedSection, current.libraries[current.selectedSection].orEmpty()))
    }

    private fun searchSuggestions(query: String): List<SpotifyContent> = if (query.isBlank()) emptyList() else
        presentLibrary(mutableState.value.libraries.values.flatten().distinctBy { it.uri }, query, LibrarySort.LIBRARY_ORDER).take(6)

    private fun updatePlaylistInLibrary(content: SpotifyContent) {
        val current = mutableState.value
        val old = current.libraries[LibrarySection.PLAYLISTS].orEmpty()
        val updated = if (old.any { it.uri == content.uri }) old.map { if (it.uri == content.uri) content else it }
            else listOf(content) + old
        val libraries = current.libraries + (LibrarySection.PLAYLISTS to updated)
        mutableState.value = current.copy(libraries = libraries, suggestedItems = librarySuggestions(libraries),
            searchResults = current.searchResults.map { if (it.uri == content.uri) content else it })
        updateVisibleItems()
    }

    private fun loadLibrary(section: LibrarySection, forceRefresh: Boolean = false, warmOthers: Boolean = false,
        refreshOwnerNames: Boolean = forceRefresh) {
        val kind = section.kind ?: return
        if (libraryJobs[section]?.isActive == true && !forceRefresh) return
        val cached = mutableState.value.libraries[section] ?: container.repository.peekLibrary(kind)
        if (cached != null) publishLibrary(section, cached)
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
                    exception !is LoginVerificationRequiredException && exception !is SpotifyAuthException) {
                    mutableState.value = mutableState.value.copy(playlistSyncFailed = true,
                        isLoading = if (mutableState.value.selectedSection == section && mutableState.value.selectedContent == null)
                            false else mutableState.value.isLoading)
                } else if (mutableState.value.selectedSection == section &&
                    mutableState.value.selectedContent == null) handleRequestFailure(exception)
            }
        }
    }

    private fun publishLibrary(section: LibrarySection, items: List<SpotifyContent>) {
        val current = mutableState.value
        val libraries = current.libraries + (section to items)
        mutableState.value = current.copy(libraries = libraries, suggestedItems = librarySuggestions(libraries),
            items = if (current.selectedSection == section) visibleItems(section, items) else current.items,
            isLoading = if (current.selectedSection == section && current.selectedContent == null) false else current.isLoading)
        mutableState.value = mutableState.value.copy(searchSuggestions = searchSuggestions(mutableState.value.searchQuery))
    }

    fun prefetchDetails(contents: List<SpotifyContent>) {
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
        if (exception is BrowserAuthorizationRequiredException) {
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
