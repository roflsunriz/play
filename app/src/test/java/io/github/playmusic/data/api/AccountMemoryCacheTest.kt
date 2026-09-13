package io.github.playmusic.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AccountMemoryCacheTest {
    @Test
    fun concurrentReadsShareTheLoadAndCancellingOneConsumerDoesNotCancelAnother() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()
            val loads = AtomicInteger()
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) { loads.incrementAndGet(); started.complete(Unit); release.await() }
            }
            started.await()
            val foreground = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) { error("Duplicate request") }
            }
            prefetch.cancelAndJoin()
            release.complete("loaded")
            assertEquals("loaded", foreground.await())
            assertEquals("loaded", cache.get("a", "one", false) { error("Cache miss") })
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun cancellingTheLastConsumerCancelsItsLoadAndTheNextReadCanRetry() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) {
                    try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            request.cancelAndJoin()
            stopped.await()
            assertNull(cache.peek("a", "one"))
            assertEquals("retry", cache.get("a", "one", false) { "retry" })
        }
    }

    @Test
    fun failedRefreshRetainsTheOldValueAndDoesNotCacheFailure() = runBlocking {
        val cache = cache()
        cache.get("a", "one", false) { "old" }
        val failed = runCatching { cache.get("a", "one", true) { throw IOException("Synthetic failure") } }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertEquals("old", cache.peek("a", "one"))
        assertEquals("new", cache.get("a", "one", true) { "new" })
        assertEquals("new", cache.peek("a", "one"))
        assertTrue(runCatching { cache.get("a", "two", false) { throw IOException() } }.isFailure)
        assertEquals("retry", cache.get("a", "two", false) { "retry" })
    }

    @Test
    fun detailLoadsNeverExceedTheConfiguredConcurrency() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = Channel<String>(Channel.UNLIMITED)
            val releases = (1..3).associate { it.toString() to CompletableDeferred<Unit>() }
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val requests = releases.map { (key, release) -> async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", key, false) {
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    try { started.send(key); release.await(); key } finally { active.decrementAndGet() }
                }
            } }
            val first = started.receive()
            val second = started.receive()
            assertTrue(started.tryReceive().isFailure)
            releases.getValue(first).complete(Unit)
            val third = started.receive()
            assertFalse(third == first || third == second)
            releases.getValue(second).complete(Unit)
            releases.getValue(third).complete(Unit)
            assertEquals(setOf("1", "2", "3"), requests.awaitAll().toSet())
            assertEquals(2, maximum.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun cachedValuesUseLeastRecentlyUsedEvictionAndAnIndependentWeightBound() = runBlocking {
        val cache = AccountMemoryCache<String, String>(2, 6, String::length, 2)
        cache.get("a", "one", false) { "11" }
        cache.get("a", "two", false) { "22" }
        assertEquals("11", cache.peek("a", "one"))
        cache.get("a", "three", false) { "33" }
        assertNull(cache.peek("a", "two"))
        cache.get("a", "four", false) { "44444" }
        assertNull(cache.peek("a", "one"))
        assertNull(cache.peek("a", "three"))
        assertEquals("44444", cache.peek("a", "four"))
        assertEquals("oversized", cache.get("a", "huge", false) { "oversized" })
        assertNull(cache.peek("a", "huge"))
        assertEquals("44444", cache.peek("a", "four"))
    }

    @Test
    fun anAccountChangeCancelsOldReadsEvenWhenTheLoaderCannotStopImmediately() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val oldAccount = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) {
                    try {
                        withContext(NonCancellable) { started.complete(Unit); release.await() }
                        "private-a"
                    } finally { finished.complete(Unit) }
                }
            }
            started.await()
            assertNull(cache.peek("b", "one"))
            cache.get("b", "one", false) { "private-b" }
            release.complete(Unit)
            assertTrue(runCatching { oldAccount.await() }.exceptionOrNull() is CancellationException)
            finished.await()
            assertEquals("private-b", cache.peek("b", "one"))
            assertNull(cache.peek("a", "one"))
        }
    }

    @Test
    fun invalidationReloadsForActiveConsumersAndClearRemovesAllRetainedValues() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            cache.get("a", "old", false) { "cached" }
            val started = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "pending", false) {
                    if (loads.incrementAndGet() == 1) { started.complete(Unit); awaitCancellation() }
                    "fresh"
                }
            }
            started.await()
            cache.invalidate("a", "pending")
            assertEquals("fresh", request.await())
            assertEquals(2, loads.get())
            cache.clear()
            assertNull(cache.peek("a", "pending"))
            assertNull(cache.peek("a", "old"))
        }
    }

    @Test
    fun libraryRefreshInvalidatesItsOldDetailsButPreservesAConcurrentExplicitDetailRefresh() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            cache.get("a", "playlist:one", false) { "old" }
            cache.get("a", "album:one", false) { "album" }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()
            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "playlist:one", true) { started.complete(Unit); release.await() }
            }
            started.await()
            val invalidate = cache.invalidationFor("a") { it.startsWith("playlist:") }
            release.complete("fresh")
            refresh.await()
            invalidate()
            assertEquals("fresh", cache.peek("a", "playlist:one"))
            assertEquals("album", cache.peek("a", "album:one"))
            cache.invalidationFor("a") { it.startsWith("playlist:") }.invoke()
            assertNull(cache.peek("a", "playlist:one"))
        }
    }

    @Test
    fun libraryRefreshRestartsOlderPrefetchesUnlessAnExplicitRefreshHasJoinedThem() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) { started.complete(Unit); release.await() }
            }
            started.await()
            val invalidate = cache.invalidationFor("a") { true }
            val explicit = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", true) { error("Must share the current request") }
            }
            invalidate()
            release.complete("fresh")
            assertEquals(listOf("fresh", "fresh"), listOf(prefetch, explicit).awaitAll())
            assertEquals("fresh", cache.peek("a", "one"))
            val nextStarted = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val older = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "two", false) {
                    if (loads.incrementAndGet() == 1) { nextStarted.complete(Unit); awaitCancellation() }
                    "fresh-two"
                }
            }
            nextStarted.await()
            cache.invalidationFor("a") { it == "two" }.invoke()
            assertEquals("fresh-two", older.await())
            assertEquals(2, loads.get())
        }
    }

    @Test
    fun normalDetailConsumersJoiningPrefetchAreReloadedTogetherWhenLibraryRefreshInvalidatesIt() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val freshStarted = CompletableDeferred<Unit>()
            val releaseFresh = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val load: suspend () -> String = {
                if (loads.incrementAndGet() == 1) { started.complete(Unit); awaitCancellation() }
                freshStarted.complete(Unit)
                releaseFresh.await()
                "fresh"
            }
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) { cache.get("a", "playlist", false, load) }
            started.await()
            val finishLibraryRefresh = cache.invalidationFor("a") { true }
            val foreground = async(start = CoroutineStart.UNDISPATCHED) { cache.get("a", "playlist", false, load) }
            finishLibraryRefresh()
            freshStarted.await()
            releaseFresh.complete(Unit)
            assertEquals(listOf("fresh", "fresh"), listOf(prefetch, foreground).awaitAll())
            assertEquals(2, loads.get())
        }
    }

    @Test
    fun clearingTheAccountCacheCancelsConsumersInsteadOfRetryingUnderTheOldAccount() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val started = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("a", "one", false) { loads.incrementAndGet(); started.complete(Unit); awaitCancellation() }
            }
            started.await()
            cache.clear()
            assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, loads.get())
        }
    }

    private fun cache() = AccountMemoryCache<String, String>(24, 6_000, String::length, 2)
}
