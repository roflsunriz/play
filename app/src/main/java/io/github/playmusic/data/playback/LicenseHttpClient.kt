package io.github.playmusic.data.playback

import io.github.playmusic.data.api.BrowserAuthorizationRequiredException
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import io.github.playmusic.data.auth.AuthException
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
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

/** Numeric totals only: never retain endpoints, credentials, messages or content identifiers. */
internal object LicenseRequestMetrics {
    private val attempts = AtomicLong()
    private val successes = AtomicLong()
    private val rateLimited = AtomicLong()
    private val cancelled = AtomicLong()
    data class Snapshot(val attempts: Long, val successes: Long, val rateLimited: Long, val cancelled: Long)
    fun snapshot() = Snapshot(attempts.get(), successes.get(), rateLimited.get(), cancelled.get())
    internal fun attempted() { attempts.incrementAndGet() }
    internal fun succeeded() { successes.incrementAndGet() }
    internal fun limited() { rateLimited.incrementAndGet() }
    internal fun cancelled() { cancelled.incrementAndGet() }
}

/** A shared gate makes a busy response visible before another engine starts its request. */
internal class LicensePacer(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = ReentrantLock()
    private var lastStartMs: Long? = null
    private var cooldownUntilMs: Long? = null
    private var cooldownStatus = 429

    fun <T> send(sleepMs: (Long) -> Unit, isCancelled: () -> Boolean, send: () -> T): T {
        while (true) {
            checkLicenseRequestActive(isCancelled)
            if (lock.tryLock()) break
            sleepMs(50)
        }
        try {
            while (true) {
                checkLicenseRequestActive(isCancelled)
                val now = nowMs()
                val cooldown = cooldownUntilMs?.let { remaining(it, now) } ?: 0
                if (cooldown > MAX_WAIT_MS) {
                    throw LicenseHttpClient.LicenseHttpException(
                        LicenseHttpClient.Failure.HTTP, cooldownStatus, retryAfterMs = cooldown)
                }
                val spacing = lastStartMs?.let { remaining(deadline(it, MIN_INTERVAL_MS), now) } ?: 0
                val wait = maxOf(cooldown, spacing)
                if (wait == 0L) break
                sleepMs(minOf(wait, 250))
            }
            checkLicenseRequestActive(isCancelled)
            lastStartMs = nowMs()
            // Authentication is already resolved. Hold the gate through the response so its
            // Retry-After deadline is published before any waiting request can be sent.
            return send()
        } finally {
            lock.unlock()
        }
    }

    fun defer(delayMs: Long, status: Int) {
        lock.lock()
        try {
            val until = deadline(nowMs(), delayMs)
            if (cooldownUntilMs == null || until > cooldownUntilMs!!) {
                cooldownUntilMs = until
                cooldownStatus = status
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val MAX_WAIT_MS = 60_000L
        private const val MIN_INTERVAL_MS = 4_000L
        val shared = LicensePacer()

        fun resetForTests() {
            shared.lock.lock()
            try {
                shared.lastStartMs = null
                shared.cooldownUntilMs = null
            } finally { shared.lock.unlock() }
        }

        private fun deadline(now: Long, delay: Long): Long =
            if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay

        private fun remaining(until: Long, now: Long): Long = when {
            until <= now -> 0
            now < 0 && until > Long.MAX_VALUE + now -> Long.MAX_VALUE
            else -> until - now
        }
    }
}

private fun checkLicenseRequestActive(isCancelled: () -> Boolean) {
    if (isCancelled() || Thread.currentThread().isInterrupted) {
        throw CancellationException("License request cancelled")
    }
}

/** Posts platform-generated license messages without following an HTTP redirect. */
internal class LicenseHttpClient(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val sleepMs: (Long) -> Unit = ::interruptibleSleep,
    private val pacer: LicensePacer = LicensePacer.shared,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    fun post(uri: URI, request: ByteArray, headers: (forceRefresh: Boolean) -> Map<String, String>): ByteArray =
        post(uri, request, { false }, headers)

    fun post(uri: URI, request: ByteArray, isCancelled: () -> Boolean,
        headers: (forceRefresh: Boolean) -> Map<String, String>): ByteArray {
        try {
            return try {
                send(uri, request, headers, false, isCancelled)
            } catch (error: LicenseHttpException) {
                checkLicenseRequestActive(isCancelled)
                if (error.responseCode == 401) send(uri, request, headers, true, isCancelled)
                // A single transport blip is worth one replay; persistent failure must surface.
                else if (error.failure == Failure.NETWORK) send(uri, request, headers, false, isCancelled)
                else throw error
            }
        } catch (error: CancellationException) {
            LicenseRequestMetrics.cancelled()
            throw error
        }
    }

    private fun send(uri: URI, request: ByteArray, headers: (Boolean) -> Map<String, String>,
        refresh: Boolean, isCancelled: () -> Boolean): ByteArray {
        checkLicenseRequestActive(isCancelled)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.host == null) {
            throw LicenseHttpException(Failure.INVALID_REQUEST)
        }
        if (request.size > MAX_MESSAGE_BYTES) throw LicenseHttpException(Failure.SIZE_LIMIT)
        var attempt = 0
        while (true) {
            checkLicenseRequestActive(isCancelled)
            try {
                return attemptSend(uri, request, headers, refresh, attempt, isCancelled)
            } catch (error: LicenseHttpException) {
                checkLicenseRequestActive(isCancelled)
                val limited = error.responseCode == 429 || error.responseCode == 503
                if (!limited || attempt >= MAX_RATE_LIMIT_RETRIES ||
                    (error.retryAfterMs ?: 0) > LicensePacer.MAX_WAIT_MS) throw error
                // The response has already published the delay to every engine's next request.
                attempt++
            }
        }
    }

    private fun attemptSend(uri: URI, request: ByteArray, headers: (Boolean) -> Map<String, String>,
        refresh: Boolean, attempt: Int, isCancelled: () -> Boolean): ByteArray {
        var connection: HttpURLConnection? = null
        var bytesLoaded = 0L
        try {
            checkLicenseRequestActive(isCancelled)
            val properties = headers(refresh)
            checkLicenseRequestActive(isCancelled)
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
            return pacer.send(sleepMs, isCancelled) {
                checkLicenseRequestActive(isCancelled)
                LicenseRequestMetrics.attempted()
                current.outputStream.use { it.write(request) }
                val status = current.responseCode
                if (status !in 200..299) {
                    val retryAfter = if (status == 429 || status == 503) {
                        LicenseRequestMetrics.limited()
                        val delay = retryAfterMs(current.getHeaderField("Retry-After")) ?: defaultRetryAfterMs(attempt)
                        pacer.defer(delay, status)
                        delay
                    } else null
                    checkLicenseRequestActive(isCancelled)
                    throw LicenseHttpException(Failure.HTTP, status, retryAfterMs = retryAfter)
                }
                checkLicenseRequestActive(isCancelled)
                val response = current.inputStream.use { source ->
                    val input = if (current.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(source) else source
                    input.use {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkLicenseRequestActive(isCancelled)
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_MESSAGE_BYTES) throw LicenseHttpException(Failure.SIZE_LIMIT, bytesLoaded = bytesLoaded)
                            output.write(buffer, 0, count)
                            bytesLoaded += count
                        }
                        output.toByteArray()
                    }
                }
                checkLicenseRequestActive(isCancelled)
                LicenseRequestMetrics.succeeded()
                response
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: LicenseHttpException) {
            throw error
        } catch (error: PlaybackAuthorizationClient.PlaybackAuthorizationException) {
            throw error
        } catch (error: AuthException) {
            throw error
        } catch (error: BrowserAuthorizationRequiredException) {
            throw error
        } catch (_: Exception) {
            checkLicenseRequestActive(isCancelled)
            // Network/header errors can quote credentials or message bytes. Never retain them.
            throw LicenseHttpException(Failure.NETWORK, bytesLoaded = bytesLoaded)
        } finally {
            // Cleanup must never mask cancellation/the original failure or replay a completed POST.
            try { connection?.disconnect() } catch (_: Exception) { }
        }
    }

    enum class Failure { HTTP, NETWORK, INVALID_REQUEST, SIZE_LIMIT }
    class LicenseHttpException(
        val failure: Failure,
        val responseCode: Int? = null,
        val bytesLoaded: Long = 0,
        val retryAfterMs: Long? = null,
    ) : IOException("License request failed: $failure" + (responseCode?.let { " (HTTP $it)" } ?: ""))

    private fun retryAfterMs(value: String?): Long? {
        val trimmed = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        // Delta-seconds can exceed Long; saturation must never wrap into an immediate retry.
        if (trimmed.all { it in '0'..'9' }) {
            val seconds = trimmed.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        }
        return try {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("GMT")
            format.isLenient = false
            val position = java.text.ParsePosition(0)
            val date = format.parse(trimmed, position)
            if (date == null || position.index != trimmed.length) null
            else (date.time - wallClockMs()).coerceAtLeast(0)
        } catch (_: Exception) { null }
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 1_048_576
        const val MAX_RATE_LIMIT_RETRIES = 3
        private val RATE_LIMIT_DELAYS_MS = longArrayOf(10_000L, 30_000L, 60_000L)

        fun defaultRetryAfterMs(attempt: Int): Long =
            RATE_LIMIT_DELAYS_MS[attempt.coerceIn(0, RATE_LIMIT_DELAYS_MS.lastIndex)]

        fun interruptibleSleep(delayMs: Long) {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("License retry interrupted")
            }
        }
    }
}
