package io.github.playmusic.data.playback

/** Cue points are supplied by the service and must identify this exact ordered pair. */
data class AutomixTransition(
    val fromUri: String,
    val toUri: String,
    val outgoingStartMs: Long,
    val incomingStartMs: Long,
    val durationMs: Long,
    val incomingSpeed: Float = 1f,
) {
    init {
        require(fromUri.isNotBlank() && toUri.isNotBlank())
        require(outgoingStartMs >= 0 && incomingStartMs >= 0 && durationMs in 1..120_000)
        require(incomingSpeed.isFinite() && incomingSpeed in 0.5f..2f)
    }

    /** The metadata-derived duration is a beat-rounded upper bound. Actual file endings
     * may fall between beats: retain the entry cues/tempo and clip to the remaining audio
     * in milliseconds, without inventing another beat position. Less than one second is
     * not a usable fade and falls back to the normal transition.
     */
    internal fun fitWithin(outgoingDurationMs: Long, incomingDurationMs: Long): AutomixTransition? {
        if (outgoingDurationMs <= outgoingStartMs || incomingDurationMs <= incomingStartMs) return null
        val outgoingRemaining = outgoingDurationMs - outgoingStartMs
        val incomingRemaining = ((incomingDurationMs - incomingStartMs).toDouble() / incomingSpeed).toLong()
        val available = minOf(durationMs, outgoingRemaining, incomingRemaining)
        if (available < 1_000) return null
        return if (available == durationMs) this else copy(durationMs = available)
    }

    /** A delayed scheduler must join the same beat phase, not restart the incoming cue.
     * Keep the original transition end while consuming lateness from both tracks.
     */
    internal fun advanceTo(outgoingPositionMs: Long): AutomixTransition? {
        if (outgoingPositionMs <= outgoingStartMs) return this
        val late = outgoingPositionMs - outgoingStartMs
        val remaining = durationMs - late
        if (remaining < 1_000) return null
        return copy(outgoingStartMs = outgoingPositionMs,
            incomingStartMs = incomingStartMs + (late.toDouble() * incomingSpeed).toLong(), durationMs = remaining)
    }
}

fun interface AutomixResolver {
    suspend fun resolve(contextUri: String, fromUri: String, toUri: String): AutomixTransition?
}
