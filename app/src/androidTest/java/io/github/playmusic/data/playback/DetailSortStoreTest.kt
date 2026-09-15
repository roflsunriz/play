package io.github.playmusic.data.playback

import android.content.Context
import android.content.ContextWrapper
import io.github.playmusic.data.model.DetailSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DetailSortStoreTest {
    @Test fun selectionsPersistAndUnknownValuesFallBackToDefaults(): Unit = runBlocking {
        val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val name = "detail-sort-test-${UUID.randomUUID()}"
        val stores = mutableListOf<DetailSortStore>()
        suspend fun open(): DetailSortStore {
            val context = object : ContextWrapper(base) {
                override fun getSharedPreferences(ignored: String, mode: Int) =
                    base.getSharedPreferences(name, mode)
            }
            return DetailSortStore(context).also { stores.add(it); withTimeout(5_000) { it.state.first { ready -> ready.isReady } } }
        }
        try {
            val store = open()
            assertEquals(DetailSortSettings(), store.state.value.settings)
            val settings = DetailSortSettings(DetailSort.TITLE, DetailSort.PLAYCOUNT)
            store.setSettings(settings)
            assertTrue(store.persistNow())
            assertEquals(settings, open().state.value.settings)
            val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString(DetailSortStore.KEY,
                """{"schemaVersion":1,"playlistDetailSort":"TITLE","albumDetailSort":"UNKNOWN_FUTURE"}""").commit())
            assertEquals(DetailSortSettings(DetailSort.TITLE, DetailSort.TRACK_ORDER), open().state.value.settings)
            assertTrue(prefs.edit().putString(DetailSortStore.KEY, "{broken").commit())
            assertEquals(DetailSortSettings(), open().state.value.settings)
        } finally {
            stores.forEach { it.close() }
            base.deleteSharedPreferences(name)
        }
    }
}
