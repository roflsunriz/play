package io.github.playmusic.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.playmusic.data.auth.AppConstants
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class SpotifyApiClient(
    private val sessionManager: SessionTokens,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    data class Response(val status: Int, val body: String, val bytes: ByteArray? = null) {
        val bodyBytes: ByteArray
            get() = bytes ?: body.toByteArray(Charsets.UTF_8)
    }

    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        base: String = API_BASE,
        acceptProto: Boolean = false,
    ): Response = request("GET", base, path, query, null, null, "application/json", acceptProto)

    suspend fun put(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        stringBody: String? = null,
        contentType: String = "application/json",
        base: String = API_BASE,
    ): Response = request("PUT", base, path, query, body, null, contentType, acceptProto = false, stringBody = stringBody)

    suspend fun post(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        base: String = API_BASE,
    ): Response = request("POST", base, path, query, body, null, "application/json", acceptProto = false)

    suspend fun postProto(
        path: String,
        body: ByteArray,
        contentType: String = "application/x-protobuf",
        base: String = API_BASE,
        featureId: String? = null,
    ): Response = request("POST", base, path, emptyMap(), null, body, contentType, acceptProto = true, featureId = featureId)

    suspend fun getProto(
        path: String,
        query: Map<String, String> = emptyMap(),
        base: String = API_BASE,
    ): Response = request("GET", base, path, query, null, null, "application/x-protobuf", acceptProto = true)

    private suspend fun request(
        method: String,
        base: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        protoBody: ByteArray?,
        contentType: String,
        acceptProto: Boolean,
        stringBody: String? = null,
        featureId: String? = null,
    ): Response {
        var token = sessionManager.accessToken()
        var clientToken = sessionManager.clientToken()
        var response = execute(method, base, path, query, body, protoBody, contentType, token, clientToken, acceptProto, stringBody, featureId)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            clientToken = sessionManager.clientToken(forceRefresh = true)
            token = sessionManager.accessToken(forceRefresh = true)
            response = execute(method, base, path, query, body, protoBody, contentType, token, clientToken, acceptProto, stringBody, featureId)
        }
        if (response.status !in 200..299) {
            val message = extractError(response.body)
            throw SpotifyApiException(response.status, message)
        }
        return response
    }

    private suspend fun execute(
        method: String,
        base: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        protoBody: ByteArray?,
        contentType: String,
        token: String,
        clientToken: String,
        acceptProto: Boolean,
        stringBody: String?,
        featureId: String?,
    ): Response = withContext(Dispatchers.IO) {
        val queryString = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val url = "$base$path" + if (queryString.isBlank()) "" else "?$queryString"
        val connection = openConnection(URI(url))
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Client-Token", clientToken)
            connection.setRequestProperty("User-Agent", AppConstants.SPOTIFY_USER_AGENT)
            connection.setRequestProperty("Spotify-App-Version", AppConstants.CLIENT_VERSION)
            connection.setRequestProperty("App-Platform", "Android")
            connection.setRequestProperty("Accept-Language", "ja-JP")
            connection.setRequestProperty("Time-Zone", java.util.TimeZone.getDefault().id)
            featureId?.let { connection.setRequestProperty("Client-Feature-Id", it) }
            connection.setRequestProperty(
                "Accept",
                when {
                    contentType == COLLECTION_CONTENT_TYPE -> contentType
                    acceptProto -> "application/protobuf"
                    else -> "application/json"
                },
            )
            if (protoBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(protoBody) }
            } else if (stringBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stringBody) }
            } else if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (acceptProto || contentType == "application/vnd.collection-v2.spotify.proto") {
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                Response(status, String(bytes, Charsets.UTF_8), bytes)
            } else {
                Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return "Service API request failed"
        return runCatching {
            val error = JSONObject(body).opt("error")
            when (error) {
                is JSONObject -> error.optString("message").ifBlank { "Service API request failed" }
                is String -> error
                null -> "Service API request failed"
                else -> "Service API request failed"
            }
        }.getOrDefault("Service API request failed")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val API_BASE = "https://spclient.wg.spotify.com"
        const val GAE2_BASE = "https://gae2-spclient.spotify.com"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        private const val COLLECTION_CONTENT_TYPE = "application/vnd.collection-v2.spotify.proto"
    }
}

class SpotifyApiException(val status: Int, message: String) : Exception("$message ($status)")
