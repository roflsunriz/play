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
        emit("session present=${session != null} browser=${session?.refreshToken != null} expiredSoon=${session?.expiresSoon() == true}")
        if (session == null) return@withContext lines
        val bearer = runCatching { container.sessionManager.accessToken(false) }.getOrElse {
            emit("bearer-failed ${describe(it)}")
            return@withContext lines
        }
        emit("baseline ${probe(bearer)}")
        emit("newver ${probe(bearer, appVersion = "1.3.0.277")}")
        emit("otturl ${probe(bearer, url = "https://accounts.spotify.com/login/ott/v2#token=$bearer")}")
        emit("wghost ${probe(bearer, host = "https://spclient.wg.spotify.com")}")
        emit("wgott ${probe(bearer, host = "https://spclient.wg.spotify.com", url = "https://accounts.spotify.com/login/ott/v2#token=$bearer")}")
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
        lines
    }

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
}
