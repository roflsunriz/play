package io.github.playmusic.data.auth

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/** Normal sign-in with PKCE and an automatic loopback callback on this device. */
class BrowserAuthorizationClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
    private val dpopKeys: DpopKeys? = null,
) {
    class Pending internal constructor(
        val authorizationUrl: String,
        internal val redirectUri: String,
        internal val verifier: String,
        internal val state: String,
        internal val expiresAt: Long,
        internal val server: ServerSocket,
        internal val dpopKey: DpopProofs.Key? = null,
    ) : Closeable {
        override fun close() = server.close()
    }

    class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
        val tokenType: String = "Bearer",
        val grantedScopes: Set<String> = emptySet(),
    ) {
        init { require(tokenType.equals("Bearer", ignoreCase = true) || tokenType.equals("DPoP", ignoreCase = true)) }
        override fun toString(): String = "BrowserAuthorizationClient.Tokens"
    }

    suspend fun begin(): Pending {
        var opened: ServerSocket? = null
        try {
            return withContext(Dispatchers.IO) {
                val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also {
                    opened = it
                    it.soTimeout = 1_000
                }
                val redirect = "http://127.0.0.1:${server.localPort}/login"
                val verifier = randomValue(32)
                val state = randomValue(24)
                val challenge = base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                val dpopKey = dpopKeys?.getOrCreate()
                val parameters = mapOf("client_id" to DesktopClientProfile.CLIENT_ID, "response_type" to "code",
                    "redirect_uri" to redirect, "code_challenge_method" to "S256", "code_challenge" to challenge,
                    "state" to state, "scope" to SCOPES,
                    "creation_flow" to "desktop", "creation_point" to CREATION_POINT,
                    "utm_source" to "spotify", "utm_medium" to "desktop-win32", "utm_campaign" to "organic",
                    "flow_ctx" to "${java.util.UUID.randomUUID()}:${(now() / 1_000) + 21_600}") +
                    (dpopKey?.let { mapOf("dpop_jkt" to DpopProofs.thumbprint(it.x, it.y)) } ?: emptyMap())
                trace("callback listener ready")
                Pending("$AUTHORIZE_URL?${form(parameters)}", redirect, verifier, state, now() + LOGIN_TIMEOUT_MS,
                    server, dpopKey)
            }
        } catch (exception: Exception) {
            opened?.close()
            throw exception
        }
    }

    suspend fun awaitAuthorization(pending: Pending, completedMessage: String, onCallback: () -> Unit = {}): Tokens =
        withContext(Dispatchers.IO) {
            pending.use {
                while (now() < pending.expiresAt) {
                    coroutineContext.ensureActive()
                    val socket = try { pending.server.accept() } catch (_: SocketTimeoutException) { continue }
                    trace("callback connection accepted")
                    val result = socket.use { readCallback(it, pending, completedMessage) } ?: continue
                    trace("callback state verified")
                    withContext(Dispatchers.Main) { onCallback() }
                    if (result.error != null) throw AuthException("Login was declined")
                    val code = checkNotNull(result.code)
                    val token = request(mapOf(
                        "client_id" to DesktopClientProfile.CLIENT_ID,
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to pending.redirectUri,
                        "code_verifier" to pending.verifier,
                    ), pending.dpopKey, accessTokenHashFor = code)
                    require(!token.refreshToken.isNullOrBlank()) { "Login did not return refresh credentials" }
                    trace("authorization token received")
                    return@withContext token
                }
                throw AuthException("Login timed out. Sign in again")
            }
        }

    suspend fun refresh(refreshToken: String): Tokens {
        require(refreshToken.isNotBlank())
        // A rotated-away device key must not break sign-in: fall back to a plain refresh.
        val key = runCatching { dpopKeys?.current() }.getOrNull()
        return try {
            request(mapOf("client_id" to DesktopClientProfile.CLIENT_ID, "grant_type" to "refresh_token",
                "refresh_token" to refreshToken), key)
        } catch (error: AuthException) {
            if (key == null) throw error
            request(mapOf("client_id" to DesktopClientProfile.CLIENT_ID, "grant_type" to "refresh_token",
                "refresh_token" to refreshToken))
        }
    }

    private fun readCallback(socket: Socket, pending: Pending, message: String): Callback? {
        socket.soTimeout = 2_000
        val line = try {
            val input = socket.getInputStream()
            var remaining = 16_384
            fun readLine(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < 8_192 && remaining-- > 0) {
                    val next = input.read()
                    require(next >= 0) { "Incomplete HTTP request" }
                    if (next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                    bytes.write(next)
                }
                throw IllegalArgumentException("HTTP request is too large")
            }
            val request = readLine()
            while (readLine().isNotEmpty()) { /* Consume bounded headers before replying. */ }
            request
        } catch (_: java.io.IOException) { trace("callback HTTP read failed"); return null }
        catch (_: IllegalArgumentException) { trace("callback HTTP request rejected"); return null }
        val result = callback(line, pending.state)
        if (result == null) trace("callback parameters rejected")
        val html = if (result == null) "Invalid login response" else message
        val escaped = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = "<!doctype html><meta charset=utf-8><title>Play</title><p>$escaped</p>".toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 ${if (result == null) "400 Bad Request" else "200 OK"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
        return result
    }

    private suspend fun request(
        parameters: Map<String, String>,
        dpopKey: DpopProofs.Key? = null,
        accessTokenHashFor: String? = null,
    ): Tokens = withContext(Dispatchers.IO) {
        val accessTokenHash = accessTokenHashFor?.let {
            DpopProofs.base64Url(DpopProofs.sha256(it.toByteArray(Charsets.US_ASCII)))
        }
        var nonce: String? = null
        repeat(2) { attempt ->
            val connection = openConnection(URI(TOKEN_URL))
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                if (dpopKey != null) {
                    connection.setRequestProperty("DPoP", DpopProofs.proof(
                        dpopKey, "POST", TOKEN_URL, accessTokenHash = accessTokenHash, nonce = nonce))
                }
                connection.doOutput = true
                connection.outputStream.use { it.write(form(parameters).toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { reader ->
                        runCatching { JSONObject(reader.readText()).optString("error") }
                            .getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                    }
                    val serverNonce = connection.getHeaderField("DPoP-Nonce")
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) }
                    if (attempt == 0 && dpopKey != null && !serverNonce.isNullOrBlank()) {
                        nonce = serverNonce
                        return@repeat
                    }
                    throw AuthException("Login request failed ($status${error?.let { ": $it" }.orEmpty()})",
                        requiresLogin = parameters["grant_type"] == "refresh_token" && error == "invalid_grant")
                }
                val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val tokenType = (json.opt("token_type") as? String)?.takeIf {
                    it.equals("Bearer", ignoreCase = true) || it.equals("DPoP", ignoreCase = true)
                } ?: throw AuthException("Login returned an unsupported credential type")
                val refresh = if (json.isNull("refresh_token")) null else
                    (json.opt("refresh_token") as? String)?.takeIf { it.isNotBlank() && it != "null" }
                        ?: throw AuthException("Login returned invalid refresh credentials")
                trace("token bound type=$tokenType refresh credentials=${refresh != null}")
                return@withContext Tokens((json.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
                        ?: throw AuthException("Login returned invalid access credentials"),
                    refresh,
                    json.getLong("expires_in").also { require(it in 1..86_400) },
                    tokenType,
                    json.optString("scope").split(' ').filter(String::isNotBlank).toSet())
            } finally { connection.disconnect() }
        }
        throw AuthException("Login request failed (DPoP nonce retry exhausted)")
    }

    private fun randomValue(size: Int): String = base64(ByteArray(size).also(SecureRandom()::nextBytes))
    private fun trace(stage: String) {
        // Stages only: never log URLs, codes, verifiers, tokens, or account identifiers.
        if (io.github.playmusic.BuildConfig.DEBUG) android.util.Log.d("PlayBrowserLogin", stage)
    }
    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    internal class Callback(val code: String?, val error: String?)

    companion object {
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/oauth2/v2/auth"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val CREATION_POINT = "https://login.app.spotify.com/?client_id=" +
            DesktopClientProfile.CLIENT_ID + "&utm_source=spotify&utm_medium=desktop-win32&utm_campaign=organic"
        internal const val LOGIN_TIMEOUT_MS = 600_000L
        // The captured desktop browser authorization requests these scopes through /oauth2/v2/auth.
        // The older /authorize endpoint rejected transfer-auth-session for the same public client ID.
        private const val SCOPES = "app-remote-control playlist-modify playlist-modify-private " +
            "playlist-modify-public playlist-read playlist-read-collaborative playlist-read-private " +
            "streaming transfer-auth-session sts-content-management ugc-image-upload " +
            "user-follow-modify user-follow-read user-library-modify user-library-read user-modify " +
            "user-modify-playback-state user-modify-private user-personalized user-read-birthdate " +
            "user-read-currently-playing user-read-email user-read-play-history user-read-playback-position " +
            "user-read-playback-state user-read-private user-read-recently-played user-top-read"

        internal fun callback(requestLine: String, expectedState: String): Callback? = runCatching {
            val request = requestLine.split(' ')
            require(request.size == 3 && request[0] == "GET" && request[2].startsWith("HTTP/1."))
            val uri = URI(request[1])
            require(!uri.isAbsolute && uri.rawAuthority == null && uri.path == "/login" && uri.fragment == null)
            val values = linkedMapOf<String, String>()
            for (part in checkNotNull(uri.rawQuery).split('&')) {
                val pieces = part.split('=', limit = 2)
                require(pieces.size == 2)
                val name = URLDecoder.decode(pieces[0], "UTF-8")
                require(values.put(name, URLDecoder.decode(pieces[1], "UTF-8")) == null)
            }
            val state = values["state"] ?: return null
            require(MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray()))
            val code = values["code"]?.takeIf { it.isNotBlank() && it.length <= 4_096 }
            val error = values["error"]?.takeIf { it == "access_denied" }
            require((code != null) xor (error != null))
            Callback(code, error)
        }.getOrNull()
    }
}
