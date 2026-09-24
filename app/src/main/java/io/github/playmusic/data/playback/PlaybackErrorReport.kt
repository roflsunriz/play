package io.github.playmusic.data.playback

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import io.github.playmusic.BuildConfig
import io.github.playmusic.data.auth.AuthException
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Anonymized playback failure report.
 *
 * Only classifications and measurements are kept. Exception messages, request bytes,
 * headers, tokens, URLs, track titles and raw URIs are never stored: [PlaybackException.message]
 * may quote them, so only [PlaybackException.errorCode]/[PlaybackException.errorCodeName] and
 * typed cause fields are extracted.
 */
data class PlaybackErrorEvent(val relativeMs: Long, val kind: String, val detail: String)

data class LicenseSignal(
    val failure: String,
    val status: Int?,
    val retryAfterMs: Long?,
    val bytesLoaded: Long,
)

data class AuthorizationSignal(val stage: String, val failure: String, val status: Int?)

data class PlaybackErrorReport(
    val errorCodeName: String,
    val errorCode: Int,
    val license: LicenseSignal?,
    val authorization: AuthorizationSignal?,
    val authorizationRequired: Boolean,
    val appVersion: String,
    val versionCode: Long,
    val sdkInt: Int,
    val queueSize: Int,
    val trackIndex: Int,
    val trackHash: String,
    val linkedMatch: Boolean?,
    val repeatMode: String,
    val shuffle: Boolean,
    val positionBucketMs: Long,
    val durationMs: Long,
    val events: List<PlaybackErrorEvent>,
) {
    fun toShareText(): String = buildString {
        appendLine("Play error report (anonymized)")
        appendLine("error=$errorCodeName($errorCode)")
        license?.let {
            appendLine("license=${it.failure} status=${it.status ?: "-"} " +
                "retryAfterMs=${it.retryAfterMs ?: "-"} bytesLoaded=${it.bytesLoaded}")
        } ?: appendLine("license=-")
        authorization?.let {
            appendLine("auth=${it.stage}/${it.failure} status=${it.status ?: "-"}")
        } ?: appendLine("auth=-")
        appendLine("loginRequired=$authorizationRequired")
        appendLine("app=$appVersion($versionCode) sdk=$sdkInt")
        appendLine("queue=$queueSize index=$trackIndex track=$trackHash linkedMatch=${linkedMatch ?: "-"}")
        appendLine("repeat=$repeatMode shuffle=$shuffle positionMs~$positionBucketMs durationMs=$durationMs")
        appendLine("events:")
        events.forEach { appendLine("+${it.relativeMs}ms ${it.kind} ${it.detail}") }
    }

    fun toIssueTitle(): String = "Playback failure: $errorCodeName"

    fun toIssueBody(maxChars: Int = 6000): String {
        val header = toShareText()
        if (header.length <= maxChars) return "```\n$header\n```\n\nReproduction steps (optional):\n- \n"
        return "```\n${header.take(maxChars)}\n```\n(truncated)\n\nReproduction steps (optional):\n- \n"
    }

    fun issueUrl(owner: String = ISSUE_OWNER, repo: String = ISSUE_REPO): String {
        val query = "title=${encode(toIssueTitle())}&body=${encode(toIssueBody())}&labels=${encode("bug-report")}"
        return "https://github.com/$owner/$repo/issues/new?$query"
    }

    companion object {
        const val ISSUE_OWNER = "roflsunriz"
        const val ISSUE_REPO = "play"
        const val MAX_EVENTS = 64
        const val POSITION_BUCKET_MS = 5_000L
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

/** Bounded in-memory log of playback actions before a failure. Callers pass safe values only. */
class PlaybackDiagnostics(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val lock = Any()
    private val events = ArrayDeque<PlaybackErrorEvent>()
    private val startedAt = clock()

    fun record(kind: String, detail: String) {
        val safeKind = kind.take(24).filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safeKind.isEmpty()) return
        synchronized(lock) {
            events.addLast(PlaybackErrorEvent(clock() - startedAt, safeKind, detail.take(120)))
            while (events.size > PlaybackErrorReport.MAX_EVENTS) events.removeFirst()
        }
    }

    fun recordCommand(name: String, positionBucketMs: Long = -1, index: Int = -1, queueSize: Int = -1) {
        record("cmd", "$name pos~$positionBucketMs idx=$index q=$queueSize")
    }

    fun snapshot(): List<PlaybackErrorEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }
}

