package io.github.playmusic.data.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import io.github.playmusic.data.auth.GrantedClientToken
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import io.github.playmusic.data.auth.PlaybackAuthorizationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class AuthenticatedDrmCallbackTest {
    @Test
    fun successfulLicensesPassThrough() {
        val callback = AuthenticatedDrmCallback(provider(transfer = null), licenseHttp(reply = RESPONSE), ::wrap)
        val response = callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(REQUEST, LICENSE.toString()))
        assertArrayEquals(RESPONSE, response.data)
    }

    @Test
    fun authorizationFailuresKeepTheirCauseWithNoBytesLoaded() {
        val transfer = PlaybackAuthorizationClient.PlaybackAuthorizationException(
            PlaybackAuthorizationClient.Stage.TRANSFER, PlaybackAuthorizationClient.Failure.HTTP, 401)
        val recorded = mutableListOf<Pair<Exception, Long>>()
        val callback = AuthenticatedDrmCallback(provider(transfer = transfer), licenseHttp(),
            wrapFailure = { cause, loaded ->
                recorded += cause to loaded
                wrap(cause, loaded)
            })
        val error = runCatching {
            callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(REQUEST, LICENSE.toString()))
        }.exceptionOrNull() as java.io.IOException
        assertEquals(listOf(transfer to 0L), recorded)
        assertSame(transfer, error.cause)
        assertNoSecrets(error)
    }

    @Test
    fun licenseTransportFailuresStayWrappedWithoutSecrets() {
        val recorded = mutableListOf<Pair<Exception, Long>>()
        val callback = AuthenticatedDrmCallback(provider(transfer = null), licenseHttp(status = 403),
            wrapFailure = { cause, loaded ->
                recorded += cause to loaded
                wrap(cause, loaded)
            })
        val error = runCatching {
            callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(REQUEST, LICENSE.toString()))
        }.exceptionOrNull() as java.io.IOException
        val transport = recorded.single().first as LicenseHttpClient.LicenseHttpException
        assertEquals(403, transport.responseCode)
        assertEquals(0L, recorded.single().second)
        assertSame(transport, error.cause)
        assertNoSecrets(error)
    }

    private fun provider(transfer: Exception?): PlaybackAuthorizationProvider =
        PlaybackAuthorizationProvider(
            sourceToken = { PlaybackAuthorizationProvider.Source("synthetic-owner", ACCESS) },
            acquire = { _, _ ->
                transfer?.let { throw it }
                PlaybackAuthorizationProvider.Credentials(
                    "synthetic-web-access",
                    // AuthenticatedDrmCallback always targets the real license endpoint.
                    GrantedClientToken("synthetic-client-token", 60, 45, listOf("gae2-spclient.spotify.com")),
                    System.currentTimeMillis() + 60_000,
                )
            },
        )

    private fun licenseHttp(status: Int = 200, reply: ByteArray = RESPONSE): LicenseHttpClient =
        LicenseHttpClient { uri -> Connection(uri, status, reply) }

    private class Connection(uri: URI, private val status: Int, private val reply: ByteArray) :
        HttpURLConnection(uri.toURL()) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(reply)
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private companion object {
        val LICENSE = URI("https://license.example.test/license")
        const val ACCESS = "synthetic-private-access"
        val REQUEST = "synthetic-private-platform-message".toByteArray()
        val RESPONSE = byteArrayOf(8, 7, 6, 5, 4)
        fun wrap(cause: Exception, loaded: Long): Exception =
            java.io.IOException("synthetic license failure ($loaded)").apply { initCause(cause) }
        fun assertNoSecrets(error: Throwable) {
            for (secret in listOf("synthetic-private-access", "synthetic-private-platform-message",
                "synthetic-web-access", "synthetic-client-token")) {
                assertFalse(error.toString().contains(secret))
                assertFalse((error.cause?.toString() ?: "").contains(secret))
            }
        }
    }
}
