package io.github.playmusic.data.playback

data class PlaybackTransitionSettings(
    val fadeInEnabled: Boolean = true,
    val fadeInSeconds: Int = 3,
    val fadeOutEnabled: Boolean = true,
    val fadeOutSeconds: Int = 3,
    val crossfadeEnabled: Boolean = true,
    val crossfadeSeconds: Int = 5,
    val peakNormalizationEnabled: Boolean = false,
    val automixEnabled: Boolean = true,
    val seekCrossfadeEnabled: Boolean = false,
    val seekCrossfadeSeconds: Int = 3,
) {
    init { require(fadeInSeconds in 1..12 && fadeOutSeconds in 1..12 && crossfadeSeconds in 1..12 && seekCrossfadeSeconds in 1..12) }
    val fadeInMs: Long get() = if (fadeInEnabled) fadeInSeconds * 1_000L else 0
    val fadeOutMs: Long get() = if (fadeOutEnabled) fadeOutSeconds * 1_000L else 0
    val crossfadeMs: Long get() = if (crossfadeEnabled) crossfadeSeconds * 1_000L else 0
    val seekCrossfadeMs: Long get() = if (seekCrossfadeEnabled) seekCrossfadeSeconds * 1_000L else 0
}

data class PlaybackTransitionState(
    val settings: PlaybackTransitionSettings = PlaybackTransitionSettings(),
    val isReady: Boolean = false,
    val storageFailed: Boolean = false,
)

/** Linear equal-gain overlap prevents two correlated full-scale signals exceeding unity. */
internal object TransitionEnvelope {
    fun gain(from: Float, to: Float, elapsedMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) to else from + (to - from) * (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    fun overlapMs(requestedMs: Long, outgoingMs: Long, incomingMs: Long): Long =
        minOf(requestedMs, outgoingMs.coerceAtLeast(0) / 2, incomingMs.coerceAtLeast(0) / 2)
}