/** Builds anonymized reports from a player failure without retaining secrets. */
@OptIn(UnstableApi::class)
object PlaybackErrorReports {
    fun build(
        error: PlaybackException,
        diagnostics: PlaybackDiagnostics,
        trackUri: String?,
        trackIndex: Int,
        queueSize: Int,
        linkedMatch: Boolean?,
        repeatMode: String,
        shuffle: Boolean,
        positionMs: Long,
        durationMs: Long,
        appVersion: String = BuildConfig.VERSION_NAME,
        versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): PlaybackErrorReport = build(
        errorCodeName = error.errorCodeName,
        errorCode = error.errorCode,
        cause = error.cause,
        diagnostics = diagnostics,
        trackUri = trackUri,
        trackIndex = trackIndex,
        queueSize = queueSize,
        linkedMatch = linkedMatch,
        repeatMode = repeatMode,
        shuffle = shuffle,
        positionMs = positionMs,
        durationMs = durationMs,
        appVersion = appVersion,
        versionCode = versionCode,
        sdkInt = sdkInt,
    )

    /** Cause-chain entry point for JVM tests, where PlaybackException itself is unavailable. */
    fun build(
        errorCodeName: String,
        errorCode: Int,
        cause: Throwable?,
        diagnostics: PlaybackDiagnostics,
        trackUri: String?,
        trackIndex: Int,
        queueSize: Int,
        linkedMatch: Boolean?,
        repeatMode: String,
        shuffle: Boolean,
        positionMs: Long,
        durationMs: Long,
        appVersion: String = BuildConfig.VERSION_NAME,
        versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): PlaybackErrorReport {
        var license: LicenseSignal? = null
        var authorization: AuthorizationSignal? = null
        var authorizationRequired = false
        var cause: Throwable? = cause
        while (cause != null) {
            when (cause) {
                is LicenseHttpClient.LicenseHttpException -> if (license == null) {
                    license = LicenseSignal(
                        failure = cause.failure.name,
                        status = cause.responseCode,
                        retryAfterMs = cause.retryAfterMs,
                        bytesLoaded = cause.bytesLoaded,
                    )
                }
                is PlaybackAuthorizationClient.PlaybackAuthorizationException -> if (authorization == null) {
                    authorization = AuthorizationSignal(
                        stage = cause.stage.name,
                        failure = cause.failure.name,
                        status = cause.status,
                    )
                }
                is AuthException -> if (authorization == null) {
                    authorization = AuthorizationSignal(
                        stage = "SESSION",
                        failure = cause.javaClass.simpleName.take(32),
                        status = null,
                    )
                }
                is MediaDrmCallbackException -> if (license == null && cause.bytesLoaded > 0) {
                    license = LicenseSignal(
                        failure = "DRM",
                        status = null,
                        retryAfterMs = null,
                        bytesLoaded = cause.bytesLoaded,
                    )
                }
                else -> if (cause.javaClass.name.contains("BrowserAuthorizationRequired")) {
                    authorizationRequired = true
                }
            }
            cause = cause.cause
        }
        return PlaybackErrorReport(
            errorCodeName = errorCodeName.take(64),
            errorCode = errorCode,
            license = license,
            authorization = authorization,
            authorizationRequired = authorizationRequired,
            appVersion = appVersion.take(32),
            versionCode = versionCode,
            sdkInt = sdkInt,
            queueSize = queueSize.coerceIn(0, 9999),
            trackIndex = trackIndex.coerceIn(-1, 9999),
            trackHash = trackUri?.let(::hashIdentity) ?: "-",
            linkedMatch = linkedMatch,
            repeatMode = repeatMode.take(16),
            shuffle = shuffle,
            positionBucketMs = positionMs.coerceAtLeast(0) / PlaybackErrorReport.POSITION_BUCKET_MS *
                PlaybackErrorReport.POSITION_BUCKET_MS,
            durationMs = durationMs.coerceIn(0, 86_400_000),
            events = diagnostics.snapshot(),
        )
    }

    /** Short hash of a track identity; the raw URI never leaves the device. */
    internal fun hashIdentity(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
