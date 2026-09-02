package io.github.playmusic.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class SpotifyApiClient(private val sessionManager: SessionManager) {
    data class Response(val status: Int, val body: String)

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): Response =
        request("GET", path, query, null)

    suspend fun put(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): Response =
        request("PUT", path, query, body)

    suspend fun post(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): Response =
        request("POST", path, query, body)

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): Response {
        var token = sessionManager.accessToken()
        var response = execute(method, path, query, body, token)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            token = sessionManager.accessToken(forceRefresh = true)
            response = execute(method, path, query, body, token)
        }
        if (response.status !in 200..299) {
            val message = extractError(response.body)
            throw SpotifyApiException(response.status, message)
        }
        return response
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        token: String,
    ): Response = withContext(Dispatchers.IO) {
        val queryString = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val url = "$API_BASE$path" + if (queryString.isBlank()) "" else "?$queryString"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return "Spotify API request failed"
        return runCatching {
            val error = JSONObject(body).opt("error")
            when (error) {
                is JSONObject -> error.optString("message").ifBlank { "Spotify API request failed" }
                is String -> error
                null -> "Spotify API request failed"
                else -> "Spotify API request failed"
            }
        }.getOrDefault("Spotify API request failed")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val API_BASE = "https://api.spotify.com/v1"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

class SpotifyApiException(val status: Int, message: String) : Exception(message)
