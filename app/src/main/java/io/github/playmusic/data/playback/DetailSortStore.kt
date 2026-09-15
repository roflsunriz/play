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
import io.github.playmusic.data.model.DetailSort
import org.json.JSONObject

/** Remembers the track order chosen inside playlist and album details. */
@OptIn(FlowPreview::class)
class DetailSortStore(context: Context) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow(DetailSortState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Boolean>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        mutableState.value = read().copy(isReady = true)
        writes.receiveAsFlow().debounce { if (it) 0L else 150L }.collect { persistNow() }
    }

    fun setSettings(settings: DetailSortSettings) {
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
    private fun read(): DetailSortState = try {
        val text = preferences.getString(KEY, null)
        if (text == null) DetailSortState() else try {
            require(text.length <= 4_096)
            DetailSortState(settings = decode(text))
        } catch (_: Exception) {
            DetailSortState(storageFailed = !preferences.edit().remove(KEY).commit())
        }
    } catch (_: Exception) { DetailSortState(storageFailed = true) }

    companion object {
        const val PREFERENCES = "play_detail_sort"
        internal const val KEY = "settings"
        internal fun encode(value: DetailSortSettings): String = JSONObject().put("schemaVersion", 1)
            .put("playlistDetailSort", value.playlistDetailSort.name)
            .put("albumDetailSort", value.albumDetailSort.name).toString()

        internal fun decode(text: String): DetailSortSettings = JSONObject(text).let {
            require(it.getInt("schemaVersion") == 1)
            DetailSortSettings(decodeSort(it, "playlistDetailSort", DetailSort.ADDED_NEWEST),
                decodeSort(it, "albumDetailSort", DetailSort.TRACK_ORDER))
        }

        private fun decodeSort(root: JSONObject, key: String, default: DetailSort): DetailSort =
            root.optString(key).takeIf { it.isNotBlank() }?.let { name ->
                runCatching { DetailSort.valueOf(name) }.getOrNull()
            } ?: default
    }
}

data class DetailSortSettings(
    val playlistDetailSort: DetailSort = DetailSort.ADDED_NEWEST,
    val albumDetailSort: DetailSort = DetailSort.TRACK_ORDER,
)

data class DetailSortState(
    val settings: DetailSortSettings = DetailSortSettings(),
    val isReady: Boolean = false,
    val storageFailed: Boolean = false,
)
