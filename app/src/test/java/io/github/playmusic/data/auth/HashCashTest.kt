package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashCashTest {
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

        val digest = java.security.MessageDigest.getInstance("SHA-1")
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

        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update(prefix)
        digest.update(suffix)
        val candidate = digest.digest()
        val digestLong = java.nio.ByteBuffer.wrap(candidate).getLong(12)

        assertTrue(
            "Expected at least 10 trailing zero bits, got ${java.lang.Long.numberOfTrailingZeros(digestLong)}",
            java.lang.Long.numberOfTrailingZeros(digestLong) >= 10,
        )
    }
}