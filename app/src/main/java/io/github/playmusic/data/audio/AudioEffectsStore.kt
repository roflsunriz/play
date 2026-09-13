package io.github.playmusic.data.audio

import android.content.Context
import android.annotation.SuppressLint
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
import org.json.JSONArray
import org.json.JSONObject

/** Immediate in-memory controls, with serialized disk writes away from UI and audio threads. */
@OptIn(FlowPreview::class)
class AudioEffectsStore(context: Context) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow(AudioEffectsState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Boolean>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        mutableState.value = read().copy(isReady = true)
        writes.receiveAsFlow().debounce { immediate -> if (immediate) 0L else 150L }.collect { persistNow() }
    }

    fun setSettings(settings: EqualizerSettings) = change {
        it.copy(settings = settings.copy(bandGainsDb = settings.bandGainsDb.toList()))
    }

    fun loadSlot(index: Int) {
        val slot = state.value.slots.getOrNull(index) ?: return
        setSettings(slot.settings)
    }

    fun saveSlot(index: Int, name: String): Boolean {
        val trimmed = validName(name) ?: return false
        if (index !in 0 until EqualizerBands.SLOT_COUNT || !state.value.isReady) return false
        change(immediate = true) { current -> current.copy(slots = current.slots.toMutableList().apply {
            this[index] = EqualizerSlot(trimmed, current.settings)
        }.toList()) }
        return true
    }

    fun renameSlot(index: Int, name: String): Boolean {
        val trimmed = validName(name) ?: return false
        if (state.value.slots.getOrNull(index) == null) return false
        change(immediate = true) { current -> current.copy(slots = current.slots.mapIndexed { position, slot ->
            if (position == index) slot?.copy(name = trimmed) else slot
        }) }
        return true
    }

    fun deleteSlot(index: Int) {
        if (index !in 0 until EqualizerBands.SLOT_COUNT) return
        change(immediate = true) { current -> current.copy(slots = current.slots.mapIndexed { position, slot ->
            if (position == index) null else slot
        }) }
    }

    fun requestSave() { writes.trySend(true) }

    private fun change(immediate: Boolean = false, transform: (AudioEffectsState) -> AudioEffectsState) {
        mutableState.update { if (it.isReady) transform(it) else it }
        writes.trySend(immediate)
    }

    // A checked commit reports disk failures; both this method and recovery below run on IO.
    @SuppressLint("ApplySharedPref", "UseKtx")
    suspend fun persistNow(): Boolean = withContext(Dispatchers.IO) {
        state.first { it.isReady }
        writeMutex.withLock {
            val snapshot = state.value
            val saved = try { preferences.edit().putString(STATE_KEY, encode(snapshot)).commit() }
            catch (_: Exception) { false }
            mutableState.update { it.copy(storageFailed = !saved) }
            saved
        }
    }

    /** Used when an isolated container ends; does not clear the user's saved settings. */
    suspend fun close() { writes.close(); worker.cancelAndJoin() }

    @SuppressLint("UseKtx")
    private fun read(): AudioEffectsState = try {
        val encoded = preferences.getString(STATE_KEY, null)
        if (encoded == null) AudioEffectsState() else try {
            require(encoded.length <= 32_768)
            val json = JSONObject(encoded)
            require(json.getInt("schemaVersion") == 1)
            val slots = json.getJSONArray("slots")
            require(slots.length() == EqualizerBands.SLOT_COUNT)
            AudioEffectsState(settings = decodeSettings(json.getJSONObject("settings")),
                slots = List(EqualizerBands.SLOT_COUNT) { index ->
                    if (slots.isNull(index)) null else slots.getJSONObject(index).let {
                        EqualizerSlot(checkNotNull(validName(it.getString("name"))), decodeSettings(it.getJSONObject("settings")))
                    }
                })
        } catch (_: Exception) {
            AudioEffectsState(storageFailed = !preferences.edit().remove(STATE_KEY).commit())
        }
    } catch (_: Exception) { AudioEffectsState(storageFailed = true) }

    private fun encode(value: AudioEffectsState): String = JSONObject().put("schemaVersion", 1)
        .put("settings", encodeSettings(value.settings)).put("slots", JSONArray().apply {
            value.slots.forEach { slot -> put(if (slot == null) JSONObject.NULL else JSONObject()
                .put("name", slot.name).put("settings", encodeSettings(slot.settings))) }
        }).toString()

    private fun encodeSettings(value: EqualizerSettings) = JSONObject().put("enabled", value.enabled)
        .put("preampDb", value.preampDb).put("bandGainsDb", JSONArray(value.bandGainsDb))

    private fun decodeSettings(value: JSONObject): EqualizerSettings {
        val bands = value.getJSONArray("bandGainsDb")
        require(bands.length() == EqualizerBands.COUNT)
        return EqualizerSettings(value.getBoolean("enabled"), value.getDouble("preampDb").toFloat(),
            List(EqualizerBands.COUNT) { bands.getDouble(it).toFloat() })
    }

    private fun validName(name: String): String? = name.trim().takeIf {
        it.isNotBlank() && it.codePointCount(0, it.length) <= EqualizerBands.MAX_NAME_LENGTH &&
            it.none { character -> character.isISOControl() }
    }

    companion object {
        const val PREFERENCES = "play_audio_effects"
        internal const val STATE_KEY = "audio_effects_state"
    }
}
