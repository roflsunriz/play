package io.github.playmusic.data.auth

import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.math.BigInteger

/**
 * RFC 9449 DPoP proof primitives (ES256).
 *
 * Shapes confirmed read-only against the current desktop client: P-256 pair kept in
 * secure storage, proof header `dpop+jwt`/`ES256`/embedded JWK, claims `htm`/`htu`/`jti`/`iat`
 * with `nonce` retry, JWK form `{"crv":"P-256","kty":"EC","x":"…","y":"…"}`.
 */
object DpopProofs {
    data class Key(val privateKey: PrivateKey, val x: String, val y: String) {
        override fun toString(): String = "DpopProofs.Key"
    }

    fun generate(random: SecureRandom = SecureRandom()): Key {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        return Key(pair.private, coordinate(point.affineX), coordinate(point.affineY))
    }

    fun importPublic(x: String, y: String): ECPublicKey {
        require(x.isNotBlank() && y.isNotBlank())
        val decodedX = B64Url.decode(x)
        val decodedY = B64Url.decode(y)
        require(decodedX.size == 32 && decodedY.size == 32)
        val parameters = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, decodedX), BigInteger(1, decodedY)),
            parameters,
        )
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /** Canonical JWK JSON in lexicographic member order, as hashed for the thumbprint. */
    fun canonicalJwk(x: String, y: String): String =
        "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"$x\",\"y\":\"$y\"}"

    /** RFC 7638 thumbprint of the canonical JWK. */
    fun thumbprint(x: String, y: String): String =
        base64Url(sha256(canonicalJwk(x, y).toByteArray(Charsets.US_ASCII)))

    /**
     * Builds a DPoP proof JWT. [htu] is normalized to scheme://host[:port]/path.
     * Throws on blank inputs or oversized values; never logs key material.
     */
    fun proof(
        key: Key,
        htm: String,
        htu: String,
        accessTokenHash: String? = null,
        nonce: String? = null,
        issuedAtSeconds: Long = System.currentTimeMillis() / 1000,
        identifier: String = randomJti(),
    ): String {
        require(htm.matches(Regex("[A-Z]{1,16}")))
        val normalized = normalizeHtu(htu)
        require(identifier.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        if (accessTokenHash != null) require(accessTokenHash.matches(Regex("[A-Za-z0-9_-]{43}")))
        if (nonce != null) require(nonce.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        val header = "{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":${canonicalJwk(key.x, key.y)}}"
        val claims = buildString {
            append("{\"htm\":\"$htm\",\"htu\":\"$normalized\",\"iat\":$issuedAtSeconds,\"jti\":\"$identifier\"")
            if (accessTokenHash != null) append(",\"ath\":\"$accessTokenHash\"")
            if (nonce != null) append(",\"nonce\":\"$nonce\"")
            append('}')
        }
        val signingInput = "${base64Url(header.toByteArray(Charsets.US_ASCII))}.${base64Url(claims.toByteArray(Charsets.US_ASCII))}"
        return "$signingInput.${base64Url(rawSignature(key.privateKey, signingInput.toByteArray(Charsets.US_ASCII)))}"
    }

    fun normalizeHtu(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw IllegalArgumentException("Invalid URL")
        // Query and fragment are dropped per RFC 9449; only scheme, host, port and path survive.
        require(uri.scheme == "https" && uri.userInfo == null)
        val host = requireNotNull(uri.host).lowercase()
        require(host.isNotBlank() && host.length <= 253)
        val port = if (uri.port in listOf(-1, 443)) "" else ":${uri.port}"
        val path = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"
        require(path.length <= 2048)
        return "https://$host$port$path"
    }

    internal fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    internal fun base64Url(bytes: ByteArray): String = B64Url.encode(bytes)

    /**
     * Minimal base64url codec without padding. Self-contained so this module stays unit-testable
     * on the JVM and safe below API 26 (no android.util or java.util Base64 dependency).
     */
    internal object B64Url {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index].toInt() and 0xFF
                val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
                val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
                out.append(ALPHABET[first shr 2])
                out.append(ALPHABET[((first and 0x03) shl 4) or (second shr 4)])
                if (index + 1 < bytes.size) out.append(ALPHABET[((second and 0x0F) shl 2) or (third shr 6)])
                if (index + 2 < bytes.size) out.append(ALPHABET[third and 0x3F])
                index += 3
            }
            return out.toString()
        }

        fun decode(value: String): ByteArray {
            require(value.isNotEmpty() && value.length % 4 != 1)
            require(value.all { it in ALPHABET })
            val output = ByteArray(value.length * 6 / 8)
            var bits = 0
            var collected = 0
            var written = 0
            for (char in value) {
                bits = (bits shl 6) or ALPHABET.indexOf(char)
                collected += 6
                if (collected >= 8) {
                    collected -= 8
                    output[written++] = (bits shr collected).toByte()
                }
            }
            require(collected < 6)
            if (collected > 0) require(bits and ((1 shl collected) - 1) == 0)
            return output.copyOf(written)
        }
    }

    internal fun coordinate(value: BigInteger): String {
        val raw = value.toByteArray()
        val stripped = if (raw.size > 32 && raw[0] == 0.toByte()) raw.copyOfRange(raw.size - 32, raw.size) else raw
        require(stripped.size <= 32)
        return base64Url(ByteArray(32 - stripped.size) + stripped)
    }

    private fun rawSignature(privateKey: PrivateKey, signingInput: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(signingInput)
        val der = signature.sign()
        // Minimal DER parse: 0x30 len 0x02 lenR R 0x02 lenS S.
        require(der.size >= 8 && der[0] == 0x30.toByte() && der[2] == 0x02.toByte())
        var offset = 4
        val lengthR = der[offset - 1].toInt() and 0xFF
        val rawR = der.copyOfRange(offset, offset + lengthR)
        offset += lengthR
        require(der[offset] == 0x02.toByte())
        val lengthS = der[offset + 1].toInt() and 0xFF
        val rawS = der.copyOfRange(offset + 2, offset + 2 + lengthS)
        return fixed(rawR) + fixed(rawS)
    }

    private fun fixed(value: ByteArray): ByteArray {
        val stripped = if (value.size > 32 && value[0] == 0.toByte()) value.copyOfRange(value.size - 32, value.size) else value
        require(stripped.size <= 32)
        return ByteArray(32 - stripped.size) + stripped
    }

    private fun randomJti(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }
}
