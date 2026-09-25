package io.github.playmusic.data.auth

import io.github.playmusic.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Debug-only transfer probe. Logs classifications only; never tokens, URLs, or account ids. */
object PlaybackAuthorizationDiagnostics {
    suspend fun run(container: AppContainer): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        fun emit(line: String): String {
            android.util.Log.i("PlayAuthDiag", line)
            lines += line
            return line
        }
        val session = container.sessionStore.loadSession()
        emit("session present=${session != null} browser=${session?.refreshToken != null} expiredSoon=${session?.expiresSoon() == true} storedCred=${session?.storedCredential != null}")
        if (session == null) return@withContext lines
        val stored = session.storedCredential
        if (stored != null) {
            val login = runCatching {
                container.login5Client.loginWithStoredCredential(
                    username = session.username,
                    storedCredential = stored,
                    deviceId = container.sessionStore.loadDeviceId(),
                )
            }
            val outcome = login.getOrNull()
            if (outcome is Login5Client.LoginOutcome.Success) {
                emit("login5 ${probe(outcome.accessToken)}")
            } else {
                emit("login5-failed ${login.exceptionOrNull()?.javaClass?.simpleName ?: outcome?.javaClass?.simpleName}")
            }
        }
        val bearer = runCatching { container.sessionManager.accessToken(false) }.getOrElse {
            emit("bearer-failed ${describe(it)}")
            return@withContext lines
        }
        emit("baseline ${probe(bearer)}")
        emit("newver ${probe(bearer, appVersion = "1.3.0.277")}")
        emit("otturl ${probe(bearer, url = "https://accounts.spotify.com/login/ott/v2#token=$bearer")}")
        emit("ottbare ${probe(bearer, url = "https://accounts.spotify.com/login/ott/v2")}")
        emit("ottempty ${probe(bearer, url = "https://accounts.spotify.com/login/ott/v2#token=")}")
        emit("wghost ${probe(bearer, host = "https://spclient.wg.spotify.com")}")
        emit("wgott ${probe(bearer, host = "https://spclient.wg.spotify.com", url = "https://accounts.spotify.com/login/ott/v2#token=$bearer")}")
        emit("jdk ${probeJdk(bearer)}")
        val desktop = runCatching {
            ClientTokenClient(
                userAgent = DesktopClientProfile.headers.getValue("User-Agent"),
                clientVersion = DesktopClientProfile.VERSION,
            ).acquire(DesktopClientProfile.CLIENT_ID, container.sessionStore.loadDeviceId()).token
        }
        if (desktop.isSuccess) {
            emit("desktop-ct ${probe(bearer, mapOf("client-token" to desktop.getOrThrow()))}")
        } else {
            emit("desktop-ct-failed ${describe(desktop.exceptionOrNull()!!)}")
        }
        val androidToken = runCatching { container.sessionManager.clientToken(false) }
        if (androidToken.isSuccess) {
            emit("android-ct ${probe(bearer, mapOf("client-token" to androidToken.getOrThrow()))}")
        } else {
            emit("android-ct-failed ${describe(androidToken.exceptionOrNull()!!)}")
        }
        val refreshed = runCatching { container.sessionManager.accessToken(true) }
        if (refreshed.isSuccess) {
            emit("refreshed ${probe(refreshed.getOrThrow())}")
        } else {
            emit("refresh-failed ${describe(refreshed.exceptionOrNull()!!)}")
        }
        emit(dpopRefreshProbe(container))
        emit(dpopTransferProbe(bearer))
        emit(cookieChainProbe(container))
        lines
    }

    /**
     * Exercises the real product path (transfer, then the imported-cookie fallback) and logs
     * only the outcome classification. Warms the cache as a side effect.
     */
    private suspend fun cookieChainProbe(container: AppContainer): String {
        if (container.sessionStore.loadSession()?.webCookie == null) {
            return "cookie-path no-cookie-saved"
        }
        return try {
            container.playbackAuthorization.headers(
                java.net.URI("https://gae2-spclient.spotify.com/widevine-license/v1/audio/license"))
            "cookie-path headers-ok"
        } catch (error: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
            "cookie-path ${error.stage}/${error.failure} status=${error.status ?: "-"}"
        } catch (error: IllegalStateException) {
            "cookie-path check-failed ${(error.message ?: "-").take(64)}"
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            "cookie-path ${error.javaClass.simpleName}"
        }
    }

    /**
     * Transfer with the DPoP scheme and the stored device key (the key bound at sign-in).
     * A fresh key is never used: the server rejects proofs from any other key.
     */
    private fun dpopTransferProbe(accessToken: String): String {
        val key = DpopKeyStore().current() ?: return "dpop-transfer no-device-key"
        var currentNonce: String? = null
        repeat(2) { attempt ->
            val result = probeTransfer(
                authorization = "DPoP $accessToken",
                proof = DpopProofs.proof(key, "POST",
                    "https://gae2-spclient.spotify.com/sessiontransfer/v1/token", nonce = currentNonce),
            )
            if (result is TransferResult.RetryWithNonce && attempt == 0) {
                currentNonce = result.nonce
            } else {
                return "dpop-transfer ${result.describe()}"
            }
        }
        return "dpop-transfer nonce-retry-exhausted"
    }

    /**
     * Refreshes with a DPoP proof and, when the server binds the token, probes transfer with the
     * DPoP scheme. The rotated session is persisted exactly like a normal refresh so the saved
     * sign-in stays healthy. Logs token types and error codes only.
     */
    private fun dpopRefreshProbe(container: AppContainer): String {
        val session = container.sessionStore.loadSession()
            ?: return "dpop-skipped no-session"
        val refreshToken = session.refreshToken
            ?: return "dpop-skipped no-refresh-token"
        val key = DpopKeyStore().current()
            ?: return "dpop-skipped no-device-key"
        var nonce: String? = null
        repeat(2) { attempt ->
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = java.net.URI("https://accounts.spotify.com/api/token")
                    .toURL().openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("DPoP", DpopProofs.proof(
                    key, "POST", "https://accounts.spotify.com/api/token", nonce = nonce))
                val form = "client_id=${encode(DesktopClientProfile.CLIENT_ID)}" +
                    "&grant_type=refresh_token&refresh_token=${encode(refreshToken)}"
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { reader ->
                        runCatching { JSONObject(reader.readText()).optString("error") }
                            .getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                    } ?: "-"
                    val serverNonce = connection.getHeaderField("DPoP-Nonce")
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) }
                    if (attempt == 0 && !serverNonce.isNullOrBlank()) {
                        nonce = serverNonce
                        return@repeat
                    }
                    return "dpop-refresh status=$status error=$error"
                }
                val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val tokenType = (json.opt("token_type") as? String).orEmpty()
                val accessToken = json.opt("access_token") as? String
                if (accessToken.isNullOrBlank()) return "dpop-refresh status=$status token_type=$tokenType no-token"
                val rotated = (json.opt("refresh_token") as? String)
                    ?.takeIf { it.isNotBlank() } ?: refreshToken
                val expiresIn = (json.opt("expires_in") as? Number)?.toLong()?.takeIf { it in 1..86_400 }
                    ?: return "dpop-refresh status=$status token_type=$tokenType bad-expiry"
                container.sessionStore.saveSession(session.copy(
                    accessToken = accessToken,
                    refreshToken = rotated,
                    expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000L,
                ))
                if (!tokenType.equals("DPoP", ignoreCase = true)) {
                    return "dpop-refresh status=$status token_type=$tokenType"
                }
                return "dpop-transfer ${probeDpop(accessToken, key, nonce)}"
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                return "dpop-refresh network ${error.javaClass.simpleName}"
            } finally {
                connection?.disconnect()
            }
        }
        return "dpop-refresh nonce-retry-exhausted"
    }

