package io.github.playmusic.data.playback

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay

enum class SleepTimerMode { DURATION, CLOCK }

data class SleepTimerRequest(val mode: SleepTimerMode, val hours: Int, val minutes: Int) {
    val isValid: Boolean get() = minutes in 0..59 && when (mode) {
        SleepTimerMode.DURATION -> hours in 0..99 && (hours > 0 || minutes > 0)
        SleepTimerMode.CLOCK -> hours in 0..23
    }

    fun deadline(now: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        require(isValid)
        if (mode == SleepTimerMode.DURATION) return now + (hours * 60L + minutes) * 60_000L
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }
}

/** A narrow player boundary keeps fade/cancel/end-of-queue behavior independently testable. */
internal interface SleepTimerPlayback {
    val active: Boolean
    var volume: Float
    fun stop()
}

/** Never starts playback. Cancellation restores the original app volume without stopping. */
internal suspend fun fadeForSleep(player: SleepTimerPlayback?, durationMs: Long = 5_000, sleep: () -> Unit) {
    require(durationMs in 1_000..12_000)
    if (player != null && player.active) {
        val originalVolume = player.volume
        try {
            val steps = (durationMs / 20).toInt()
            for (step in 1..steps) {
                if (!player.active) break
                delay(20)
                player.volume = originalVolume * (1f - step.toFloat() / steps)
            }
            if (player.active) player.stop()
        } finally {
            player.volume = originalVolume
        }
    }
    sleep()
}
