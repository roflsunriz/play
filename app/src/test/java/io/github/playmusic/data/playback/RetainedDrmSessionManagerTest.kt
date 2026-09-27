package io.github.playmusic.data.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener.EventDispatcher
import androidx.media3.exoplayer.drm.DrmSessionManager
import org.junit.Assert.*
import org.junit.Test

class RetainedDrmSessionManagerTest {
    @Test fun preparationAndReleaseReachPlatformBeforeCryptoTypeAndDecoderAcquisition() {
        val rig = Rig()
        val format = Format.Builder().setId("a").build()
        assertEquals(1, rig.platform.prepared)
        assertEquals(C.CRYPTO_TYPE_FRAMEWORK, rig.manager.getCryptoType(format))
        rig.manager.prepare()
        assertEquals(2, rig.platform.prepared)
        rig.manager.release()
        assertEquals(1, rig.platform.prepared)
        assertTrue(rig.acquire("a").playClearSamplesWithoutKeys())
        rig.manager.release()
        assertEquals(0, rig.platform.prepared)
        assertTrue(runCatching { rig.manager.getCryptoType(format) }.isFailure)
        rig.manager.prepare()
        assertEquals(C.CRYPTO_TYPE_FRAMEWORK, rig.manager.getCryptoType(format))
    }

    @Test fun futureSampleQueuePreacquisitionNeverStartsPlatformWorkButIncomingDecoderDoes() {
        val rig = Rig()
        val future = Format.Builder().setId("future").build()
        repeat(6) {
            val preload = rig.manager.preacquireSession(null, future)
            preload.release()
            preload.release() // The optional empty reference remains idempotent.
        }
        assertEquals(0, rig.platform.preacquired)
        assertEquals(0, rig.platform.preacquisitionCalls)
        assertTrue(rig.platform.created.isEmpty())
        val incoming = rig.acquire("future")
        assertEquals(1, rig.platform.created.size)
        assertEquals(DrmSession.STATE_OPENED_WITH_KEYS, incoming.state)
    }

    @Test fun completedSessionSurvivesSourceReleaseAndIsReusedUntilIdleTimeout() {
        val rig = Rig()
        val first = rig.acquire("a")
        first.release(null)
        rig.manager.release()
        assertEquals(1, rig.platform.latest("a").references)
        rig.manager.prepare()
        val again = rig.acquire("a")
        assertSame(first, again)
        assertEquals(1, rig.platform.created.size)
        assertTrue(rig.timers.isEmpty())
        again.release(null)
        assertEquals(30_000L, rig.timers.values.single())
        rig.expire()
        assertEquals(DrmSession.STATE_RELEASED, again.state)
        assertNotSame(again, rig.acquire("a"))
        assertEquals(2, rig.platform.created.size)
    }

    @Test fun pendingAndFailedSessionsAreNeverRetained() {
        for (state in listOf(DrmSession.STATE_OPENING, DrmSession.STATE_OPENED, DrmSession.STATE_ERROR)) {
            val rig = Rig(state)
            val session = rig.acquire("a")
            session.release(null)
            assertEquals(0, rig.platform.latest("a").references)
            assertTrue(rig.timers.isEmpty())
        }
    }

    @Test fun platformFailureWhileIdleIsDiscardedBeforeRevisitingTrack() {
        val rig = Rig()
        val first = rig.acquire("a")
        first.release(null)
        rig.platform.latest("a").currentState = DrmSession.STATE_ERROR
        val next = rig.acquire("a")
        assertNotSame(first, next)
        assertEquals(DrmSession.STATE_OPENED_WITH_KEYS, next.state)
        assertEquals(2, rig.platform.created.size)
        assertTrue(rig.timers.isEmpty())
    }

    @Test fun onlyLastConsumerStartsTimeoutAndReacquisitionCancelsIt() {
        val rig = Rig()
        val a = rig.acquire("a")
        a.acquire(null) // Decoder and sample queue have independent references.
        val another = rig.acquire("a")
        assertSame(a, another)
        a.release(null)
        another.release(null)
        assertTrue(rig.timers.isEmpty())
        a.release(null)
        assertEquals(1, rig.timers.size)
        a.acquire(null)
        assertTrue(rig.timers.isEmpty())
        assertEquals(1, rig.platform.latest("a").references)
        a.release(null)
        rig.expire()
        assertEquals(0, rig.platform.latest("a").references)
    }

