package io.github.playmusic.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class HashCashTest {
    @Test
    fun invalidDifficultyAndExhaustedBudgetFailPromptly() {
        assertThrows(IllegalArgumentException::class.java) { HashCash.solve(null, byteArrayOf(1), -1) }
        assertThrows(IllegalArgumentException::class.java) { HashCash.solveClientToken(byteArrayOf(1), 65) }
        assertThrows(AuthException::class.java) { HashCash.solve(null, byteArrayOf(1), 64, maxDurationMs = 0) }
    }

    @Test
    fun interruptionStopsAnInProgressChallenge() {
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            started.countDown()
            try {
                HashCash.solve(null, byteArrayOf(1, 2, 3), 64)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            }
        }.apply { isDaemon = true }
        worker.start()
        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(2_000)
        assertFalse("Challenge must stop when the login operation is cancelled", worker.isAlive)
        assertTrue(interrupted.get())
    }

    @Test
    fun solutionSuffixLengthIsSixteen() {
        val solution = HashCash.solve(
            loginContext = byteArrayOf(1, 2, 3),
            prefix = byteArrayOf(0x0A, 0x0B),
            length = 10,
        )

        assertEquals(16, solution.suffix.size)
    }

    @Test
    fun solutionDigestHasTrailingZeroBits() {
        val loginContext = "login-context".toByteArray()
        val prefix = "prefix".toByteArray()
        val solution = HashCash.solve(loginContext, prefix, 10)

        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(prefix)
        digest.update(solution.suffix)
        val candidate = digest.digest()

        var trailing = 0
        for (index in candidate.indices.reversed()) {
            val byte = candidate[index].toInt()
            if (byte == 0) {
                trailing += 8
            } else {
                trailing += Integer.numberOfTrailingZeros(byte)
                break
            }
            if (trailing >= 10) break
        }
        assertTrue("Expected at least 10 trailing zero bits, got $trailing", trailing >= 10)
    }

    @Test
    fun emptyLoginContextIsHandled() {
        val solution = HashCash.solve(null, "prefix".toByteArray(), 10)

        assertEquals(16, solution.suffix.size)
    }

    @Test
    fun clientTokenSuffixIsSixteenBytes() {
        val suffix = HashCash.solveClientToken(byteArrayOf(0x0A, 0x0B, 0x0C), 20)

        assertEquals(16, suffix.size)
    }

    @Test
    fun clientTokenSuffixSatisfiesChallenge() {
        val prefix = byteArrayOf(0x0A, 0x0B, 0x0C)
        val suffix = HashCash.solveClientToken(prefix, 10)

        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(prefix)
        digest.update(suffix)
        val candidate = digest.digest()

        var trailing = 0
        for (index in candidate.indices.reversed()) {
            val byte = candidate[index].toInt()
            if (byte == 0) {
                trailing += 8
            } else {
                trailing += Integer.numberOfTrailingZeros(byte)
                break
            }
            if (trailing >= 10) break
        }
        assertTrue("Expected at least 10 trailing zero bits, got $trailing", trailing >= 10)
    }

    @Test
    fun clientTokenSuffixUsesSeedPlusCounter() {
        // suffix[0:8] must be SHA-1("")[12:20] + counter, suffix[8:16] = counter
        val prefix = byteArrayOf(0x11, 0x2C, 0x47)
        val suffix = HashCash.solveClientToken(prefix, 20)

        val seed = MessageDigest.getInstance("SHA-1").digest(ByteArray(0))
        val seedLong = java.nio.ByteBuffer.wrap(seed).getLong(12)
        val counter = java.nio.ByteBuffer.wrap(suffix, 8, 8).long

        assertTrue("counter should be >= 0", counter >= 0L)
        assertEquals(seedLong + counter, java.nio.ByteBuffer.wrap(suffix, 0, 8).long)
        assertArrayEquals(suffix.copyOfRange(8, 16), java.nio.ByteBuffer.allocate(8).putLong(counter).array())
    }
}
