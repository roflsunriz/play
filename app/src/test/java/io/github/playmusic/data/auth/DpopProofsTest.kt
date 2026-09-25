package io.github.playmusic.data.auth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.security.Signature

class DpopProofsTest {
    @Test
    fun codecMatchesJavaUtilAndRejectsMalformedInput() {
        val random = SecureRandom()
        for (length in 1..64) {
            val bytes = ByteArray(length).also(random::nextBytes)
            val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals(expected, DpopProofs.base64Url(bytes))
            assertArrayEquals(bytes, DpopProofs.B64Url.decode(expected))
        }
        assertEquals("", DpopProofs.base64Url(ByteArray(0)))
        for (bad in listOf("", "A", "AB*C", "AB+C", "AB=C", "ABCDE")) {
            runCatching { DpopProofs.B64Url.decode(bad) }.exceptionOrNull() as? IllegalArgumentException
                ?: fail("decode($bad) must be rejected")
        }
        // Non-zero padding bits are malformed.
        runCatching { DpopProofs.B64Url.decode("AB") }.exceptionOrNull() as? IllegalArgumentException
            ?: fail("non-zero padding bits must be rejected")
    }

    @Test
    fun sha256MatchesKnownEmptyVector() {
        // Well-known SHA-256 digest of the empty string.
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
            java.util.Base64.getEncoder().encodeToString(DpopProofs.sha256(ByteArray(0))))
    }

    @Test
    fun keyAndThumbprintShapes() {
        val key = DpopProofs.generate()
        assertEquals(43, key.x.length)
        assertEquals(43, key.y.length)
        assertNotEquals(key.x, key.y)
        assertEquals("{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${key.x}\",\"y\":\"${key.y}\"}",
            DpopProofs.canonicalJwk(key.x, key.y))
        val thumbprint = DpopProofs.thumbprint(key.x, key.y)
        assertEquals(43, thumbprint.length)
        assertEquals(thumbprint, DpopProofs.thumbprint(key.x, key.y))
        assertFalse(key.toString().contains(key.x))
    }

    @Test
    fun proofStructureAndSignatureVerify() {
        val key = DpopProofs.generate()
        val token = DpopProofs.proof(
            key = key,
            htm = "POST",
            htu = "https://spclient.wg.spotify.com/sessiontransfer/v1/token?x=1#frag",
            accessTokenHash = "A".repeat(43),
            nonce = "test-nonce_1",
            issuedAtSeconds = 1_700_000_000L,
            identifier = "test-jti",
        )
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val header = JSONObject(String(DpopProofs.B64Url.decode(parts[0]), Charsets.US_ASCII))
        assertEquals("dpop+jwt", header.getString("typ"))
        assertEquals("ES256", header.getString("alg"))
        val jwk = header.getJSONObject("jwk")
        assertEquals("P-256", jwk.getString("crv"))
        assertEquals("EC", jwk.getString("kty"))
        assertEquals(key.x, jwk.getString("x"))
        assertEquals(key.y, jwk.getString("y"))
        val claims = JSONObject(String(DpopProofs.B64Url.decode(parts[1]), Charsets.US_ASCII))
        assertEquals("POST", claims.getString("htm"))
        assertEquals("https://spclient.wg.spotify.com/sessiontransfer/v1/token", claims.getString("htu"))
        assertEquals(1_700_000_000L, claims.getLong("iat"))
        assertEquals("test-jti", claims.getString("jti"))
        assertEquals("A".repeat(43), claims.getString("ath"))
        assertEquals("test-nonce_1", claims.getString("nonce"))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(DpopProofs.importPublic(key.x, key.y))
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        assertTrue(signature.verify(derEncode(DpopProofs.B64Url.decode(parts[2]))))
        // Tampered payload must not verify.
        val tampered = signature.apply {
            initVerify(DpopProofs.importPublic(key.x, key.y))
            update("${parts[0]}.${parts[1]}x".toByteArray(Charsets.US_ASCII))
        }
        assertFalse(tampered.verify(derEncode(DpopProofs.B64Url.decode(parts[2]))))
    }

    @Test
    fun htuNormalizationAndInputValidation() {
        assertEquals("https://example.test/a/b",
            DpopProofs.normalizeHtu("https://EXAMPLE.test:443/a/b?query=1#fragment"))
        assertEquals("https://example.test:8443/a",
            DpopProofs.normalizeHtu("https://example.test:8443/a"))
        assertEquals("https://example.test/",
            DpopProofs.normalizeHtu("https://example.test"))
        for (bad in listOf("http://example.test/", "https://user@example.test/", "https:///path", "not a uri")) {
            runCatching { DpopProofs.normalizeHtu(bad) }.exceptionOrNull() as? IllegalArgumentException
                ?: fail("normalizeHtu($bad) must be rejected")
        }
        val key = DpopProofs.generate()
        for (bad in listOf(
            Triple("post", "https://example.test/", null),
            Triple("POST", "https://example.test/", "short"),
        )) {
            runCatching {
                DpopProofs.proof(key, bad.first, bad.second,
                    accessTokenHash = bad.third ?: "A".repeat(43), identifier = "id")
            }.exceptionOrNull() as? IllegalArgumentException
                ?: fail("proof(${bad.first}, ath=${bad.third}) must be rejected")
        }
    }

    private fun derEncode(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun trim(value: ByteArray): ByteArray {
            val dropped = value.dropWhile { it == 0.toByte() }.toByteArray()
            val stripped = if (dropped.isEmpty()) byteArrayOf(0) else dropped
            return if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
        }
        val rawR = trim(raw.copyOfRange(0, 32))
        val rawS = trim(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (2 + rawR.size + 2 + rawS.size).toByte(), 0x02, rawR.size.toByte()) +
            rawR + byteArrayOf(0x02, rawS.size.toByte()) + rawS
    }
}