    @Test fun capacityEvictsOldestIdleSessionWithoutTouchingActiveSession() {
        val rig = Rig()
        val playing = rig.acquire("playing")
        listOf("a", "b", "c").forEach { rig.acquire(it).release(null) }
        assertEquals(0, rig.platform.latest("a").references)
        assertEquals(1, rig.platform.latest("b").references)
        assertEquals(1, rig.platform.latest("c").references)
        assertEquals(DrmSession.STATE_OPENED_WITH_KEYS, playing.state)
        assertEquals(2, rig.timers.size)
    }

    @Test fun clearDropsIdleSessionsAndPreventsOldActiveSessionBeingRetainedLater() {
        val rig = Rig()
        val active = rig.acquire("a")
        rig.acquire("b").release(null)
        rig.manager.invalidate()
        assertEquals(0, rig.platform.latest("b").references)
        active.release(null)
        assertEquals(0, rig.platform.latest("a").references)
        assertTrue(rig.timers.isEmpty())
        rig.acquire("c").release(null)
        assertEquals(1, rig.timers.size)
    }

    private class Rig(state: Int = DrmSession.STATE_OPENED_WITH_KEYS) {
        val platform = FakeManager(state)
        val timers = linkedMapOf<Runnable, Long>()
        val manager = RetainedDrmSessionManager(platform, DrmRequestLifetime(),
            schedule = { task, delay -> timers[task] = delay }, unschedule = { timers.remove(it) })
            .also { it.prepare() }
        fun acquire(id: String) = checkNotNull(manager.acquireSession(null, Format.Builder().setId(id).build()))
        fun expire() { timers.keys.toList().forEach { it.run() } }
    }

    private class FakeManager(val initialState: Int) : DrmSessionManager {
        val created = mutableListOf<Pair<String, FakeSession>>()
        var prepared = 0
        var preacquired = 0
        var preacquisitionCalls = 0
        override fun prepare() { prepared++ }
        override fun release() { check(prepared > 0); prepared-- }
        override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = Unit
        override fun getCryptoType(format: Format): Int {
            check(prepared > 0) { "Platform DRM not prepared" }
            return C.CRYPTO_TYPE_FRAMEWORK
        }
        override fun preacquireSession(eventDispatcher: EventDispatcher?, format: Format): DrmSessionManager.DrmSessionReference {
            check(prepared > 0)
            preacquired++
            preacquisitionCalls++
            var released = false
            return DrmSessionManager.DrmSessionReference {
                if (!released) { released = true; preacquired-- }
            }
        }
        override fun acquireSession(eventDispatcher: EventDispatcher?, format: Format): DrmSession {
            check(prepared > 0) { "Platform DRM not prepared" }
            val id = checkNotNull(format.id)
            val session = created.lastOrNull { it.first == id && it.second.state != DrmSession.STATE_RELEASED }?.second
                ?: FakeSession(initialState).also { created += id to it }
            session.acquire(eventDispatcher)
            return session
        }
        fun latest(id: String) = created.last { it.first == id }.second
    }

    private class FakeSession(var currentState: Int) : DrmSession {
        var references = 0
        override fun getState() = currentState
        override fun playClearSamplesWithoutKeys() = true
        override fun getError(): DrmSession.DrmSessionException? = null
        override fun getSchemeUuid() = C.WIDEVINE_UUID
        override fun getCryptoConfig(): CryptoConfig? = null
        override fun queryKeyStatus(): MutableMap<String, String>? = null
        override fun getOfflineLicenseKeySetId(): ByteArray? = null
        override fun requiresSecureDecoder(mimeType: String) = false
        override fun acquire(eventDispatcher: EventDispatcher?) { references++ }
        override fun release(eventDispatcher: EventDispatcher?) {
            check(references > 0)
            if (--references == 0) currentState = DrmSession.STATE_RELEASED
        }
    }
}
