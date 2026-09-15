package io.github.playmusic.data.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PlaybackTransitionStoreTest {
    @Test fun settingsPersistOffMainAndFailuresRecoverWithoutTouchingOtherData(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val name = "transition-test-${UUID.randomUUID()}"
        val mainAccesses = AtomicInteger()
        var failWrites = false
        val stores = mutableListOf<PlaybackTransitionStore>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences {
                if (Looper.myLooper() == Looper.getMainLooper()) mainAccesses.incrementAndGet()
                val actual = base.getSharedPreferences(name, mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        if (Looper.myLooper() == Looper.getMainLooper()) mainAccesses.incrementAndGet()
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                            override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                            override fun commit(): Boolean = !failWrites && editor.commit()
                        }
                    }
                }
            }
        }
        suspend fun open() = PlaybackTransitionStore(context).also { store ->
            stores.add(store); withTimeout(5_000) { store.state.first { it.isReady } }
        }
        try {
            val store = open()
            val settings = PlaybackTransitionSettings(false, 12, false, 1, true, 8, true, false)
            instrumentation.runOnMainSync { store.setSettings(settings) }
            assertTrue(store.persistNow())
            assertEquals(settings, open().state.value.settings)
            failWrites = true
            store.setSettings(settings.copy(fadeOutSeconds = 11))
            assertFalse(store.persistNow())
            assertTrue(store.state.value.storageFailed)
            assertEquals(11, store.state.value.settings.fadeOutSeconds)
            failWrites = false
            assertTrue(store.persistNow())
            assertEquals(11, open().state.value.settings.fadeOutSeconds)
            stores.forEach { it.close() }
            val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("unrelated", "keep").putString(PlaybackTransitionStore.KEY, "{broken").commit())
            assertEquals(PlaybackTransitionSettings(), open().state.value.settings)
            assertNull(prefs.getString(PlaybackTransitionStore.KEY, null))
            assertEquals("keep", prefs.getString("unrelated", null))
            assertEquals(0, mainAccesses.get())
        } finally { stores.forEach { it.close() }; base.deleteSharedPreferences(name) }
    }
}
