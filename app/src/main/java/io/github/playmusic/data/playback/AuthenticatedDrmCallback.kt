package io.github.playmusic.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import io.github.playmusic.data.auth.PlaybackAuthorizationProvider
import java.net.URI
import java.util.UUID

/** Android's DRM implementation keeps content keys inside the platform. */
@OptIn(UnstableApi::class)
internal class AuthenticatedDrmCallback(
    private val authorization: PlaybackAuthorizationProvider,
    private val licenseHttp: LicenseHttpClient = LicenseHttpClient(),
    private val wrapFailure: (cause: Exception, bytesLoaded: Long) -> Exception =
        { cause, loaded -> defaultFailure(cause, loaded) },
) : MediaDrmCallback {
    private val http by lazy { DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000) }

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        HttpMediaDrmCallback(StreamingApiClient.LICENSE_URL, http).executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        val uri = URI(StreamingApiClient.LICENSE_URL)
        return try {
            val response = licenseHttp.post(uri, request.data) { refresh ->
                runBlocking { authorization.headers(uri, refresh) }
            }
            // LoadEventInfo is optional in Media3 1.11.1; do not expose request secrets through analytics.
            MediaDrmCallback.Response.Builder(response).build()
        } catch (error: LicenseHttpClient.LicenseHttpException) {
            throw wrapFailure(error, error.bytesLoaded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Authorization failures carry only stage/failure/status, never tokens or message bytes.
            throw wrapFailure(error, 0)
        }
    }

    internal companion object {
        internal fun defaultFailure(cause: Exception, bytesLoaded: Long): MediaDrmCallbackException {
            // The spec carries only the license endpoint, never request headers or message bytes.
            val safeSpec = DataSpec.Builder().setUri(StreamingApiClient.LICENSE_URL)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST).build()
            return MediaDrmCallbackException(safeSpec, safeSpec.uri, emptyMap(), bytesLoaded, cause)
        }
    }
}
