package io.github.playmusic.data.audio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AudioEffectsStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val name = "audio_effects_test_${java.util.UUID.randomUUID()}"
    private val stores = mutableListOf<AudioEffectsStore>()
    @Volatile private var failWrites = false
    @Volatile private var watchMain = false
    private val mainAccesses = AtomicInteger()
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences {
            val real = base.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun getString(key: String?, default: String?): String? {
                    checkThread()
                    return real.getString(key, default)
                }
                override fun edit(): SharedPreferences.Editor {
                    checkThread()
                    val editor = real.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun commit(): Boolean { checkThread(); return !failWrites && editor.commit() }
                    }
                }
            }
        }
    }

    private fun checkThread() {
        if (watchMain && Looper.myLooper() == Looper.getMainLooper()) mainAccesses.incrementAndGet()
    }

    @After fun cleanup() = runBlocking {
        stores.forEach { it.close() }
        base.deleteSharedPreferences(name)
        Unit
    }

    @Test fun allFiveSlotsCanBeSavedReplacedRenamedDeletedAndRestored() = runBlocking {
        val store = open()
        for (slot in 0 until EqualizerBands.SLOT_COUNT) {
            store.setSettings(curve(slot))
            assertTrue(store.saveSlot(slot, "調整 ${slot + 1}"))
        }
        store.setSettings(curve(5))
        assertTrue(store.saveSlot(4, "高域"))
        val preserved = store.state.value.slots[2]!!.settings
        assertTrue(store.renameSlot(2, "声"))
        assertEquals(preserved, store.state.value.slots[2]!!.settings)
        store.deleteSlot(1)
        store.loadSlot(0)
        assertEquals(curve(0), store.state.value.settings)
        assertTrue(store.persistNow())
        val expected = store.state.value
        store.close()
        val reopened = open().state.value
        assertEquals(expected, reopened)
        assertEquals(5, reopened.slots.size)
        assertNull(reopened.slots[1])
        assertEquals(curve(5), reopened.slots[4]!!.settings)
    }

    @Test fun emptyInvalidAndExcessSlotsCannotAlterSavedSettings() = runBlocking {
        val store = open()
        assertFalse(store.saveSlot(0, "  "))
        assertFalse(store.saveSlot(0, "x".repeat(41)))
        assertFalse(store.saveSlot(0, "line\nbreak"))
        assertFalse(store.saveSlot(-1, "Outside"))
        assertFalse(store.saveSlot(5, "Outside"))
        assertTrue(store.state.value.slots.all { it == null })
        assertTrue(store.saveSlot(4, "🎵".repeat(40)))
        assertFalse(store.renameSlot(4, ""))
        assertEquals("🎵".repeat(40), store.state.value.slots[4]!!.name)
        assertFalse(store.renameSlot(1, "Missing"))
    }

    @Test fun brokenUnknownAndOutOfRangeStoredDataRecoverOnlyTheEffectsState() = runBlocking {
        val seed = open()
        seed.setSettings(curve(2))
        assertTrue(seed.persistNow())
        seed.close()
        val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        val valid = checkNotNull(prefs.getString(AudioEffectsStore.STATE_KEY, null))
        val invalid = listOf("{broken", "x".repeat(32_769),
            JSONObject(valid).put("schemaVersion", 9).toString(),
            JSONObject(valid).put("slots", JSONArray()).toString(),
            JSONObject(valid).apply { getJSONObject("settings").put("preampDb", 11) }.toString(),
            JSONObject(valid).apply { getJSONObject("settings").getJSONArray("bandGainsDb").put(29, -13) }.toString())
        for (payload in invalid) {
            assertTrue(prefs.edit().putString("unrelated", "keep").putString(AudioEffectsStore.STATE_KEY, payload).commit())
            val recovered = open()
            assertEquals(EqualizerSettings(), recovered.state.value.settings)
            assertTrue(recovered.state.value.slots.all { it == null })
            assertFalse(recovered.state.value.storageFailed)
            assertNull(prefs.getString(AudioEffectsStore.STATE_KEY, null))
            assertEquals("keep", prefs.getString("unrelated", null))
            recovered.close()
        }
    }

    @Test fun failedPersistenceKeepsTheCurrentCurveAndSlotsForRetry() = runBlocking {
        val store = open()
        failWrites = true
        store.setSettings(curve(3))
        store.saveSlot(3, "残す設定")
        assertFalse(store.persistNow())
        assertTrue(store.state.value.storageFailed)
        assertEquals(curve(3), store.state.value.settings)
        assertEquals("残す設定", store.state.value.slots[3]!!.name)
        failWrites = false
        store.requestSave()
        withTimeout(5_000) { store.state.first { !it.storageFailed } }
        assertEquals(store.state.value.settings, open().state.value.settings)
    }

    @Test fun rapidUiChangesStayOffDiskUntilTheIoWriterAndPersistTheLastValue() = runBlocking {
        val store = open()
        watchMain = true
        instrumentation.runOnMainSync {
            repeat(200) { index -> store.setSettings(EqualizerSettings(true, (index % 41 - 20) / 2f)) }
        }
        val last = store.state.value.settings
        assertTrue(store.persistNow())
        assertEquals(0, mainAccesses.get())
        assertEquals(last, open().state.value.settings)
    }

    @Test fun ordinaryUpdatesAutomaticallySaveAllSlotsAndTheNewestCurve() = runBlocking {
        val store = open()
        repeat(5) { index -> store.setSettings(curve(index)); store.saveSlot(index, "自動 ${index + 1}") }
        val latest = curve(1).copy(preampDb = 9.5f)
        store.setSettings(latest)
        val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        withTimeout(5_000) {
            while (!runCatching {
                val json = JSONObject(prefs.getString(AudioEffectsStore.STATE_KEY, null).orEmpty())
                json.getJSONObject("settings").getDouble("preampDb") == 9.5 &&
                    (0..4).all { !json.getJSONArray("slots").isNull(it) }
            }.getOrDefault(false)) delay(25)
        }
        store.close()
        val restored = open().state.value
        assertEquals(latest, restored.settings)
        assertEquals((1..5).map { "自動 $it" }, restored.slots.map { it?.name })
    }

    private suspend fun open(): AudioEffectsStore = AudioEffectsStore(context).also {
        stores += it
        withTimeout(5_000) { it.state.first { state -> state.isReady } }
    }

    private fun curve(index: Int) = EqualizerSettings(true, -10f + index * 2f,
        List(EqualizerBands.COUNT) { if (it == 29) 12f else (it % 9 - 4).toFloat() })
}
