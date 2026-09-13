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
        if (mutableState.value.isLoggedIn) refreshAll()
    }

    fun beginBrowserLogin(completedMessage: String) {
        if (mutableState.value.isAuthorizing) return
        val generation = ++authorizationGeneration
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
                refreshAll()
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
        refreshAll()
    }

    fun selectSection(section: LibrarySection) {
        if (section == mutableState.value.selectedSection && mutableState.value.selectedContent == null) return
        contentRequestJob?.cancel()
        detailHistory.clear()
        mutableState.value = mutableState.value.copy(selectedSection = section, items = emptyList(), isLoading = false,
            error = null, selectedContent = null, detail = null)
        if (section != LibrarySection.SEARCH) loadLibrary(section)
    }

    fun updateSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    fun search() {
        contentRequestJob?.cancel()
        val query = mutableState.value.searchQuery
        contentRequestJob = executeRequest {
            val items = container.repository.search(query)
            mutableState.value = mutableState.value.copy(items = items)
        }
    }

    fun refreshAll() {
        val selectedContent = mutableState.value.selectedContent
        if (selectedContent != null) loadDetail(selectedContent, rememberCurrent = false)
        else if (mutableState.value.selectedSection == LibrarySection.SEARCH) search()
        else loadLibrary(mutableState.value.selectedSection)
        refreshPlayback()
    }

    fun openDetail(content: SpotifyContent) = loadDetail(content, rememberCurrent = true)

    private fun loadDetail(content: SpotifyContent, rememberCurrent: Boolean) {
        contentRequestJob?.cancel()
        val current = mutableState.value.detail
        if (rememberCurrent && current != null && current.content.uri != content.uri) detailHistory.addLast(current)
        mutableState.value = mutableState.value.copy(selectedContent = content, detail = null, error = null)
        contentRequestJob = executeRequest {
            val detail = container.repository.detail(content)
            mutableState.value = mutableState.value.copy(detail = detail)
        }
    }

    fun closeDetail() {
        contentRequestJob?.cancel()
        val previous = detailHistory.removeLastOrNull()
        mutableState.value = mutableState.value.copy(selectedContent = previous?.content, detail = previous,
            isLoading = false, error = null)
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
                val current = mutableState.value
                val items = if (current.items.any { it.uri == updated.uri }) {
                    current.items.map { if (it.uri == updated.uri) updated else it }
                } else if (current.selectedSection == LibrarySection.PLAYLISTS) listOf(updated) + current.items else current.items
                mutableState.value = current.copy(playlistEditor = null, items = items)
                loadDetail(updated, rememberCurrent = false)
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
                mutableState.value = mutableState.value.copy(playlistToDelete = null, isDeletingPlaylist = false,
                    selectedContent = null, detail = null, items = mutableState.value.items.filterNot { it.uri == content.uri })
                refreshAll()
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
        authorizationGeneration++
        contentRequestJob?.cancel()
        browserAuthorizationJob?.cancel()
        pendingAuthorization?.close()
        pendingAuthorization = null
        container.stopLoginWaiting()
        legacyLoginJob?.cancel()
        playbackRequestJob?.cancel()
        playbackSnapshotJob?.cancel()
        val clearFailure = runCatching { container.sessionManager.clearSession() }.exceptionOrNull()
        playlistWriteJob?.cancel()
        playlistImageJob?.cancel()
        playlistEditorGeneration++
        detailHistory.clear()
        mutableState.value = if (clearFailure == null) PlayUiState()
            else mutableState.value.copy(isAuthorizing = false, browserAuthorization = null, loginPending = null,
                isLoading = false, error = UiError(ErrorKind.REQUEST, clearFailure.message))
        viewModelScope.launch {
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

    private fun loadLibrary(section: LibrarySection) {
        val kind = section.kind ?: return
        contentRequestJob?.cancel()
        contentRequestJob = executeRequest {
            val items = container.repository.library(kind)
            mutableState.value = mutableState.value.copy(items = items)
        }
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
                if (exception is BrowserAuthorizationRequiredException) {
                    mutableState.value = mutableState.value.copy(isLoading = false, error = UiError(ErrorKind.LOGIN_REQUIRED))
                    return@launch
                }
                if (exception is LoginVerificationRequiredException) {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = null,
                        loginPending = LoginPending(
                            username = exception.username,
                            deviceId = container.sessionStore.loadDeviceId(),
                            loginContext = exception.challenge.loginContext,
                            maskedTarget = exception.challenge.maskedTarget,
                        ),
                    )
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = if (showLoading) false else mutableState.value.isLoading,
                    error = UiError(ErrorKind.REQUEST, exception.message),
                )
            }
        }

    override fun onCleared() {
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
