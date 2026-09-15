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

/** True when the cause chain holds a rate-limited license response worth waiting out. */
internal fun isLicenseRateLimited(error: Throwable?): Boolean {
    var cause = error
    while (cause != null) {
        if (cause is LicenseHttpClient.LicenseHttpException &&
            (cause.responseCode == 429 || cause.responseCode == 503)) return true
        cause = cause.cause
    }
    return false
}

/**
 * Spaces license requests process-wide. Bursts of concurrent key requests (track switch
 * plus its overlap, player retries) would otherwise keep tripping the server limit.
 */
internal object LicensePacer {
    private const val MIN_INTERVAL_MS = 4_000L
    @Volatile private var lastStartMs = 0L

    fun waitTurn(sleepMs: (Long) -> Unit) {
        val wait = synchronized(this) {
            val now = System.currentTimeMillis()
            val pending = (MIN_INTERVAL_MS - (now - lastStartMs)).coerceAtLeast(0)
            lastStartMs = now + pending
            pending
        }
        if (wait > 0) sleepMs(wait)
    }

    internal fun resetForTests() {
        lastStartMs = 0L
    }
}

/** Posts platform-generated license messages without following an HTTP redirect. */
internal class LicenseHttpClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val sleepMs: (Long) -> Unit = ::interruptibleSleep,
    private val paceMs: (Long) -> Unit = sleepMs,
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
        var attempt = 0
        while (true) {
            try {
                return attemptSend(uri, request, headers, refresh)
            } catch (error: LicenseHttpException) {
                // Rate limiting is not an authorization fault: replay the same message with the
                // same credentials after backing off instead of forcing a re-acquisition.
                val limited = error.responseCode == 429 || error.responseCode == 503
                if (!limited || attempt >= MAX_RATE_LIMIT_RETRIES) throw error
                val delay = ((error.retryAfterMs ?: defaultRetryAfterMs(attempt))).coerceAtMost(MAX_RETRY_AFTER_MS)
                sleepMs(delay.coerceAtLeast(0))
                attempt++
            }
        }
    }
    private fun attemptSend(uri: URI, request: ByteArray, headers: (Boolean) -> Map<String, String>, refresh: Boolean): ByteArray {
        var connection: HttpURLConnection? = null
        var bytesLoaded = 0L
        try {
            LicensePacer.waitTurn(paceMs)
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
            if (status !in 200..299) {
                val retryAfter = if (status == 429 || status == 503) retryAfterMs(current.getHeaderField("Retry-After")) else null
                throw LicenseHttpException(Failure.HTTP, status, retryAfterMs = retryAfter)
            }
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
        val retryAfterMs: Long? = null,
    ) : IOException("License request failed: $failure" + (responseCode?.let { " (HTTP $it)" } ?: ""))

    private companion object {
        const val MAX_MESSAGE_BYTES = 1_048_576
        /** Rate-limit retries after the initial attempt; further attempts belong to the player. */
        const val MAX_RATE_LIMIT_RETRIES = 3
        const val MAX_RETRY_AFTER_MS = 60_000L
        private val RATE_LIMIT_DELAYS_MS = longArrayOf(10_000L, 30_000L, 60_000L)

        fun defaultRetryAfterMs(attempt: Int): Long =
            RATE_LIMIT_DELAYS_MS[attempt.coerceIn(0, RATE_LIMIT_DELAYS_MS.lastIndex)]

        /** Parses Retry-After seconds or an HTTP-date into milliseconds. Unknown values stay null. */
        private fun retryAfterMs(value: String?): Long? {
            val trimmed = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { return (it.coerceAtLeast(0) * 1_000) }
            return try {
                val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                format.parse(trimmed)?.time?.minus(System.currentTimeMillis())?.coerceAtLeast(0)
            } catch (_: Exception) { null }
        }

        fun interruptibleSleep(delayMs: Long) {
            var remaining = delayMs
            while (remaining > 0) {
                val slice = minOf(remaining, 250)
                try { Thread.sleep(slice) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("License retry interrupted")
                }
                remaining -= slice
            }
        }
    }
}
