package io.github.playmusic.data.playback

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import io.github.playmusic.data.auth.SpotifyAuthException
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** Posts platform-generated license messages without following an HTTP redirect. */
internal class LicenseHttpClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    fun post(uri: URI, request: ByteArray, headers: (forceRefresh: Boolean) -> Map<String, String>): ByteArray {
        return try {
            send(uri, request, headers, false)
        } catch (error: LicenseHttpException) {
            if (error.responseCode == 401) send(uri, request, headers, true) else throw error
        }
    }

    private fun send(uri: URI, request: ByteArray, headers: (Boolean) -> Map<String, String>, refresh: Boolean): ByteArray {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.host == null) {
            throw LicenseHttpException(Failure.INVALID_REQUEST)
        }
        if (request.size > MAX_MESSAGE_BYTES) throw LicenseHttpException(Failure.SIZE_LIMIT)
        var connection: HttpURLConnection? = null
        var bytesLoaded = 0L
        try {
            // Resolve the permitted credentials before opening any network connection.
            val properties = headers(refresh)
            val current = openConnection(uri).also { connection = it }
            current.instanceFollowRedirects = false
            current.requestMethod = "POST"
            current.connectTimeout = 15_000
            current.readTimeout = 20_000
            current.useCaches = false
            current.setRequestProperty("Content-Type", "application/octet-stream")
            current.setRequestProperty("Accept-Encoding", "gzip")
            properties.forEach(current::setRequestProperty)
            current.doOutput = true
            current.setFixedLengthStreamingMode(request.size)
            current.outputStream.use { it.write(request) }
            val status = current.responseCode
            if (status !in 200..299) throw LicenseHttpException(Failure.HTTP, status)
            return current.inputStream.use { source ->
                val input = if (current.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(source) else source
                input.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_MESSAGE_BYTES) throw LicenseHttpException(Failure.SIZE_LIMIT, bytesLoaded = bytesLoaded)
                        output.write(buffer, 0, count)
                        bytesLoaded += count
                    }
                    output.toByteArray()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: LicenseHttpException) {
            throw error
        } catch (error: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
            // Fresh sign-ins fail here while their derived credentials are still propagating.
            // Keep the stage/failure/status so the DRM error can be told apart from a network fault.
            throw error
        } catch (error: SpotifyAuthException) {
            throw error
        } catch (error: BrowserAuthorizationRequiredException) {
            throw error
        } catch (_: Exception) {
            // Network/header errors can quote credentials or message bytes. Never retain their cause.
            throw LicenseHttpException(Failure.NETWORK, bytesLoaded = bytesLoaded)
        } finally {
            try { connection?.disconnect() }
            catch (_: Exception) { throw LicenseHttpException(Failure.NETWORK, bytesLoaded = bytesLoaded) }
        }
    }

    enum class Failure { HTTP, NETWORK, INVALID_REQUEST, SIZE_LIMIT }
    class LicenseHttpException(
        val failure: Failure,
        val responseCode: Int? = null,
        val bytesLoaded: Long = 0,
    ) : IOException("License request failed: $failure" + (responseCode?.let { " (HTTP $it)" } ?: ""))

    private companion object { const val MAX_MESSAGE_BYTES = 1_048_576 }
}
