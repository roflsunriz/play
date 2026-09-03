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

data class PlayUiState(
    val username: String = "",
    val isLoggedIn: Boolean = false,
    val selectedSection: LibrarySection = LibrarySection.PLAYLISTS,
    val items: List<SpotifyContent> = emptyList(),
    val playback: Playback = Playback(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val error: UiError? = null,
    val diagnosticsReport: String? = null,
)

private data class Combo(
    val label: String,
    val clientId: String,
    val userAgent: String,
    val clientVersion: String,
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
                val success = container.login5Client.loginWithPassword(
                    username = normalizedUsername,
                    password = password,
                    deviceId = container.sessionStore.loadDeviceId(),
                )
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
                )
                refreshAll()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = UiError(ErrorKind.LOGIN, exception.message),
                )
            }
        }
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

    fun runDiagnostics() {
        mutableState.value = mutableState.value.copy(diagnosticsReport = "Running diagnostics...", isLoading = true)
        viewModelScope.launch {
            val report = buildString {
                appendLine("== Play Diagnostics ==")
                val deviceId = container.sessionStore.loadDeviceId()
                appendLine("deviceId=$deviceId")
                val combos = listOf(
                    Combo("android-id+android-ua", AppContainer.SPOTIFY_CLIENT_ID, "Spotify/9.1.78.2218 Android/37 (Android 16)", "9.1.78.2218"),
                    Combo("keymaster-id+spotcontrol-ua+ver", AppContainer.CLIENT_TOKEN_CLIENT_ID, "spotcontrol/0.0.0 Go/1.0", "0.0.0"),
                    Combo("keymaster-id+spotcontrol-ua+realver", AppContainer.CLIENT_TOKEN_CLIENT_ID, "spotcontrol/0.0.0 Go/1.0", "9.1.78.2218"),
                    Combo("keymaster-id+android-ua", AppContainer.CLIENT_TOKEN_CLIENT_ID, "Spotify/9.1.78.2218 Android/37 (Android 16)", "9.1.78.2218"),
                )
                for ((label, clientId, userAgent, clientVersion) in combos) {
                    appendLine("-- client token ($label) --")
                    val result = runCatching {
                        container.acquireClientTokenWithUserAgent(clientId, deviceId, userAgent, clientVersion)
                    }
                    result.onSuccess {
                        appendLine("$label OK: ${it.token.take(24)}...")
                    }.onFailure {
                        appendLine("$label FAIL: ${it.message}")
                    }
                }
                appendLine("-- login5 (dummy credentials) --")
                val login = runCatching {
                    container.login5Client.loginWithPassword(
                        username = "dummyuser123",
                        password = "dummypass456",
                        deviceId = deviceId,
                    )
                }
                login.onSuccess {
                    appendLine("login5 OK: user=${it.username} token=${it.accessToken.take(24)}...")
                }.onFailure {
                    appendLine("login5 FAIL: ${it.message}")
                    appendLine(it.stackTraceToString())
                }
            }
            android.util.Log.w("PlayDiagnostics", report)
            mutableState.value = mutableState.value.copy(
                diagnosticsReport = report,
                isLoading = false,
            )
        }
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