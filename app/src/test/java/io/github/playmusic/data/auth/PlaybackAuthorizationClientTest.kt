package io.github.playmusic.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class PlaybackAuthorizationClientTest {
    @Test
    fun ownBearerAndSsoCookiesRemainInTheirRespectiveOrigins() = runBlocking {
        val server = Server()
        server.diagnostic.connect(BEARER).use { session ->
            assertEquals(PlaybackAuthorizationClient.PageKind.DESKTOP, session.pageKind)
            assertEquals(listOf("https://open.spotifycdn.com/cdn/build/web-player/web-player.test.js"), session.publicMainScriptUrls)
            val configuration = session.configuration()
            assertEquals("test.1", configuration.clientVersion)
            assertEquals("web-test_1", configuration.buildVersion)
            assertEquals(1_700_000_000L, configuration.serverTimeSeconds)
            assertEquals(1_700_000_005L, session.serverTime())
            val observation = session.requestToken(query())
            assertTrue(observation.hasAccessToken)
            assertEquals(false, observation.isAnonymous)
            assertEquals(PUBLIC_CLIENT_ID, observation.clientId)
            assertTrue(session.withAccessToken { it == WEB_TOKEN })
            val tokenUseFailure = runCatching { session.withAccessToken { throw IOException("private $it") } }.exceptionOrNull()
            assertTrue(tokenUseFailure is PlaybackAuthorizationClient.PlaybackAuthorizationException)
            assertFalse(tokenUseFailure.toString().contains(WEB_TOKEN))
            assertNull(tokenUseFailure?.cause)
            assertFalse(session.toString().contains(WEB_TOKEN))
            assertFalse(observation.toString().contains(WEB_TOKEN))
        }
        val transfer = server.requests.single { it.url.host == "gae2-spclient.spotify.com" }
        assertEquals("Bearer $BEARER", transfer.header("Authorization"))
        assertNull(transfer.header("Cookie"))
        assertEquals("https://open.spotify.com/", JSONObject(body(transfer)).getString("url"))
        val verify = server.requests.single { it.url.encodedPath == "/api/login/ott/verify" }
        assertEquals(OTT, JSONObject(body(verify)).getString("token"))
        assertEquals(CSRF, verify.header("X-CSRF-Token"))
        assertTrue(verify.header("Cookie").orEmpty().contains("account-only=private-account-cookie"))
        for (request in server.requests.filter { it.url.host != "gae2-spclient.spotify.com" }) {
            assertNull(request.header("Authorization"))
            assertNull(request.header("Client-Token"))
        }
        val open = server.requests.first { it.url.host == "open.spotify.com" }
        assertTrue(open.header("Cookie").orEmpty().contains("shared-session=private-shared-cookie"))
        assertFalse(open.header("Cookie").orEmpty().contains("account-only"))
        assertNull(open.header("X-CSRF-Token"))
        val token = server.requests.single { it.url.encodedPath == "/api/token" }
        assertNull(token.header("X-Spotify-Tr"))
        assertEquals(setOf("reason", "productType", "totp", "totpServer", "totpVer"), token.url.queryParameterNames)
        assertFalse(query().toString().contains("123456"))
    }

    @Test
    fun forcedRefreshUsesTheObservedHeaderWithoutAddingBearerAuthorization() = runBlocking {
        val server = Server()
        server.diagnostic.connect(BEARER).use { session ->
            session.requestToken(query(), forceRefresh = true)
        }
        val request = server.requests.single { it.url.encodedPath == "/api/token" }
        assertEquals("true", request.header("X-Spotify-Tr"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Client-Token"))
    }

    @Test
    fun csrfRotationRetriesOnceAndApprovalUsesTheUpdatedValue() = runBlocking {
        val server = Server().apply { rotateCsrf = true; needsApproval = true }
        server.diagnostic.connect(BEARER).close()
        val attempts = server.requests.filter { it.url.encodedPath == "/api/login/ott/verify" }
        assertEquals(2, attempts.size)
        assertEquals(CSRF, attempts[0].header("X-CSRF-Token"))
        assertEquals("rotated-csrf", attempts[1].header("X-CSRF-Token"))
        assertEquals(body(attempts[0]), body(attempts[1]))
        val approved = server.requests.single { it.url.encodedPath == "/api/login/ott/approve" }
        assertEquals("rotated-csrf", approved.header("X-CSRF-Token"))
        assertEquals(OTT, JSONObject(body(approved)).getString("token"))
    }

    @Test
    fun persistentCsrfFailureAndForeignRedirectsStopWithoutLeakingSecrets() = runBlocking {
        val csrfServer = Server().apply { rejectCsrf = true }
        val csrfError = runCatching { csrfServer.diagnostic.connect(BEARER) }.exceptionOrNull()
        assertTrue(csrfError is PlaybackAuthorizationClient.PlaybackAuthorizationException)
        assertEquals(PlaybackAuthorizationClient.Failure.CSRF, (csrfError as PlaybackAuthorizationClient.PlaybackAuthorizationException).failure)
        assertEquals(2, csrfServer.requests.count { it.url.encodedPath == "/api/login/ott/verify" })
        for (redirect in listOf("https://outside.invalid/?secret=$OTT", "http://open.spotify.com/",
            "https://open.spotify.com:8443/", "https://private-user@open.spotify.com/", "https://accounts.spotify.com.outside.invalid/")) {
            val server = Server().apply { this.redirect = redirect }
            val error = runCatching { server.diagnostic.connect(BEARER) }.exceptionOrNull()
            assertTrue(error is PlaybackAuthorizationClient.PlaybackAuthorizationException)
            assertFalse(error.toString().contains(OTT))
            assertNull(error?.cause)
            assertFalse(server.requests.any { it.url.host == "outside.invalid" || it.url.port == 8443 })
        }
        val transferServer = Server().apply { redirectTransfer = true }
        val transferError = runCatching { transferServer.diagnostic.connect(BEARER) }.exceptionOrNull()
        assertTrue(transferError is PlaybackAuthorizationClient.PlaybackAuthorizationException)
        assertEquals(1, transferServer.requests.size)
    }

    @Test
    fun missingInvalidAndOversizedResponsesAreSanitized() = runBlocking {
        for (invalid in listOf("{broken $OTT", """{"token":"$OTT","expires_in":0}""", "x".repeat(131_073))) {
            val server = Server().apply { transferBody = invalid }
            val error = runCatching { server.diagnostic.connect(BEARER) }.exceptionOrNull()
            assertTrue(error is PlaybackAuthorizationClient.PlaybackAuthorizationException)
            assertFalse(error.toString().contains(OTT))
            assertFalse(error.toString().contains(BEARER))
            assertNull(error?.cause)
        }
        val failing = PlaybackAuthorizationClient(object : Call.Factory {
            override fun newCall(request: Request): Call = throw IOException("secret $BEARER $OTT")
        })
        val error = runCatching { failing.connect(BEARER) }.exceptionOrNull()
        assertTrue(error is PlaybackAuthorizationClient.PlaybackAuthorizationException)
        assertEquals("java.io.IOException", (error as PlaybackAuthorizationClient.PlaybackAuthorizationException).exceptionClass)
        assertTrue(error.originFrame.orEmpty().contains(".newCall:"))
        assertFalse(error.toString().contains(BEARER))
        assertFalse(error.toString().contains(OTT))
        assertNull(error.cause)
    }

    @Test
    fun sessionsAreIsolatedAndClosingPreventsFurtherUse() = runBlocking {
        val server = Server()
        val first = server.diagnostic.connect(BEARER)
        server.diagnostic.connect(BEARER).use { second ->
            assertEquals(first.configuration(), second.configuration())
        }
        assertTrue(server.requests.filter { it.url.encodedPath == "/login/ott/v2" }.all { it.header("Cookie") == null })
        first.close()
        val count = server.requests.size
        assertTrue(runCatching { first.configuration() }.exceptionOrNull() is PlaybackAuthorizationClient.PlaybackAuthorizationException)
        assertTrue(runCatching { first.requestToken(query()) }.exceptionOrNull() is PlaybackAuthorizationClient.PlaybackAuthorizationException)
        assertEquals(count, server.requests.size)
    }

    @Test
    fun anonymousAndExpiredTokensCannotBeUsedAsAuthenticatedPlaybackCredentials() = runBlocking {
        for (anonymous in listOf(true, false)) {
            val server = Server().apply { anonymousToken = anonymous; expiredToken = !anonymous }
            server.diagnostic.connect(BEARER).use { session ->
                assertTrue(session.requestToken(query()).hasAccessToken)
                val failure = runCatching { session.withAccessToken { error("Must not enter this callback") } }.exceptionOrNull()
                assertTrue(failure is PlaybackAuthorizationClient.PlaybackAuthorizationException)
                assertEquals(PlaybackAuthorizationClient.Failure.UNUSABLE_TOKEN,
                    (failure as PlaybackAuthorizationClient.PlaybackAuthorizationException).failure)
            }
        }
    }

    private class Server {
        val requests = mutableListOf<Request>()
        var rotateCsrf = false
        var rejectCsrf = false
        var needsApproval = false
        var redirectTransfer = false
        var anonymousToken = false
        var expiredToken = false
        var redirect = "https://open.spotify.com/"
        var transferBody = """{"token":"$OTT","expires_in":299}"""
        val diagnostic = PlaybackAuthorizationClient(object : Call.Factory {
            override fun newCall(request: Request) = FakeCall(request, ::reply).also { requests += request }
        })

        private fun reply(request: Request): Response {
            return when (request.url.encodedPath) {
                "/sessiontransfer/v1/token" -> if (redirectTransfer) response(request, "", 302, mapOf("Location" to redirect))
                    else response(request, transferBody)
                "/login/ott/v2" -> response(request, """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"csrfSettings":{"enabled":true,"initialToken":"$CSRF"}}}}</script></html>""",
                    headers = mapOf("Set-Cookie" to "account-only=private-account-cookie; Path=/; Secure; HttpOnly"))
                "/api/login/ott/verify" -> {
                    val first = requests.count { it.url.encodedPath == "/api/login/ott/verify" } == 1
                    when {
                        rejectCsrf || rotateCsrf && first -> response(request, "{\"error\":\"$OTT\"}", 403,
                            mapOf("X-CSRF-Token" to "rotated-csrf", "X-CSRF-Valid" to "false"))
                        needsApproval -> response(request, """{"result":"approval","userInfo":{"username":"private-user"}}""")
                        else -> loginSuccess(request)
                    }
                }
                "/api/login/ott/approve" -> loginSuccess(request)
                "/" -> response(request, page())
                "/api/server-time" -> response(request, """{"serverTime":1700000005}""")
                "/api/token" -> response(request, """{"accessToken":"$WEB_TOKEN","clientId":"$PUBLIC_CLIENT_ID","isAnonymous":$anonymousToken,"accessTokenExpirationTimestampMs":${if (expiredToken) 1 else System.currentTimeMillis() + 60_000}}""")
                else -> error("Unexpected synthetic request")
            }
        }
        private fun loginSuccess(request: Request) = response(request, JSONObject().put("result", "redirect").put("url", redirect).toString(),
            headers = mapOf("Set-Cookie" to "shared-session=private-shared-cookie; Domain=spotify.com; Path=/; Secure; HttpOnly"))
    }

    private class FakeCall(private val source: Request, private val response: (Request) -> Response) : Call {
        private var executed = false
        private var cancelled = false
        override fun request() = source
        override fun execute(): Response { executed = true; return response(source) }
        override fun enqueue(responseCallback: Callback) { responseCallback.onResponse(this, execute()) }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(source, response)
    }

    companion object {
        private const val BEARER = "synthetic-private-native-bearer"
        private const val OTT = "synthetic-private-ott"
        private const val CSRF = "synthetic-private-csrf"
        private const val WEB_TOKEN = "synthetic-private-web-token"
        private const val PUBLIC_CLIENT_ID = "0123456789abcdef0123456789abcdef"
        private fun query() = PlaybackAuthorizationClient.TokenQuery("init", "web-player", "123456", "654321", "999")
        private fun body(request: Request): String = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
        private fun page(): String {
            val config = """{"clientVersion":"test.1","buildVersion":"web-test_1","serverTime":1700000000}"""
            val encoded = Base64.getEncoder().encodeToString(config.toByteArray())
            return """<script id="appServerConfig" type="text/plain">$encoded</script><script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.test.js"></script>"""
        }
        private fun response(request: Request, body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic response")
                .body(body.toResponseBody("text/plain;charset=UTF-8".toMediaType())).apply {
                    headers.forEach { (name, value) -> addHeader(name, value) }
                }.build()
    }
}
