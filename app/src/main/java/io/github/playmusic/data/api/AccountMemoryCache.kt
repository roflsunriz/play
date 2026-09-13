package io.github.playmusic.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One account at a time, shared in-flight reads, bounded retained values, and no cached failures. */
internal class AccountMemoryCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Int,
    private val weightOf: (V) -> Int,
    maxConcurrentLoads: Int,
) {
    private class ContentsChanged(val generation: Long) : CancellationException("Content changed")
    private class Flight<V>(val task: Deferred<V>, val generation: Long, var forceRequested: Boolean, var waiters: Int = 0)
    private class Retained<V>(val value: V, val source: Flight<V>)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(maxConcurrentLoads)
    private val values = LinkedHashMap<K, Retained<V>>(16, 0.75f, true)
    private val flights = mutableMapOf<K, Flight<V>>()
    private var owner: String? = null
    private var generation = 0L

    init { require(maxEntries > 0 && maxWeight >= 0) }

    suspend fun get(account: String, key: K, forceRefresh: Boolean, load: suspend () -> V): V {
        while (true) {
            currentCoroutineContext().ensureActive()
            try { return awaitValue(account, key, forceRefresh, load) }
            catch (changed: ContentsChanged) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (owner != account || generation != changed.generation) throw changed
                }
            }
        }
    }

    private suspend fun awaitValue(account: String, key: K, forceRefresh: Boolean, load: suspend () -> V): V {
        val flight = synchronized(lock) {
            useAccount(account)
            if (!forceRefresh) values[key]?.let { return it.value }
            (flights[key] ?: run {
                val expectedGeneration = generation
                val task = scope.async(start = CoroutineStart.LAZY) {
                    val ownTask = currentCoroutineContext().job
                    val result = permits.withPermit { load() }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (owner != account || generation != expectedGeneration || flights[key]?.task !== ownTask) {
                            throw invalidation(account, expectedGeneration)
                        }
                        retain(key, result, checkNotNull(flights[key]))
                    }
                    result
                }
                Flight(task, generation, forceRefresh).also { flights[key] = it }
            }).also { it.waiters++; it.forceRequested = it.forceRequested || forceRefresh }
        }
        try {
            flight.task.start()
            val result = flight.task.await()
            synchronized(lock) {
                if (owner != account || flights[key] !== flight) throw invalidation(account, flight.generation)
            }
            return result
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.task.isCompleted) flight.task.cancel(CancellationException("Content request has no remaining consumers"))
                }
            }
        }
    }

    fun peek(account: String, key: K): V? = synchronized(lock) {
        useAccount(account)
        values[key]?.value
    }

    /** Invalidate reads already present when refresh started, while preserving concurrent explicit refreshes. */
    fun invalidationFor(account: String, matches: (K) -> Boolean): () -> Unit = synchronized(lock) {
        val retained = if (owner == account) values.filterKeys(matches) else emptyMap()
        val pending = if (owner == account) flights.filter { matches(it.key) && !it.value.forceRequested } else emptyMap()
        val expectedGeneration = generation
        return@synchronized {
            synchronized(lock) {
                if (owner == account && generation == expectedGeneration) {
                    values.entries.removeAll { (key, value) ->
                        retained[key] === value || (pending[key] === value.source && !value.source.forceRequested)
                    }
                    pending.forEach { (key, flight) ->
                        if (flights[key] === flight && !flight.forceRequested) {
                            flights.remove(key)
                            flight.task.cancel(ContentsChanged(generation))
                        }
                    }
                }
            }
        }
    }

    fun invalidate(account: String, key: K) = synchronized(lock) {
        if (owner != account) return@synchronized
        values.remove(key)
        flights.remove(key)?.task?.cancel(ContentsChanged(generation))
    }

    fun clear() = synchronized(lock) {
        generation++
        owner = null
        values.clear()
        flights.values.toList().forEach { it.task.cancel(CancellationException("Content cache cleared")) }
        flights.clear()
    }

    private fun useAccount(account: String) {
        if (owner != account) { clear(); owner = account }
    }

    private fun invalidation(account: String, expectedGeneration: Long): CancellationException =
        if (owner == account && generation == expectedGeneration) ContentsChanged(generation)
        else CancellationException("Content request was invalidated")

    private fun retain(key: K, value: V, source: Flight<V>) {
        val weight = weightOf(value)
        require(weight >= 0)
        values.remove(key)
        if (weight > maxWeight) return
        values[key] = Retained(value, source)
        while (values.size > maxEntries || values.values.sumOf { weightOf(it.value).toLong() } > maxWeight) {
            values.remove(values.keys.first())
        }
    }
}
