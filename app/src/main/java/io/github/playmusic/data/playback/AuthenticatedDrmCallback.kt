package io.github.playmusic.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import kotlinx.coroutines.runBlocking
import io.github.playmusic.data.auth.PlaybackAuthorizationProvider
import java.net.URI
import java.util.UUID

/** Android's DRM implementation keeps content keys inside the platform. */
@OptIn(UnstableApi::class)
internal class AuthenticatedDrmCallback(
    private val authorization: PlaybackAuthorizationProvider,
    private val licenseHttp: LicenseHttpClient = LicenseHttpClient(),
) : MediaDrmCallback {
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)

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
            val safeSpec = DataSpec.Builder().setUri(StreamingApiClient.LICENSE_URL)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST).build()
            throw MediaDrmCallbackException(safeSpec, safeSpec.uri, emptyMap(), error.bytesLoaded, error)
        }
    }
}
