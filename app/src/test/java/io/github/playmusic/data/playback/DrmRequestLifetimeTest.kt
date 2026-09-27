package io.github.playmusic.data.playback

import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DrmRequestLifetimeTest {
    @Test fun platformWrapperConnectsActualKeyRequestIdentityToSessionClose() {
        val lifetime = DrmRequestLifetime()
        val platformRequest = request()
        var closes = 0
        var playerAssigned = false
        var removed = false
        val keySets = mutableListOf(byteArrayOf(9))
        val platform = Proxy.newProxyInstance(ExoMediaDrm::class.java.classLoader,
            arrayOf(ExoMediaDrm::class.java)) { _, method, _ ->
            when (method.name) {
                "openSession" -> byteArrayOf(1)
                "getKeyRequest" -> platformRequest
                "closeSession" -> { closes++; null }
                "setPlayerIdForSession" -> { playerAssigned = true; null }
                "removeOfflineLicense" -> { removed = true; null }
                "getOfflineLicenseKeySetIds" -> keySets
                else -> error("Unexpected platform operation: ${method.name}")
            }
        } as ExoMediaDrm
        val wrapper = LifetimeMediaDrm(platform, lifetime)
        val session = wrapper.openSession()
        wrapper.setPlayerIdForSession(session, PlayerId.UNSET)
        wrapper.removeOfflineLicense(byteArrayOf(9))
        assertSame(keySets, wrapper.offlineLicenseKeySetIds)
        assertTrue(playerAssigned)
        assertTrue(removed)
        val request = wrapper.getKeyRequest(session, null, ExoMediaDrm.KEY_TYPE_STREAMING, null)
        assertSame(platformRequest, request)
        val cancelled = lifetime.cancellation(request)
        assertFalse(cancelled())
        wrapper.closeSession(session.copyOf())
        assertTrue(cancelled())
        assertEquals(1, closes)
    }

    @Test fun closingOneSessionCancelsAllItsRequestsButNotAnotherSession() {
        val lifetime = DrmRequestLifetime()
        lifetime.opened(byteArrayOf(1))
        lifetime.opened(byteArrayOf(2))
        val first = request()
        val renewal = request()
        val other = request()
        lifetime.register(byteArrayOf(1), first)
        lifetime.register(byteArrayOf(1), renewal)
        lifetime.register(byteArrayOf(2), other)
        val firstCancelled = lifetime.cancellation(first)
        assertFalse(firstCancelled())
        lifetime.closed(byteArrayOf(1))
        assertTrue(firstCancelled())
        assertTrue(lifetime.cancellation(renewal)()) // Includes a queued callback starting after close.
        assertFalse(lifetime.cancellation(other)())
        lifetime.opened(byteArrayOf(1))
        assertTrue(firstCancelled()) // A platform reusing an ID cannot revive an old request.
    }

    @Test fun invalidationCancelsInFlightWorkAndUnknownRequestsFailClosed() {
        val lifetime = DrmRequestLifetime()
        lifetime.opened(byteArrayOf(1))
        val first = request()
        lifetime.register(byteArrayOf(1), first)
        val cancelled = lifetime.cancellation(first)
        lifetime.cancelAll()
        assertTrue(cancelled())
        assertTrue(lifetime.cancellation(request())())
        val stale = request()
        lifetime.register(byteArrayOf(1), stale)
        assertTrue(lifetime.cancellation(stale)())
        lifetime.completed(first)
        assertTrue(lifetime.cancellation(first)())
    }

    private fun request() = ExoMediaDrm.KeyRequest(byteArrayOf(1, 2, 3), "")
}
