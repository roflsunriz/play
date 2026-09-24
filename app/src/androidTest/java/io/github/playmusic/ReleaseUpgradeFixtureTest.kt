package io.github.playmusic

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.audio.AudioEffectsStore
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.cache.PlaylistCacheEntry
import io.github.playmusic.data.cache.PlaylistCacheSnapshot
import io.github.playmusic.data.cache.PlaylistDiskCache
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.security.SecureSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only an explicitly selected emulator: prepares visible state to inspect after a signed release upgrade. */
class ReleaseUpgradeFixtureTest {
    @Test fun prepareStateForInPlaceReleaseUpgrade(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE == "ranchu")
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepareReleaseUpgrade") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "release-upgrade-fixture"
        SecureSessionStore(context).saveSession(AuthSession(account, "synthetic-access", null, Long.MAX_VALUE, "synthetic-refresh"))
        val effects = AudioEffectsStore(context)
        try {
            withTimeout(5_000) { effects.state.first { it.isReady } }
            repeat(5) { index ->
                effects.setSettings(EqualizerSettings(true, -3.5f + index / 2f,
                    List(30) { if (it == 29) 4.5f else -1.5f }))
                effects.saveSlot(index, "Upgrade slot ${index + 1}")
            }
            effects.loadSlot(0)
            assertTrue(effects.persistNow())
        } finally { effects.close() }
        val cache = PlaylistDiskCache(context)
        try {
            val item = MusicContent("0000000000000000000001", "spotify:playlist:0000000000000000000001",
                "Upgrade library preserved", "", null, ContentKind.PLAYLIST, description = "Local upgrade fixture", trackCount = 0)
            val previous = cache.read(account)
            assertTrue(cache.reconcile(account, previous.generation, PlaylistCacheSnapshot(
                listOf(PlaylistCacheEntry(item, "upgrade-fixture", 0)), System.currentTimeMillis())))
        } finally { cache.close() }
    }
}