    /** Transfer probe with the DPoP authorization scheme and a fresh proof per attempt. */
    private fun probeDpop(accessToken: String, key: DpopProofs.Key, nonce: String?): String {
        var currentNonce = nonce
        repeat(2) { attempt ->
            val result = probeTransfer(
                authorization = "DPoP $accessToken",
                proof = DpopProofs.proof(key, "POST",
                    "https://gae2-spclient.spotify.com/sessiontransfer/v1/token", nonce = currentNonce),
            )
            if (result is TransferResult.RetryWithNonce && attempt == 0) {
                currentNonce = result.nonce
            } else {
                return result.describe()
            }
        }
        return "nonce-retry-exhausted"
    }

    private sealed interface TransferResult {
        fun describe(): String
        data class Done(val text: String) : TransferResult {
            override fun describe(): String = text
        }
        data class RetryWithNonce(val nonce: String) : TransferResult {
            override fun describe(): String = "nonce-retry-exhausted"
        }
    }

    private fun probeTransfer(authorization: String, proof: String): TransferResult {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = java.net.URI("https://gae2-spclient.spotify.com/sessiontransfer/v1/token")
                .toURL().openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "*/*")
            DesktopClientProfile.headers.forEach(connection::setRequestProperty)
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("DPoP", proof)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            val payload = JSONObject().put("url", "https://open.spotify.com/").toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status == 400 || status == 401) {
                val serverNonce = connection.getHeaderField("DPoP-Nonce")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) }
                if (!serverNonce.isNullOrBlank()) return TransferResult.RetryWithNonce(serverNonce)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val type = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim().orEmpty().ifBlank { "-" }
            val www = connection.getHeaderField("WWW-Authenticate")?.take(80).orEmpty()
            val hasNonce = connection.getHeaderField("DPoP-Nonce") != null
            if (status in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val hasToken = (json?.opt("token") as? String)?.isNotBlank() == true
                val expires = (json?.opt("expires_in") as? Number)?.toLong()
                TransferResult.Done("status=$status type=$type hasToken=$hasToken expiresIn=${expires ?: "-"}")
            } else {
                TransferResult.Done("status=$status type=$type len=${text.toByteArray(Charsets.UTF_8).size} " +
                    "www=${redact(www)} nonce=$hasNonce body=${redact(text)}")
            }
        } catch (error: Exception) {
            TransferResult.Done("network ${error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Exact AuthException messages carry only stage/status classifications, never secrets. */
    private fun describe(error: Throwable): String =
        if (error.javaClass == AuthException::class.java) "${error.javaClass.simpleName} ${(error as AuthException).message}"
        else error.javaClass.simpleName

    internal fun redact(body: String): String {
        val stripped = body
            .replace(Regex("https?://\\S+"), "*")
            .replace(Regex("[A-Za-z0-9+/=_-]{24,}"), "*")
            .replace(Regex("\\*(?:\\.[A-Za-z0-9+/=_-]+)+"), "*")
            .filter { it.code in 32..126 }
        return stripped.take(180)
    }

    private fun probe(
        bearer: String,
        extra: Map<String, String> = emptyMap(),
        appVersion: String? = null,
        url: String = "https://open.spotify.com/",
        host: String = "https://gae2-spclient.spotify.com",
    ): String {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        val profile = if (appVersion != null) {
            DesktopClientProfile.headers.mapValues { (_, value) ->
                value.replace(DesktopClientProfile.VERSION, appVersion)
            }
        } else DesktopClientProfile.headers
        val headers = profile + mapOf(
            "Authorization" to "Bearer $bearer",
            "Content-Type" to "application/json",
            "Cache-Control" to "no-cache, no-store, max-age=0",
        ) + extra
        val body = JSONObject().put("url", url).toString()
        val builder = Request.Builder()
            .url("$host/sessiontransfer/v1/token")
            .header("Accept", "*/*")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.post(body.toRequestBody("application/json".toMediaType()))
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val bytes = response.body.bytes()
                val text = bytes.toString(Charsets.UTF_8)
                val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty().ifBlank { "-" }
                val names = response.headers.names().joinToString(",") { it.lowercase() }
                val authError = Regex("error=\"([a-z0-9_]{1,40})\"")
                    .find(response.header("WWW-Authenticate").orEmpty())?.groupValues?.get(1) ?: "-"
                if (response.code in 200..299) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val hasToken = (json?.opt("token") as? String)?.isNotBlank() == true
                    val expires = (json?.opt("expires_in") as? Number)?.toLong()
                    "status=${response.code} type=$type hasToken=$hasToken expiresIn=${expires ?: "-"} headers=$names"
                } else {
                    "status=${response.code} type=$type len=${bytes.size} www=$authError headers=$names body=${redact(text)}"
                }
            }
        } catch (error: Exception) {
            "network ${error.javaClass.simpleName}"
        }
    }

    /** Same request through java.net.HttpURLConnection (the catalog path). Isolates TLS-stack gating. */
    private fun probeJdk(bearer: String): String {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = java.net.URI("https://gae2-spclient.spotify.com/sessiontransfer/v1/token")
                .toURL().openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "*/*")
            DesktopClientProfile.headers.forEach(connection::setRequestProperty)
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            val payload = JSONObject().put("url", "https://open.spotify.com/").toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val type = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim().orEmpty().ifBlank { "-" }
            if (status in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val hasToken = (json?.opt("token") as? String)?.isNotBlank() == true
                val expires = (json?.opt("expires_in") as? Number)?.toLong()
                "status=$status type=$type hasToken=$hasToken expiresIn=${expires ?: "-"}"
            } else {
                "status=$status type=$type len=${text.toByteArray(Charsets.UTF_8).size} body=${redact(text)}"
            }
        } catch (error: Exception) {
            "network ${error.javaClass.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }
}
