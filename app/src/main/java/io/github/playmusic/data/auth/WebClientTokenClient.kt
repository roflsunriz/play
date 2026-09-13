package io.github.playmusic.data.auth

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Requests client credentials matching the identity returned by the current authorization. */
class WebClientTokenClient(
    private val device: WebClientDevice,
    private val clientId: String,
    private val clientVersion: String,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    init {
        require(clientId.matches(Regex("[A-Fa-f0-9]{32}")))
        require(clientVersion.isNotBlank() && clientVersion.length <= 256 && clientVersion.none { it == '\r' || it == '\n' })
    }
    suspend fun acquire(): GrantedClientToken = withContext(Dispatchers.IO) {
        val body = requestBody(device, clientId, clientVersion).toString().toByteArray(Charsets.UTF_8)
        val connection = try { openConnection(URI(ENDPOINT)) } catch (_: IOException) {
            throw SpotifyAuthException("Client token request failed (network)")
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw SpotifyAuthException("Client token request failed ($status)")
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (output.size() + length > 1_048_576) throw SpotifyAuthException("Client token response is too large")
                    output.write(buffer, 0, length)
                }
                output.toString("UTF-8")
            }
            parseGrant(JSONObject(response))
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            throw SpotifyAuthException("Client token request failed (network)")
        } catch (_: JSONException) {
            // JSON parser exceptions can quote response values; do not propagate their messages or causes.
            throw SpotifyAuthException("Client token response is invalid")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken"

        internal fun requestBody(device: WebClientDevice, clientId: String,
            clientVersion: String): JSONObject = JSONObject().put("client_data", JSONObject()
            .put("client_version", clientVersion)
            .put("client_id", clientId)
            .put("js_sdk_data", JSONObject()
                .put("device_brand", device.brand)
                .put("device_model", device.model)
                .put("os", "android")
                .put("os_version", device.osVersion)
                .put("device_id", device.deviceId)
                .put("device_type", if (device.isTablet) "tablet" else "smartphone")))

        internal fun parseGrant(root: JSONObject): GrantedClientToken {
            val responseType = root.opt("response_type")
            if (responseType != null && responseType != "RESPONSE_GRANTED_TOKEN_RESPONSE" && responseType != 1) {
                throw SpotifyAuthException("Client token request did not return a grant")
            }
            if (!root.isNull("challenges")) throw SpotifyAuthException("Client token request requires additional verification")
            val grant = root.optJSONObject("granted_token")
                ?: throw SpotifyAuthException("Client token grant is missing")
            val token = (grant.opt("token") as? String)?.takeIf {
                it.length in 1..32_768 && it.all { character -> character.code in 33..126 }
            } ?: throw SpotifyAuthException("Client token is invalid")
            fun seconds(name: String, optional: Boolean = false): Int {
                if (optional && !grant.has(name)) return 0
                val value = grant.opt(name)
                val parsed = if (value is Number) value.toString().toLongOrNull() else null
                if (parsed == null || parsed !in 0..Int.MAX_VALUE.toLong()) {
                    throw SpotifyAuthException("Client token lifetime is invalid")
                }
                return parsed.toInt()
            }
            val expires = seconds("expires_after_seconds")
            val refresh = seconds("refresh_after_seconds", optional = true)
            if (expires <= 0) throw SpotifyAuthException("Client token has expired")
            val sourceDomains = grant.optJSONArray("domains")
                ?: throw SpotifyAuthException("Client token domains are missing")
            val domains = (0 until sourceDomains.length()).map { index ->
                val value = sourceDomains.optJSONObject(index)?.opt("domain") as? String
                    ?: throw SpotifyAuthException("Client token domain is invalid")
                try { GrantedClientToken.normalizeDomain(value) } catch (_: ProtoParseException) {
                    throw SpotifyAuthException("Client token domain is invalid")
                }
            }.distinct()
            if (domains.isEmpty()) throw SpotifyAuthException("Client token has no permitted hosts")
            return GrantedClientToken(token, expires, refresh, domains)
        }
    }
}

data class WebClientDevice(
    val deviceId: String,
    val brand: String,
    val model: String,
    val osVersion: String,
    val isTablet: Boolean,
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 1024)
        require(brand.isNotBlank() && model.isNotBlank() && osVersion.isNotBlank())
    }

    override fun toString(): String = "WebClientDevice"

    companion object {
        fun fromAndroid(context: Context, deviceId: String): WebClientDevice = WebClientDevice(
            deviceId = deviceId,
            brand = Build.BRAND?.takeIf { it.isNotBlank() } ?: "unknown",
            model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown",
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            isTablet = context.resources.configuration.smallestScreenWidthDp >= 600,
        )
    }
}
