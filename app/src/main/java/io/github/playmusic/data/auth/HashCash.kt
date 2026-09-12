package io.github.playmusic.data.auth

import java.security.MessageDigest

object HashCash {
    data class Solution(
        val suffix: ByteArray,
        val durationSeconds: Long,
        val durationNanos: Int,
    )

    fun solve(loginContext: ByteArray?, prefix: ByteArray, length: Int, maxDurationMs: Long = 30_000): Solution {
        require(length in 0..64)
        require(maxDurationMs in 0..30_000)
        val contextDigest = MessageDigest.getInstance("SHA-1").digest(loginContext ?: ByteArray(0))
        val seed = ByteArray(8)
        System.arraycopy(contextDigest, 12, seed, 0, 8)

        val suffix = ByteArray(16)
        System.arraycopy(seed, 0, suffix, 0, 8)

        val start = System.nanoTime()
        val digest = MessageDigest.getInstance("SHA-1")
        val candidate = ByteArray(20)
        var iterations = 0
        while (true) {
            if (iterations++ and 4095 == 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Verification cancelled")
                if (System.nanoTime() - start >= maxDurationMs * 1_000_000) {
                    throw SpotifyAuthException("Verification calculation timed out. Try again.")
                }
            }
            digest.reset()
            digest.update(prefix)
            digest.update(suffix)
            digest.digest(candidate, 0, candidate.size)
            if (hasTrailingZeroBits(candidate, length)) {
                break
            }
            increment(suffix, suffix.size - 1)
            increment(suffix, 7)
        }
        val durationNano = System.nanoTime() - start
        return Solution(
            suffix = suffix.copyOf(),
            durationSeconds = durationNano / 1_000_000_000L,
            durationNanos = (durationNano % 1_000_000_000L).toInt(),
        )
    }

    /**
     * Client token hashcash.
     * seed = SHA1("")[12:20] as BigEndian uint64 target (fixed).
     * suffix = [target.to_be_bytes(), counter.to_be_bytes()] (16 bytes).
     * SHA1(prefix + suffix)[12:20] must have >= length trailing zero bits.
     */
    fun solveClientToken(prefix: ByteArray, length: Int): ByteArray {
        return solve(null, prefix, length).suffix
    }

    private fun hasTrailingZeroBits(array: ByteArray, length: Int): Boolean {
        var trailing = 0
        for (index in array.indices.reversed()) {
            val byte = array[index].toInt()
            if (byte == 0) {
                trailing += 8
            } else {
                trailing += Integer.numberOfTrailingZeros(byte)
                break
            }
            if (trailing >= length) return true
        }
        return trailing >= length
    }

    private fun increment(byteArray: ByteArray, index: Int) {
        byteArray[index]++
        if (byteArray[index] == 0.toByte() && index != 0) increment(byteArray, index - 1)
    }
}
