package io.github.playmusic.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object Pkce {
    private val secureRandom = SecureRandom()

    fun randomUrlSafe(byteCount: Int = 64): String {
        require(byteCount >= 32)
        return encodeUrlSafe(ByteArray(byteCount).also(secureRandom::nextBytes))
    }

    fun challenge(verifier: String): String =
        encodeUrlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeUrlSafe(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
}
