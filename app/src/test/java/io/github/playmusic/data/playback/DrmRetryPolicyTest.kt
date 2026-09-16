package io.github.playmusic.data.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class DrmRetryPolicyTest {
    @Test fun licenseFailuresAreNeverReplayedByThePlayer() {
        // Java reflection supplies the platform-typed arguments; the policy never reads them.
        val info = LoadErrorHandlingPolicy.LoadErrorInfo::class.java
            .getConstructor(LoadEventInfo::class.java, MediaLoadData::class.java,
                IOException::class.java, java.lang.Integer.TYPE)
            .newInstance(null, null, IOException("synthetic"), 1)
        assertEquals(C.TIME_UNSET, DrmRetryPolicy.getRetryDelayMsFor(info))
        assertEquals(0, DrmRetryPolicy.getMinimumLoadableRetryCount(0))
        assertNull(DrmRetryPolicy.getFallbackSelectionFor(
            LoadErrorHandlingPolicy.FallbackOptions(1, 0, 1, 0), info))
    }
}
