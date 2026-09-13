package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicWebTokenConfigurationTest {
    @Test
    fun sha1MatchesRfc6238VectorsWithSixDigitTruncation() {
        // RFC 6238 Appendix B: the published eight-digit SHA-1 vectors reduced to six digits.
        val key = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        val vectors = mapOf(59L to "287082", 1_111_111_109L to "081804", 1_111_111_111L to "050471",
            1_234_567_890L to "005924", 2_000_000_000L to "279037", 20_000_000_000L to "353130")
        for ((seconds, expected) in vectors) assertEquals(expected, PublicWebTokenConfiguration.totp(key, seconds * 1000))
    }

    @Test
    fun stringAndNumericSeedsUseThePublishedDecimalConcatenation() {
        val string = PublicWebTokenConfiguration.parse(source("'ABC'"))
        val array = PublicWebTokenConfiguration.parse(source("[65,66,67]"))
        try {
            // 65 xor 9, 66 xor 10 and 67 xor 11 are all 72, so the HMAC key is UTF-8 "727272".
            val expected = PublicWebTokenConfiguration.totp("727272".toByteArray(), 59_000)
            for (config in listOf(string, array)) {
                val values = config.query(PublicWebTokenConfiguration.Reason.INITIAL,
                    PlaybackAuthorizationClient.PageKind.DESKTOP, 59_000, 59).parameters()
                assertEquals(setOf("reason", "productType", "totp", "totpServer", "totpVer"), values.keys)
                assertEquals("init", values["reason"])
                assertEquals("web-player", values["productType"])
                assertEquals(expected, values["totp"])
                assertEquals(expected, values["totpServer"])
                assertEquals("7", values["totpVer"])
                assertFalse(config.toString().contains("ABC"))
            }
        } finally { string.close(); array.close() }
    }

    @Test
    fun escapedLiteralsAndMobileRequestsPreserveTheContract() {
        PublicWebTokenConfiguration.parse(source("\"\\x41\\u0042C\"")).use { config ->
            val query = config.query(PublicWebTokenConfiguration.Reason.TRANSPORT,
                PlaybackAuthorizationClient.PageKind.MOBILE, 59_000, null)
            val values = query.parameters()
            assertEquals("transport", values["reason"])
            assertEquals("mobile-web-player", values["productType"])
            assertEquals("unavailable", values["totpServer"])
            assertEquals(PublicWebTokenConfiguration.totp("727272".toByteArray(), 59_000), values["totp"])
            assertFalse(query.toString().contains(values.getValue("totp")))
        }
    }

    @Test
    fun missingAmbiguousOrChangedConfigurationFailsWithoutExposingTheSource() {
        val marker = "sensitive-placeholder-must-not-appear"
        val valid = source("'$marker'")
        val invalid = listOf("", "const unrelated = 1;", valid + valid,
            source("[]"), source("[256]"), source("[-1]"), source("someFunction()"), source("'\\q'"),
            source("'ABC'").replace("version:7", "version:0"),
            source("'ABC'").replace("version:7}", "version:7},{secret:'XYZ',version:7}"),
            source("'ABC'").replace("%33+9", "%32+9"),
            source("'ABC'").replace("period:30", "period:31"),
            source("'ABC'").replace("algorithm:\"SHA1\"", "algorithm:\"SHA256\""),
            source("'ABC'").replace(")[0]", ")[1]"))
        for (script in invalid) {
            val error = runCatching { PublicWebTokenConfiguration.parse(script) }.exceptionOrNull()
            assertTrue(error is PublicWebTokenConfiguration.ConfigurationException)
            assertFalse(error.toString().contains(marker))
        }
    }

    @Test
    fun unknownPageInvalidTimeAndClosedConfigurationCannotGenerateRequests() {
        val config = PublicWebTokenConfiguration.parse(source("'ABC'"))
        assertTrue(runCatching { config.query(PublicWebTokenConfiguration.Reason.INITIAL,
            PlaybackAuthorizationClient.PageKind.UNKNOWN, 59_000, 59) }.exceptionOrNull() is PublicWebTokenConfiguration.ConfigurationException)
        assertTrue(runCatching { config.query(PublicWebTokenConfiguration.Reason.INITIAL,
            PlaybackAuthorizationClient.PageKind.DESKTOP, 59_000, Long.MAX_VALUE) }.exceptionOrNull() is PublicWebTokenConfiguration.ConfigurationException)
        config.close()
        assertTrue(runCatching { config.query(PublicWebTokenConfiguration.Reason.INITIAL,
            PlaybackAuthorizationClient.PageKind.DESKTOP, 59_000, 59) }.exceptionOrNull() is PublicWebTokenConfiguration.ConfigurationException)
    }

    private fun source(seed: String): String = """
        let cfg=[{secret:$seed,version:7}].map(e=>{var t;let r,i;return{secret:(t=e.secret,r=[],r="string"==typeof t?t.split("").map((e,t)=>e.charCodeAt(0)^t%33+9):t.map((e,t)=>e^t%33+9),i=Buffer.from(r.join(""),"utf8").toString("hex"),Secret.fromHex(i)),version:e.version}})[0],otp=new TOTP({period:30,algorithm:"SHA1",digits:6,secret:cfg.secret});
        function parameters(r,p,t,s){return{reason:r,productType:p,totp:t,totpServer:s,totpVer:String(cfg.version)}}
        const endpoint="/api/token";
    """.trimIndent()
}
