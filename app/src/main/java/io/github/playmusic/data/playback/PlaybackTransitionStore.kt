package io.github.playmusic.data.playback

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only immutable snapshots are read by the audio thread. All disk access stays on IO. */
@OptIn(FlowPreview::class)
class PlaybackTransitionStore(context: Context) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow(PlaybackTransitionState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Boolean>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        mutableState.value = read().copy(isReady = true)
        writes.receiveAsFlow().debounce { if (it) 0L else 150L }.collect { persistNow() }
    }

    fun setSettings(settings: PlaybackTransitionSettings) {
        mutableState.update { if (it.isReady) it.copy(settings = settings) else it }
        writes.trySend(false)
    }
    fun requestSave() { writes.trySend(true) }

    @SuppressLint("ApplySharedPref", "UseKtx")
    suspend fun persistNow(): Boolean = withContext(Dispatchers.IO) {
        state.first { it.isReady }
        mutex.withLock {
            val value = state.value.settings
            val saved = try { preferences.edit().putString(KEY, encode(value)).commit() }
                catch (_: Exception) { false }
            mutableState.update { it.copy(storageFailed = !saved) }
            saved
        }
    }

    suspend fun close() { writes.close(); worker.cancelAndJoin() }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun read(): PlaybackTransitionState = try {
        val text = preferences.getString(KEY, null)
        if (text == null) PlaybackTransitionState() else try {
            require(text.length <= 4_096)
            PlaybackTransitionState(settings = decode(text))
        } catch (_: Exception) {
            PlaybackTransitionState(storageFailed = !preferences.edit().remove(KEY).commit())
        }
    } catch (_: Exception) { PlaybackTransitionState(storageFailed = true) }

    companion object {
        const val PREFERENCES = "play_playback_transitions"
        internal const val KEY = "settings"
        internal fun encode(value: PlaybackTransitionSettings): String = JSONObject().put("schemaVersion", 1)
            .put("fadeInEnabled", value.fadeInEnabled).put("fadeInSeconds", value.fadeInSeconds)
            .put("fadeOutEnabled", value.fadeOutEnabled).put("fadeOutSeconds", value.fadeOutSeconds)
            .put("crossfadeEnabled", value.crossfadeEnabled).put("crossfadeSeconds", value.crossfadeSeconds)
            .put("peakNormalizationEnabled", value.peakNormalizationEnabled).put("automixEnabled", value.automixEnabled)
            .put("seekCrossfadeEnabled", value.seekCrossfadeEnabled).put("seekCrossfadeSeconds", value.seekCrossfadeSeconds)
            .put("musicCacheGb", value.musicCacheGb).toString()

        internal fun decode(text: String): PlaybackTransitionSettings = JSONObject(text).let {
            require(it.getInt("schemaVersion") == 1)
            PlaybackTransitionSettings(it.getBoolean("fadeInEnabled"), it.getInt("fadeInSeconds"),
                it.getBoolean("fadeOutEnabled"), it.getInt("fadeOutSeconds"), it.getBoolean("crossfadeEnabled"),
                it.getInt("crossfadeSeconds"), it.getBoolean("peakNormalizationEnabled"), it.getBoolean("automixEnabled"),
                // Settings saved before the seek crossfade existed keep their behavior.
                it.optBoolean("seekCrossfadeEnabled", false), it.optInt("seekCrossfadeSeconds", 3),
                // Older saves predate the cache limit and take the current default.
                it.optInt("musicCacheGb", 20))
        }
    }
}
