package io.github.playmusic.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Android's DRM implementation keeps content keys inside the platform. */
@OptIn(UnstableApi::class)
internal class AuthenticatedDrmCallback(private val api: StreamingApiClient) : MediaDrmCallback {
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        HttpMediaDrmCallback(StreamingApiClient.LICENSE_URL, http).executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        fun send(refresh: Boolean): MediaDrmCallback.Response {
            val callback = HttpMediaDrmCallback(StreamingApiClient.LICENSE_URL, true, http)
            callback.setKeyRequestProperty("Authorization", "Bearer ${runBlocking { api.accessToken(refresh) }}")
            StreamingApiClient.requestHeaders.forEach(callback::setKeyRequestProperty)
            return callback.executeKeyRequest(uuid, request)
        }
        return try { send(false) } catch (exception: MediaDrmCallbackException) {
            if ((exception.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 401) send(true)
            else throw exception
        }
    }
}
