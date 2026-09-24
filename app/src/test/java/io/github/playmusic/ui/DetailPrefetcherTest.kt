package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailPrefetcherTest {
    @Test fun theWindowIsBoundedAndCachedEntriesDoNotLoadAgain() = runTest {
        val items = (0..10).map(::item)
        val cached = mutableSetOf(items[0].uri)
        val loaded = mutableListOf<String>()
        val worker = DetailPrefetcher(backgroundScope, { it.uri in cached }, {
            loaded += it.uri; cached += it.uri
        }, StandardTestDispatcher(testScheduler))
        worker.update(items)
        runCurrent()
        assertEquals(items.subList(1, 4).map { it.uri }, loaded)
        worker.update(emptyList())
        runCurrent()
        worker.update(items)
        runCurrent()
        assertEquals(3, loaded.size)
    }

    @Test fun scrollingDropsQueuedWorkButLetsTheSingleActiveReadFinish() = runTest {
        val started = mutableListOf<Int>()
        val cached = mutableSetOf<String>()
        val firstRead = CompletableDeferred<Unit>()
        var cancelled = false
        var active = 0
        var maximum = 0
        val worker = DetailPrefetcher(backgroundScope, { it.uri in cached }, { content ->
            active++; maximum = maxOf(maximum, active); started += content.id.toInt()
            try {
                if (content.id == "0") firstRead.await()
                cached += content.uri
            } finally { active--; if (!firstRead.isCompleted) cancelled = true }
        }, StandardTestDispatcher(testScheduler))
        worker.update(listOf(item(0), item(1), item(2)))
        runCurrent()
        worker.update(emptyList())
        runCurrent()
        assertFalse(cancelled)
        firstRead.complete(Unit)
        runCurrent()
        assertEquals(listOf(0), started)
        worker.update(listOf(item(0), item(8), item(9)))
        runCurrent()
        assertEquals(listOf(0, 8, 9), started)
        assertEquals(1, maximum)
    }

    @Test fun aNewWindowReplacesOldPendingEntriesWithoutStarvingIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val worker = DetailPrefetcher(backgroundScope, { false }, {
            started += it.id.toInt()
            if (it.id == "0") gate.await()
        }, StandardTestDispatcher(testScheduler))
        worker.update(listOf(item(0), item(1)))
        runCurrent()
        worker.update(listOf(item(5), item(6)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 5, 6), started)
    }

    @Test fun accountCancellationStopsActiveWorkAndFailuresDoNotLoop() = runTest {
        var cancelled = false
        val calls = mutableListOf<Int>()
        val worker = DetailPrefetcher(backgroundScope, { false }, {
            calls += it.id.toInt()
            if (it.id == "0") try { awaitCancellation() } finally { cancelled = true }
            if (it.id == "1") error("Unavailable item")
        }, StandardTestDispatcher(testScheduler))
        worker.update(listOf(item(0), item(4)))
        runCurrent()
        worker.cancel()
        runCurrent()
        assertTrue(cancelled)
        worker.update(listOf(item(1), item(2)))
        runCurrent()
        worker.update(listOf(item(1), item(2)))
        runCurrent()
        assertEquals(listOf(0, 1, 2), calls)
    }

    private fun item(id: Int) = MusicContent(id.toString(), "spotify:playlist:$id", "Item $id", "", null, ContentKind.PLAYLIST)
}
