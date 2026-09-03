package io.github.playmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.playmusic.AppContainer
import io.github.playmusic.data.api.SpotifyApiException
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.CancellationException
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
    CREDENTIALS_REQUIRED,
    NO_ACTIVE_DEVICE,
    LOGIN,
    REQUEST,
}

data class UiError(val kind: ErrorKind, val detail: String? = null)

data class LoginPending(
    val username: String,
    val password: String,
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
)

class PlayViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PlayUiState(
            username = container.sessionStore.loadSession()?.username.orEmpty(),
            isLoggedIn = container.sessionStore.loadSession() != null,
        ),
    )
    val state: StateFlow<PlayUiState> = mutableState.asStateFlow()

    init {
        if (mutableState.value.isLoggedIn) refreshAll()
    }

    fun beginLogin(username: String, password: String) {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank() || password.isBlank()) {
            mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.CREDENTIALS_REQUIRED))
            return
        }
        mutableState.value = mutableState.value.copy(username = normalizedUsername, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val deviceId = container.sessionStore.loadDeviceId()
                when (val outcome = container.login5Client.loginWithPassword(normalizedUsername, password, deviceId)) {
                    is io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.Success -> {
                        completeLogin(outcome)
                    }
                    is io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.CodeChallengeRequired -> {
                        mutableState.value = mutableState.value.copy(
                            isLoading = false,
                            loginPending = LoginPending(
                                username = normalizedUsername,
                                password = password,
                                deviceId = deviceId,
                                loginContext = outcome.loginContext,
                                maskedTarget = outcome.maskedTarget,
                            ),
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

    fun submitCode(code: String) {
        val pending = mutableState.value.loginPending ?: return
        val trimmed = code.trim()
        if (trimmed.isBlank()) return
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val outcome = container.login5Client.loginWithCode(
                    username = pending.username,
                    password = pending.password,
                    deviceId = pending.deviceId,
                    code = trimmed,
                    loginContext = pending.loginContext,
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
                            error = UiError(ErrorKind.LOGIN, "確認コードが正しくありません。新しいコードを入力してください。"),
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
        mutableState.value = mutableState.value.copy(loginPending = null, isLoading = false)
    }

    private fun completeLogin(
        success: io.github.playmusic.data.auth.SpotifyLogin5Client.LoginOutcome.Success,
    ) {
        val session = io.github.playmusic.data.model.AuthSession(
            username = success.username,
            accessToken = success.accessToken,
            storedCredential = success.storedCredential,
            expiresAtEpochMs = System.currentTimeMillis() + success.accessTokenExpiresIn * 1_000L,
        )
        container.sessionStore.saveSession(session)
        mutableState.value = mutableState.value.copy(
            username = success.username,
            isLoggedIn = true,
            isLoading = false,
            loginPending = null,
        )
        refreshAll()
    }

    fun selectSection(section: LibrarySection) {
        if (section == mutableState.value.selectedSection) return
        mutableState.value = mutableState.value.copy(selectedSection = section, items = emptyList())
        if (section != LibrarySection.SEARCH) loadLibrary(section)
    }

    fun updateSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    fun search() = executeRequest {
        mutableState.value = mutableState.value.copy(
            items = container.repository.search(mutableState.value.searchQuery),
        )
    }

    fun refreshAll() {
        loadLibrary(mutableState.value.selectedSection)
        refreshPlayback()
    }

    fun refreshPlayback() = executeRequest(showLoading = false) {
        mutableState.value = mutableState.value.copy(playback = container.repository.playback())
    }

    fun play(content: SpotifyContent) = executePlaybackRequest {
        container.repository.play(content)
    }

    fun preview(content: SpotifyContent) {
        val previewUrl = content.previewUrl ?: return
        if (content.kind != ContentKind.TRACK) return
        if (container.previewPlayer.isPlaying()) {
            container.previewPlayer.stop()
        } else {
            container.previewPlayer.play(previewUrl)
        }
    }

    fun togglePlayPause() = executePlaybackRequest {
        if (mutableState.value.playback.isPlaying) container.repository.pause() else container.repository.resume()
    }

    fun next() = executePlaybackRequest { container.repository.next() }

    fun previous() = executePlaybackRequest { container.repository.previous() }

    fun seek(positionMs: Long) = executePlaybackRequest { container.repository.seek(positionMs) }

    fun toggleShuffle() = executePlaybackRequest {
        container.repository.setShuffle(!mutableState.value.playback.shuffle)
    }

    fun cycleRepeat() = executePlaybackRequest {
        container.repository.setRepeat(mutableState.value.playback.repeatMode.next())
    }

    fun logout() {
        container.previewPlayer.stop()
        container.sessionStore.clearSession()
        mutableState.value = PlayUiState()
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun reportLoginFailure(message: String?) {
        mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.LOGIN, message))
    }

    private fun loadLibrary(section: LibrarySection) {
        val kind = section.kind ?: return
        executeRequest {
            mutableState.value = mutableState.value.copy(items = container.repository.library(kind))
        }
    }

    private fun executePlaybackRequest(block: suspend () -> Unit) = executeRequest(showLoading = false) {
        block()
        mutableState.value = mutableState.value.copy(playback = container.repository.playback())
    }

    private fun executeRequest(showLoading: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (showLoading) mutableState.value = mutableState.value.copy(isLoading = true)
            try {
                block()
                mutableState.value = mutableState.value.copy(isLoading = false, error = null)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val kind = if (exception is SpotifyApiException && exception.status == 404) {
                    ErrorKind.NO_ACTIVE_DEVICE
                } else {
                    ErrorKind.REQUEST
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = UiError(kind, exception.message),
                )
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return PlayViewModel(container) as T
        }
    }
}
