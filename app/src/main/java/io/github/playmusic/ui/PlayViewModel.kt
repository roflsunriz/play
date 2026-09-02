package io.github.playmusic.ui

import android.net.Uri
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
    CLIENT_ID_REQUIRED,
    NO_ACTIVE_DEVICE,
    LOGIN,
    REQUEST,
}

data class UiError(val kind: ErrorKind, val detail: String? = null)

data class PlayUiState(
    val clientId: String = "",
    val isLoggedIn: Boolean = false,
    val selectedSection: LibrarySection = LibrarySection.PLAYLISTS,
    val items: List<SpotifyContent> = emptyList(),
    val playback: Playback = Playback(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val error: UiError? = null,
)

class PlayViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PlayUiState(
            clientId = container.sessionStore.loadClientId(),
            isLoggedIn = container.sessionStore.loadSession() != null,
        ),
    )
    val state: StateFlow<PlayUiState> = mutableState.asStateFlow()

    init {
        if (mutableState.value.isLoggedIn) refreshAll()
    }

    fun beginLogin(clientId: String): Uri? {
        val normalizedClientId = clientId.trim()
        if (normalizedClientId.isBlank()) {
            mutableState.value = mutableState.value.copy(error = UiError(ErrorKind.CLIENT_ID_REQUIRED))
            return null
        }
        return runCatching {
            container.sessionStore.saveClientId(normalizedClientId)
            val attempt = container.oauthCoordinator.prepare(normalizedClientId)
            mutableState.value = mutableState.value.copy(clientId = normalizedClientId, isLoading = true, error = null)
            viewModelScope.launch {
                try {
                    val session = container.oauthCoordinator.complete(attempt)
                    container.sessionStore.saveSession(session)
                    mutableState.value = mutableState.value.copy(isLoggedIn = true, isLoading = false)
                    refreshAll()
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = UiError(ErrorKind.LOGIN, exception.message),
                    )
                }
            }
            attempt.authorizationUri
        }.getOrElse { exception ->
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                error = UiError(ErrorKind.LOGIN, exception.message),
            )
            null
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
        container.sessionStore.clearSession()
        mutableState.value = PlayUiState(clientId = container.sessionStore.loadClientId())
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun reportLaunchFailure(message: String?) {
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
