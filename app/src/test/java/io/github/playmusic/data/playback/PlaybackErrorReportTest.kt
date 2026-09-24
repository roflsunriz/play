package io.github.playmusic.data.playback

import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorReportTest {
    @Test
    fun rateLimitedLicenseKeepsStatusWithoutSecrets() {
        val transport = LicenseHttpClient.LicenseHttpException(
            LicenseHttpClient.Failure.HTTP, 429, retryAfterMs = 10_000)
        val report = PlaybackErrorReports.build("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED",
            androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            transport, diagnostics(), TRACK, 3, 12, true,
            "CONTEXT", false, 12_345, 200_000, appVersion = "0.3.0", versionCode = 3, sdkInt = 34)

        assertEquals("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED", report.errorCodeName)
        assertEquals(429, report.license?.status)
        assertEquals(10_000L, report.license?.retryAfterMs)
        assertEquals("CONTEXT", report.repeatMode)
        assertEquals(10_000L, report.positionBucketMs)
        assertEquals(16, report.trackHash.length)
        val text = report.toShareText()
        assertFalse(text.contains(TRACK))
        assertFalse(text.contains(ACCESS))
        assertFalse(text.contains(TITLE))
        assertTrue(text.contains(report.trackHash))
    }

    @Test
    fun authorizationStageSurvivesWithoutMessageBytes() {
        val transfer = PlaybackAuthorizationClient.PlaybackAuthorizationException(
            PlaybackAuthorizationClient.Stage.TOKEN, PlaybackAuthorizationClient.Failure.HTTP, 401)
        val report = PlaybackErrorReports.build("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED",
            androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            transfer, diagnostics(), null, -1, 0, null,
            "OFF", false, 0, 0, appVersion = "0.3.0", versionCode = 3, sdkInt = 34)

        assertEquals("TOKEN", report.authorization?.stage)
        assertEquals(401, report.authorization?.status)
        assertFalse(report.toShareText().contains(ACCESS))
    }

    @Test
    fun diagnosticsRingStaysBoundedAndRedacted() {
        val diagnostics = PlaybackDiagnostics(clock = { 0L })
        repeat(PlaybackErrorReport.MAX_EVENTS + 10) { diagnostics.recordCommand("seek-play", 60_000, 1, 5) }
        assertEquals(PlaybackErrorReport.MAX_EVENTS, diagnostics.snapshot().size)
        assertTrue(diagnostics.snapshot().all { it.detail.contains("pos~60000") })
    }

    @Test
    fun issueBodyStaysWithinLimits() {
        val diagnostics = PlaybackDiagnostics(clock = { 0L })
        repeat(PlaybackErrorReport.MAX_EVENTS) { diagnostics.record("cmd", "seek-play pos~60000 idx=1 q=5") }
        val report = PlaybackErrorReports.build("ERROR_CODE_IO_UNSPECIFIED",
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            null, diagnostics, TRACK, 0, 1, false,
            "OFF", false, 0, 180_000, appVersion = "0.3.0", versionCode = 3, sdkInt = 34)
        assertTrue(report.toIssueBody().length <= 7000)
        assertTrue(report.issueUrl().startsWith("https://github.com/roflsunriz/play/issues/new?"))
    }

    private fun diagnostics(): PlaybackDiagnostics {
        var now = 0L
        return PlaybackDiagnostics(clock = { now += 40; now })
    }

    private companion object {
        const val TRACK = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
        const val ACCESS = "synthetic-private-access-token"
        const val TITLE = "Synthetic Private Song Title"
    }
}
