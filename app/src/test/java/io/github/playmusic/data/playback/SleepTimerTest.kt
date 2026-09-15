package io.github.playmusic.data.playback

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    @Test fun durationUsesHoursAndMinutesAndRejectsInvalidInputs() {
        assertEquals(5_400_123L, SleepTimerRequest(SleepTimerMode.DURATION, 1, 30).deadline(123))
        assertFalse(SleepTimerRequest(SleepTimerMode.DURATION, 0, 0).isValid)
        assertFalse(SleepTimerRequest(SleepTimerMode.DURATION, 100, 0).isValid)
        assertFalse(SleepTimerRequest(SleepTimerMode.CLOCK, 24, 0).isValid)
        assertFalse(SleepTimerRequest(SleepTimerMode.CLOCK, 0, 60).isValid)
        assertFalse(SleepTimerRequest(SleepTimerMode.CLOCK, -1, 10).isValid)
    }

    @Test fun wallClockTimeUsesNextOccurrenceAcrossMidnightAndMonthBoundary() {
        val zone = TimeZone.getTimeZone("Asia/Tokyo")
        val now = time(zone, 2026, Calendar.SEPTEMBER, 30, 23, 50)
        assertEquals(time(zone, 2026, Calendar.OCTOBER, 1, 0, 15),
            SleepTimerRequest(SleepTimerMode.CLOCK, 0, 15).deadline(now, zone))
        assertEquals(time(zone, 2026, Calendar.SEPTEMBER, 30, 23, 55),
            SleepTimerRequest(SleepTimerMode.CLOCK, 23, 55).deadline(now, zone))
        assertEquals(time(zone, 2026, Calendar.OCTOBER, 1, 23, 50),
            SleepTimerRequest(SleepTimerMode.CLOCK, 23, 50).deadline(now, zone))
    }

    @Test fun clockTimerPreservesLocalHourAcrossDaylightSavingBoundary() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = time(zone, 2026, Calendar.MARCH, 7, 22, 0)
        assertEquals(time(zone, 2026, Calendar.MARCH, 8, 8, 0),
            SleepTimerRequest(SleepTimerMode.CLOCK, 8, 0).deadline(now, zone))
    }

    @Test fun fadeGraduallyStopsBeforeSleepAndRestoresAppVolume() = runTest {
        val events = mutableListOf<String>()
        val player = FakePlayback(events)
        val work = launch { fadeForSleep(player) { events += "sleep" } }
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(0.4f, player.volume, 0.001f)
        assertFalse(events.contains("stop"))
        assertFalse(events.contains("sleep"))
        work.join()
        assertEquals(listOf("stop", "sleep"), events)
        assertEquals(0.8f, player.volume, 0f)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun alreadyEndedOrAbsentPlayerSleepsWithoutChangingPlayback() = runTest {
        val events = mutableListOf<String>()
        val player = FakePlayback(events).apply { active = false }
        fadeForSleep(player) { events += "sleep" }
        fadeForSleep(null) { events += "sleep" }
        assertEquals(listOf("sleep", "sleep"), events)
        assertEquals(0.8f, player.volume, 0f)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun fadeSupportsTheFullOneToTwelveSecondRange() = runTest {
        for (seconds in listOf(1L, 12L)) {
            val events = mutableListOf<String>()
            val before = testScheduler.currentTime
            fadeForSleep(FakePlayback(events), seconds * 1_000) { events += "sleep" }
            assertEquals(seconds * 1_000, testScheduler.currentTime - before)
            assertEquals(listOf("stop", "sleep"), events)
        }
    }

    @Test fun cancellingDuringFadeRestoresVolumeAndDoesNotStopOrSleep() = runTest {
        val events = mutableListOf<String>()
        val player = FakePlayback(events)
        val work = launch { fadeForSleep(player) { events += "sleep" } }
        advanceTimeBy(1_250)
        assertTrue(player.volume < 0.8f)
        work.cancelAndJoin()
        assertEquals(0.8f, player.volume, 0f)
        assertTrue(events.isEmpty())
    }

    @Test fun queueEndingDuringFadeSleepsWithoutRestartingOrStoppingEndedPlayer() = runTest {
        val events = mutableListOf<String>()
        val player = FakePlayback(events)
        val work = launch { fadeForSleep(player) { events += "sleep" } }
        advanceTimeBy(1_250)
        player.active = false
        work.join()
        assertEquals(listOf("sleep"), events)
        assertEquals(0.8f, player.volume, 0f)
    }

    private class FakePlayback(private val events: MutableList<String>) : SleepTimerPlayback {
        override var active = true
        override var volume = 0.8f
        override fun stop() { events += "stop"; active = false }
    }

    private fun time(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance(zone).apply { clear(); set(year, month, day, hour, minute) }.timeInMillis
}
