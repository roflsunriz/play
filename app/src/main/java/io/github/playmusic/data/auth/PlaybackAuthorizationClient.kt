package io.github.playmusic.data.auth

import io.github.playmusic.data.api.ServiceApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Acquires authorization for native playback using the signed-in account, without persisting web credentials. */
class PlaybackAuthorizationClient(
    private val calls: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun connect(ownBearer: String): Session = withTimeout(120_000) {
        val transport = Transport(calls)
        try {
            guarded(Stage.TRANSFER) {
                require(ownBearer.isNotBlank() && ownBearer.length <= 32_768 && ownBearer.none { it == '\r' || it == '\n' })
            }
            val grant = transport.request(Stage.TRANSFER, TRANSFER, "POST",
                DesktopClientProfile.headers + mapOf("Authorization" to "Bearer $ownBearer",
                    "Content-Type" to "application/json", "Cache-Control" to "no-cache, no-store, max-age=0"),
                JSONObject().put("url", OPEN).toString(), cookies = false)
            grant.requireSuccess(Stage.TRANSFER)
            val ott = guarded(Stage.TRANSFER) {
                val root = JSONObject(grant.body)
                val value = root.get("token") as? String ?: error("schema")
                require(value.isNotBlank() && value.length <= 32_768 && root.getLong("expires_in") > 0)
                value
            }
            val initial = transport.getFollowing(Stage.CSRF_PAGE, LOGIN)
            if (initial.url.host != "accounts.spotify.com") fail(Stage.CSRF_PAGE, Failure.REDIRECT)
            val csrf = guarded(Stage.CSRF_PAGE) {
                val root = JSONObject(scriptContents(initial.body, "__NEXT_DATA__"))
                val settings = root.getJSONObject("props").getJSONObject("pageProps").getJSONObject("csrfSettings")
                if (settings.getBoolean("enabled")) {
                    (settings.get("initialToken") as? String)?.takeIf { it.isNotBlank() && it.length <= 32_768 }
                        ?: error("schema")
                } else null
            }
            var currentCsrf = csrf
            suspend fun exchange(stage: Stage, path: String): JSONObject {
                repeat(2) { attempt ->
                    val headers = mapOf("Content-Type" to "text/plain;charset=UTF-8", "Origin" to ACCOUNTS,
                        "Referer" to LOGIN) + (currentCsrf?.let { mapOf("X-CSRF-Token" to it) } ?: emptyMap())
                    val reply = transport.request(stage, "$ACCOUNTS$path", "POST", headers,
                        JSONObject().put("token", ott).toString())
                    reply.csrfToken?.let {
                        if (it.length > 32_768) fail(stage, Failure.SIZE_LIMIT)
                        currentCsrf = it
                    }
                    if (reply.csrfValid == "false") {
                        if (attempt == 0 && !reply.csrfToken.isNullOrBlank()) return@repeat
                        fail(stage, Failure.CSRF, reply.status)
                    }
                    reply.requireSuccess(stage)
                    return guarded(stage) { JSONObject(reply.body) }
                }
                fail(stage, Failure.CSRF)
            }
            var result = exchange(Stage.VERIFY, "/api/login/ott/verify")
            if (result.optString("result") != "redirect") {
                if (result.optJSONObject("userInfo") == null) fail(Stage.VERIFY, Failure.SCHEMA)
                result = exchange(Stage.APPROVE, "/api/login/ott/approve")
            }
            val redirect = guarded(Stage.REDIRECT) {
                require(result.getString("result") == "redirect")
                result.getString("url")
            }
            val page = transport.getFollowing(Stage.REDIRECT, redirect)
            if (page.url.host != "open.spotify.com") fail(Stage.REDIRECT, Failure.REDIRECT)
            Session(transport, page.body)
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }

    /**
     * Opens a web session from an imported browser cookie, bypassing the transfer chain.
     * The cookie value is validated as RFC 6265 cookie-octets and never logged or exposed.
     */
    suspend fun connectWithCookie(spDc: String): Session = withTimeout(120_000) {
        guarded(Stage.WEB) { require(isValidWebCookie(spDc)) }
        val transport = Transport(calls)
        try {
            transport.seedCookie(Cookie.Builder().name("sp_dc").value(spDc)
                .hostOnlyDomain("open.spotify.com").path("/").build())
            val page = transport.getFollowing(Stage.WEB, OPEN)
            if (page.url.host != "open.spotify.com") fail(Stage.WEB, Failure.REDIRECT)
            Session(transport, page.body)
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }

    class Session internal constructor(private val transport: Transport, initialPage: String) : AutoCloseable {
        private val mutex = Mutex()
        private var page: String? = initialPage
        private var token: String? = null
        private var tokenExpiration: Long? = null
        private var tokenIsAnonymous: Boolean? = null
        val pageKind: PageKind = when {
            initialPage.contains("/mobile-web-player/") -> PageKind.MOBILE
            initialPage.contains("/web-player/") -> PageKind.DESKTOP
            else -> PageKind.UNKNOWN
        }
        /** Public immutable bundle URLs only; excludes query strings, fragments and account hosts. */
        val publicMainScriptUrls: List<String> = Regex("<script\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(initialPage).mapNotNull { it.groupValues[1].toHttpUrlOrNull() }.filter {
                it.isHttps && it.port == 443 && it.host == "open.spotifycdn.com" && it.username.isEmpty() &&
                    it.password.isEmpty() && it.query == null && it.fragment == null &&
                    it.encodedPath.matches(Regex("/cdn/build/(?:web-player|mobile-web-player)/(?:web-player|mobile-web-player)\\.[A-Za-z0-9]+\\.js"))
            }.map(HttpUrl::toString).distinct().toList()

        fun configuration(): AppServerConfig = guarded(Stage.CONFIGURATION) {
            transport.checkOpen()
            val encoded = scriptContents(checkNotNull(page), "appServerConfig").filterNot(Char::isWhitespace)
            require(encoded.length <= 131_072)
            val decoded = encoded.decodeBase64() ?: error("base64")
            require(decoded.size <= 98_304)
            val root = JSONObject(decoded.utf8())
            AppServerConfig(publicVersion(root, "clientVersion"), publicVersion(root, "buildVersion"),
                positiveInteger(root, "serverTime"), pageKind)
        }

        suspend fun serverTime(): Long = mutex.withLock {
            val response = transport.request(Stage.TOKEN, "${OPEN}api/server-time")
            response.requireSuccess(Stage.TOKEN)
            guarded(Stage.TOKEN) { positiveInteger(JSONObject(response.body), "serverTime") }
        }

        /** All five values must come from the inspected client contract; this method invents none. */
        suspend fun requestToken(query: TokenQuery, forceRefresh: Boolean = false): TokenObservation = mutex.withLock {
            transport.checkOpen()
            token = null
            tokenExpiration = null
            tokenIsAnonymous = null
            val url = OPEN.toHttpUrl().newBuilder().encodedPath("/api/token").apply {
                query.parameters().forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            val response = transport.request(Stage.TOKEN, url.toString(),
                headers = if (forceRefresh) mapOf("X-Spotify-Tr" to "true") else emptyMap())
            if (response.status != 200) return@withLock TokenObservation(response.status, false, null, null)
            guarded(Stage.TOKEN) {
                val root = JSONObject(response.body)
                val parsedToken = (root.opt("accessToken") as? String)?.takeIf { it.isNotBlank() && it.length <= 32_768 }
                val parsedExpiration = (root.opt("accessTokenExpirationTimestampMs") as? Number)?.toString()?.toLongOrNull()
                val parsedAnonymous = root.opt("isAnonymous") as? Boolean
                val clientId = if (root.has("clientId") && !root.isNull("clientId")) {
                    (root.get("clientId") as? String)?.takeIf { it.matches(Regex("[A-Fa-f0-9]{32}")) } ?: error("client ID schema")
                } else null
                token = parsedToken
                tokenExpiration = parsedExpiration
                tokenIsAnonymous = parsedAnonymous
                TokenObservation(response.status, token != null, tokenIsAnonymous, tokenExpiration, clientId)
            }
        }

        /** Uses the current playback token transiently. Cookies are never exposed. */
        suspend fun <T> withAccessToken(block: suspend (String) -> T): T = mutex.withLock {
            transport.checkOpen()
            val current = token
            if (current == null || tokenIsAnonymous != false || (tokenExpiration ?: 0) <= System.currentTimeMillis()) {
                fail(Stage.TOKEN, Failure.UNUSABLE_TOKEN)
            }
            try { block(current) }
            catch (error: CancellationException) { throw error }
            catch (error: PlaybackAuthorizationException) { throw error }
            catch (_: Exception) { fail(Stage.TOKEN, Failure.TOKEN_USE) }
        }

        override fun close() {
            transport.close()
            page = null
            token = null
            tokenExpiration = null
            tokenIsAnonymous = null
        }

        override fun toString(): String = "PlaybackAuthorizationClient.Session"
    }

    class TokenQuery(
        private val reason: String,
        private val productType: String,
        private val totp: String,
        private val totpServer: String,
        private val totpVer: String,
    ) {
        init {
            guarded(Stage.TOKEN) {
                require(reason.matches(Regex("[a-z-]{1,32}")) && productType.matches(Regex("[a-z-]{1,64}")))
                require(totp.matches(Regex("[0-9]{6}")))
                require(totpServer == "unavailable" || totpServer.matches(Regex("[0-9]{6}")))
                require(totpVer.matches(Regex("[0-9]{1,10}")))
            }
        }
        internal fun parameters(): Map<String, String> = mapOf("reason" to reason, "productType" to productType,
            "totp" to totp, "totpServer" to totpServer, "totpVer" to totpVer)
        override fun toString(): String = "PlaybackAuthorizationClient.TokenQuery"
    }

    data class AppServerConfig(val clientVersion: String, val buildVersion: String, val serverTimeSeconds: Long, val pageKind: PageKind)
    data class TokenObservation(val status: Int, val hasAccessToken: Boolean, val isAnonymous: Boolean?, val expiresAtEpochMs: Long?, val clientId: String? = null)
    enum class PageKind { DESKTOP, MOBILE, UNKNOWN }
    enum class Stage { TRANSFER, CSRF_PAGE, VERIFY, APPROVE, REDIRECT, CONFIGURATION, TOKEN, STATE, WEB }
    enum class Failure { NETWORK, HTTP, SCHEMA, REDIRECT, CSRF, SIZE_LIMIT, CLOSED, UNUSABLE_TOKEN, TOKEN_USE }
    class PlaybackAuthorizationException internal constructor(
        val stage: Stage,
        val failure: Failure,
        val status: Int? = null,
        val exceptionClass: String? = null,
        val originFrame: String? = null,
    ) : Exception("Playback authorization: $stage/$failure" + (status?.let { " (HTTP $it)" } ?: "") +
        (exceptionClass?.let { " [$it]" } ?: "") + (originFrame?.let { " at $it" } ?: ""))

    internal class Transport(private val calls: Call.Factory) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val activeCall = AtomicReference<Call?>()
        private val cookies = mutableListOf<Cookie>()

        fun checkOpen() { if (closed.get()) fail(Stage.STATE, Failure.CLOSED) }

        fun seedCookie(cookie: Cookie) = synchronized(this@Transport) {
            checkOpen()
            this@Transport.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this@Transport.cookies += cookie
        }

        suspend fun request(
            stage: Stage,
            target: String,
            method: String = "GET",
            headers: Map<String, String> = emptyMap(),
            body: String? = null,
            cookies: Boolean = true,
        ): Reply = withContext(Dispatchers.IO) {
            checkOpen()
            currentCoroutineContext().ensureActive()
            try {
                val url = checkedUrl(stage, target, allowTransfer = !cookies)
                val builder = Request.Builder().url(url).header("User-Agent", BROWSER_AGENT).header("Accept", "*/*")
                    .header("Cache-Control", "no-cache, no-store, max-age=0")
                headers.forEach { (name, value) -> builder.header(name, value) }
                if (cookies) {
                    if (headers.keys.any { it.equals("Authorization", true) || it.equals("Client-Token", true) }) {
                        fail(stage, Failure.SCHEMA)
                    }
                    val values = synchronized(this@Transport) {
                        this@Transport.cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
                        this@Transport.cookies.filter { it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
                    }
                    if (values.length > 16_384) fail(stage, Failure.SIZE_LIMIT)
                    if (values.isNotEmpty()) builder.header("Cookie", values)
                }
                val payload = body?.toByteArray(Charsets.UTF_8)
                if (payload != null && payload.size > 65_536) fail(stage, Failure.SIZE_LIMIT)
                builder.method(method, payload?.toRequestBody(headers["Content-Type"]?.toMediaType()))
                val call = calls.newCall(builder.build())
                activeCall.set(call)
                if (closed.get()) { call.cancel(); checkOpen() }
                try {
                    call.execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        checkOpen()
                        if (cookies) synchronized(this@Transport) {
                            if (closed.get()) fail(Stage.STATE, Failure.CLOSED)
                            Cookie.parseAll(url, response.headers).forEach { cookie ->
                                this@Transport.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                                if (cookie.expiresAt > System.currentTimeMillis()) this@Transport.cookies += cookie
                            }
                            if (this@Transport.cookies.size > 64) fail(stage, Failure.SIZE_LIMIT)
                        }
                        val maximum = if (stage in setOf(Stage.REDIRECT, Stage.CONFIGURATION, Stage.CSRF_PAGE, Stage.WEB)) 2_097_152 else 131_072
                        val bytes = response.body?.byteStream()?.use { input ->
                            val result = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (result.size() + count > maximum) fail(stage, Failure.SIZE_LIMIT)
                                result.write(buffer, 0, count)
                            }
                            result.toByteArray()
                        } ?: byteArrayOf()
                        Reply(url, response.code, bytes.toString(Charsets.UTF_8), response.header("Location"),
                            response.header("X-CSRF-Token"), response.header("X-CSRF-Valid"))
                    }
                } finally { activeCall.compareAndSet(call, null) }
            } catch (error: CancellationException) { throw error }
            catch (error: PlaybackAuthorizationException) { throw error }
            catch (error: Exception) { networkFailure(stage, error) }
        }

        suspend fun getFollowing(stage: Stage, start: String): Reply {
            var target = start
            repeat(8) {
                val reply = request(stage, target)
                if (reply.status !in setOf(301, 302, 303, 307, 308)) {
                    reply.requireSuccess(stage)
                    return reply
                }
                target = guarded(stage) {
                    val next = reply.url.resolve(checkNotNull(reply.location)) ?: error("redirect")
                    checkedUrl(stage, next.toString()).toString()
                }
            }
            fail(stage, Failure.REDIRECT)
        }

        override fun close() {
            closed.set(true)
            activeCall.getAndSet(null)?.cancel()
            synchronized(this@Transport) { cookies.clear() }
        }
        override fun toString(): String = "PlaybackAuthorizationClient.Transport"
    }

    internal class Reply(val url: HttpUrl, val status: Int, val body: String, val location: String?,
        val csrfToken: String?, val csrfValid: String?) {
        fun requireSuccess(stage: Stage) { if (status !in 200..299) fail(stage, Failure.HTTP, status) }
        override fun toString(): String = "PlaybackAuthorizationClient.Reply"
    }

    companion object {
        // RFC 6265 cookie-octets, without DQUOTE/comma/semicolon/backslash.
        private val COOKIE_OCTETS = (0x21..0x7E).filter { it !in listOf(0x22, 0x2C, 0x3B, 0x5C) }.toSet()

        /** Shared import validation so the UI and the client reject the same values. */
        internal fun isValidWebCookie(value: String): Boolean =
            value.length in 1..4096 && value.all { it.code in COOKIE_OCTETS }
        const val ACCOUNTS = "https://accounts.spotify.com"
        const val LOGIN = "$ACCOUNTS/login/ott/v2"
        const val OPEN = "https://open.spotify.com/"
        const val TRANSFER = "${ServiceApiClient.GAE2_BASE}/sessiontransfer/v1/token"
        val BROWSER_AGENT = DesktopClientProfile.headers.getValue("User-Agent").replace(" Spotify/${DesktopClientProfile.VERSION}", "")
        fun checkedUrl(stage: Stage, value: String, allowTransfer: Boolean = false): HttpUrl {
            val url = try { value.toHttpUrl() } catch (_: Exception) { fail(stage, Failure.REDIRECT) }
            val allowed = if (allowTransfer) url.toString() == TRANSFER
                else url.host in setOf("accounts.spotify.com", "open.spotify.com")
            if (value.length > 32_768 || !url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || !allowed) {
                fail(stage, Failure.REDIRECT)
            }
            return url
        }
        fun scriptContents(html: String, id: String): String {
            val opening = Regex("<script\\b[^>]*\\bid=[\"']${Regex.escape(id)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)
                ?: error("missing script")
            val end = html.indexOf("</script", opening.range.last + 1, ignoreCase = true)
            require(end >= 0)
            return html.substring(opening.range.last + 1, end).trim()
        }
        fun publicVersion(root: JSONObject, name: String): String =
            (root.get(name) as? String)?.takeIf { it.length in 1..256 && it.matches(Regex("[A-Za-z0-9._+/-]+")) }
                ?: error("version schema")
        fun positiveInteger(root: JSONObject, name: String): Long =
            (root.get(name) as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: error("integer schema")
        fun fail(stage: Stage, failure: Failure, status: Int? = null): Nothing = throw PlaybackAuthorizationException(stage, failure, status)
        fun networkFailure(stage: Stage, error: Exception): Nothing {
            val classPattern = Regex("[A-Za-z0-9_.$]{1,256}")
            val methodPattern = Regex("[A-Za-z0-9_$<>-]{1,128}")
            val exceptionClass = error.javaClass.name.takeIf { it.matches(classPattern) }
            val frame = error.stackTrace.firstOrNull()?.takeIf {
                it.className.matches(classPattern) && it.methodName.matches(methodPattern)
            }?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            throw PlaybackAuthorizationException(stage, Failure.NETWORK, exceptionClass = exceptionClass, originFrame = frame)
        }
        inline fun <T> guarded(stage: Stage, block: () -> T): T = try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: PlaybackAuthorizationException) { throw error }
        catch (_: Exception) { fail(stage, Failure.SCHEMA) }
    }
}
