package io.github.playmusic.data.playback

import android.media.ResourceBusyException
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener.EventDispatcher
import androidx.media3.exoplayer.drm.DrmSessionManager
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps only successfully loaded platform sessions briefly reusable on the same playback thread.
 * DefaultDrmSessionManager must have its own keepalive disabled: pending requests must close as
 * soon as the last consumer leaves. Keys and license responses are never copied or persisted.
 */
@OptIn(UnstableApi::class)
internal class RetainedDrmSessionManager(
    private val delegate: DrmSessionManager,
    private val lifetime: DrmRequestLifetime,
    private val keepaliveMs: Long = 30_000,
    private val maxRetained: Int = 2,
    private val schedule: ((Runnable, Long) -> Unit)? = null,
    private val unschedule: ((Runnable) -> Unit)? = null,
) : DrmSessionManager by delegate {
    private var handler: Handler? = null
    private val generation = AtomicInteger()
    private val sessions = IdentityHashMap<DrmSession, Session>()
    private val retained = linkedSetOf<Session>()

    init { require(keepaliveMs > 0 && maxRetained > 0) }

    // Kotlin interface delegation does not forward Java default methods. prepare/release must
    // reach the real manager, otherwise its MediaDrm remains uninitialized on first preparation.
    override fun prepare() = delegate.prepare()
    override fun release() = delegate.release()
    override fun preacquireSession(eventDispatcher: EventDispatcher?, format: Format): DrmSessionManager.DrmSessionReference {
        // Progressive sample queues also pre-acquire for buffered future tracks. Repeated seeks
        // discard those queues and re-create their licenses despite never playing the tracks.
        // TransitionPlayer prepares the incoming decoder explicitly, which calls acquireSession.
        return DrmSessionManager.DrmSessionReference.EMPTY
    }

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {
        delegate.setPlayer(playbackLooper, playerId)
        if (handler == null) handler = Handler(playbackLooper)
    }

    override fun acquireSession(eventDispatcher: EventDispatcher?, format: Format): DrmSession? {
        // A platform expiry/renewal can fail while idle. Never hand that terminal state back
        // to a new consumer just because its original license acquisition succeeded.
        retained.filter { it.state != DrmSession.STATE_OPENED_WITH_KEYS }.forEach { it.releaseRetention() }
        var underlying = delegate.acquireSession(eventDispatcher, format) ?: return null
        if (underlying.error?.cause is ResourceBusyException && retained.isNotEmpty()) {
            underlying.release(eventDispatcher)
            clearRetained()
            underlying = delegate.acquireSession(eventDispatcher, format) ?: return null
        }
        val session = sessions.getOrPut(underlying) { Session(underlying, generation.get()) }
        session.references++
        session.releaseRetention()
        return session
    }

    /** Called before clear/release, also invalidating pending HTTP work immediately on any thread. */
    fun invalidate() {
        generation.incrementAndGet()
        lifetime.cancelAll()
        val playbackHandler = handler
        if (playbackHandler == null || Looper.myLooper() == playbackHandler.looper) clearRetained()
        else playbackHandler.post { clearRetained() }
    }

    private fun clearRetained() { retained.toList().forEach { it.releaseRetention() } }

    private inner class Session(val underlying: DrmSession, val epoch: Int) : DrmSession by underlying {
        var references = 0
        private val expire = Runnable { releaseRetention() }

        override fun playClearSamplesWithoutKeys(): Boolean = underlying.playClearSamplesWithoutKeys()

        override fun acquire(eventDispatcher: EventDispatcher?) {
            underlying.acquire(eventDispatcher)
            references++
            releaseRetention()
        }

        override fun release(eventDispatcher: EventDispatcher?) {
            check(references > 0)
            references--
            if (references == 0 && epoch == generation.get() && state == DrmSession.STATE_OPENED_WITH_KEYS) {
                underlying.acquire(null)
                retained.add(this)
                if (schedule != null) schedule.invoke(expire, keepaliveMs)
                else checkNotNull(handler).postDelayed(expire, keepaliveMs)
                while (retained.size > maxRetained) retained.first().releaseRetention()
            }
            underlying.release(eventDispatcher)
            forgetIfUnused()
        }

        fun releaseRetention() {
            if (retained.remove(this)) {
                if (unschedule != null) unschedule.invoke(expire) else handler?.removeCallbacks(expire)
                underlying.release(null)
            }
            forgetIfUnused()
        }

        private fun forgetIfUnused() {
            if (references == 0 && this !in retained) sessions.remove(underlying)
        }
    }
}
