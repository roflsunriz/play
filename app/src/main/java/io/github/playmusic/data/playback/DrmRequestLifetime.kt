package io.github.playmusic.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.analytics.PlayerId
import java.util.WeakHashMap

/** Connects the platform session lifetime to work on Media3's separate license thread. */
@OptIn(UnstableApi::class)
internal class DrmRequestLifetime {
    private class Session(val id: ByteArray) {
        @Volatile var cancelled = false
    }
    private val sessions = mutableListOf<Session>()
    // Media3 may discard a queued request without ever calling the callback.
    private val requests = WeakHashMap<ExoMediaDrm.KeyRequest, Session>()

    @Synchronized fun opened(id: ByteArray) { sessions += Session(id.copyOf()) }

    @Synchronized fun register(id: ByteArray, request: ExoMediaDrm.KeyRequest) {
        val session = sessions.firstOrNull { it.id.contentEquals(id) }
            ?: Session(ByteArray(0)).apply { cancelled = true }
        requests[request] = session
    }

    @Synchronized fun cancellation(request: ExoMediaDrm.KeyRequest): () -> Boolean {
        val session = requests[request]
        return { session == null || session.cancelled }
    }

    @Synchronized fun completed(request: ExoMediaDrm.KeyRequest) { requests.remove(request) }

    @Synchronized fun closed(id: ByteArray) {
        sessions.removeAll { session ->
            if (!session.id.contentEquals(id)) false else {
                session.cancelled = true
                true
            }
        }
    }

    @Synchronized fun cancelAll() {
        sessions.forEach { it.cancelled = true }
        sessions.clear()
    }
}

/** Delegates all cryptography to Android, observing only opaque session/request identities. */
@OptIn(UnstableApi::class)
internal class LifetimeMediaDrm(
    private val delegate: ExoMediaDrm,
    private val lifetime: DrmRequestLifetime,
) : ExoMediaDrm by delegate {
    // Explicitly preserve Java default-method overrides on the actual platform implementation.
    override fun setPlayerIdForSession(sessionId: ByteArray, playerId: PlayerId) =
        delegate.setPlayerIdForSession(sessionId, playerId)
    override fun removeOfflineLicense(keySetId: ByteArray) = delegate.removeOfflineLicense(keySetId)
    override fun getOfflineLicenseKeySetIds(): MutableList<ByteArray> = delegate.offlineLicenseKeySetIds

    override fun openSession(): ByteArray = delegate.openSession().also(lifetime::opened)

    override fun closeSession(sessionId: ByteArray) {
        lifetime.closed(sessionId)
        delegate.closeSession(sessionId)
    }

    override fun getKeyRequest(scope: ByteArray, schemeDatas: MutableList<DrmInitData.SchemeData>?,
        keyType: Int, optionalParameters: HashMap<String, String>?): ExoMediaDrm.KeyRequest =
        delegate.getKeyRequest(scope, schemeDatas, keyType, optionalParameters).also { lifetime.register(scope, it) }
}
