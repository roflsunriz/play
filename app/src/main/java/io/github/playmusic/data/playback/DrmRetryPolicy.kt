package io.github.playmusic.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Retries of license requests belong to [LicenseHttpClient]: it backs off on rate limiting
 * instead of hammering the server. The player itself must not replay failed key requests,
 * or the two retry loops multiply into a self-sustaining storm.
 */
@OptIn(UnstableApi::class)
internal object DrmRetryPolicy : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        C.TIME_UNSET

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
}
