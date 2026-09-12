package io.github.playmusic.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

class AccessPointIdentity {
    suspend fun username(accessToken: String, deviceId: String): String = withContext(Dispatchers.IO) {
        val connection = URI("https://apresolve.spotify.com/?type=accesspoint").toURL().openConnection() as HttpURLConnection
        val endpoints = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            require(connection.responseCode == 200) { "Could not find an account access point" }
            val response = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("accesspoint")
            (0 until response.length()).map { response.getString(it) }.take(3)
        } finally { connection.disconnect() }
        var failure: Exception? = null
        for (endpoint in endpoints) {
            coroutineContext.ensureActive()
            val uri = URI("tcp://$endpoint")
            require(uri.host?.endsWith(".spotify.com") == true && uri.port in 1..65535 &&
                uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && uri.path.isNullOrEmpty()) {
                "Unexpected account access point"
            }
            try { return@withContext AccessPointConnection(uri.host, uri.port).username(accessToken, deviceId) }
            catch (exception: java.io.IOException) { failure = exception }
        }
        throw SpotifyAuthException("Could not verify account identity: ${failure?.javaClass?.simpleName ?: "no access points"}")
    }
}
