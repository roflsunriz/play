package io.github.playmusic

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.playback.LocalPlayback
import io.github.playmusic.data.playback.SleepTimerMode
import io.github.playmusic.data.playback.SleepTimerRequest
import io.github.playmusic.testing.PlaybackTestService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opt in on an isolated emulator: this test turns its screen off. Never runs on a phone. */
class SleepTimerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val timer get() = (context.applicationContext as PlayApplication).container.sleepTimer

    @Test fun scheduleReplacementAndCancellationReachTheStoredState(): Unit = runBlocking {
        requireTestDevice()
        try {
            assertTrue(timer.schedule(SleepTimerRequest(SleepTimerMode.DURATION, 0, 1)))
            val first = timer.state.value.deadlineMillis
            assertTrue(timer.schedule(SleepTimerRequest(SleepTimerMode.DURATION, 1, 5)))
            assertNotEquals(first, timer.state.value.deadlineMillis)
            assertTrue(timer.state.value.deadlineMillis!! > first!! + 3_000_000L)
            assertFalse(timer.schedule(SleepTimerRequest(SleepTimerMode.DURATION, 0, 0)))
            assertTrue(timer.cancel())
            assertNull(timer.state.value.deadlineMillis)
        } finally { timer.cancel() }
    }

    @Test fun realAlarmFadesServiceVolumeStopsThenTurnsScreenOff(): Unit = runBlocking {
        requireTestDevice()
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        val settings = (context.applicationContext as PlayApplication).container.playbackTransitions
        val originalSettings = settings.state.first { it.isReady }.settings
        settings.setSettings(originalSettings.copy(fadeOutSeconds = 12))
        wakeScreen()
        ActivityScenario.launch(PlaylistUiTestActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                playback.play(listOf(track))
                playback.setRepeat(RepeatMode.TRACK)
                await { actual { isPlaying } }
                withContext(Dispatchers.Main) { checkNotNull(PlaybackTestService.activeSession).player.volume = 0.8f }
                assertTrue(timer.schedule(SleepTimerRequest(SleepTimerMode.DURATION, 0, 1)))
                await(70_000) { timer.state.value.fading }
                await(4_000) { actual { volume in 0.15f..0.65f } }
                assertTrue(actual { isPlaying })
                await(16_000) { timer.state.value.deadlineMillis == null && !timer.state.value.fading }
                assertFalse(actual { playWhenReady })
                assertEquals(Player.STATE_IDLE, actual { playbackState })
                assertEquals(0.8f, actual { volume }, 0.001f)
                await { !context.getSystemService(PowerManager::class.java).isInteractive }
                assertNull(timer.state.value.error)
            } finally {
                timer.cancel()
                playback.clear()
                playback.release()
                context.stopService(Intent(context, PlaybackTestService::class.java))
                settings.setSettings(originalSettings)
                settings.persistNow()
                wakeScreen()
            }
        }
    }

    @Test fun queueEndedBeforeAlarmOnlyTurnsScreenOff(): Unit = runBlocking {
        requireTestDevice()
        val playback = LocalPlayback(context, PlaybackTestService::class.java)
        wakeScreen()
        ActivityScenario.launch(PlaylistUiTestActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                playback.play(listOf(track))
                await { actual { isPlaying } }
                playback.seek(11_800)
                await { actual { playbackState == Player.STATE_ENDED } }
                val endedPosition = actual { currentPosition }
                assertTrue(timer.schedule(SleepTimerRequest(SleepTimerMode.DURATION, 0, 1)))
                await(70_000) { timer.state.value.deadlineMillis == null }
                assertEquals(Player.STATE_ENDED, actual { playbackState })
                assertEquals(endedPosition, actual { currentPosition })
                await { !context.getSystemService(PowerManager::class.java).isInteractive }
                assertNull(timer.state.value.error)
            } finally {
                timer.cancel()
                playback.clear()
                playback.release()
                context.stopService(Intent(context, PlaybackTestService::class.java))
                wakeScreen()
            }
        }
    }

    private suspend fun requireTestDevice() {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString("sleepTimer") == "true")
        assumeTrue("Sleep timer integration is emulator-only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        timer.refresh()
        assertTrue("Enable the screen-lock device administrator on the test AVD", timer.state.value.canLock)
        assertTrue("Enable exact alarms on the test AVD", timer.state.value.canSchedule)
        assertTrue(timer.cancel())
    }

    private suspend fun <T> actual(block: Player.() -> T): T = withContext(Dispatchers.Main) {
        block(checkNotNull(PlaybackTestService.activeSession).player)
    }

    private suspend fun await(timeout: Long = 15_000, predicate: suspend () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(40) }
    }

    private fun wakeScreen() {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private val track = MusicContent("0000000000000000000001", "spotify:track:0000000000000000000001",
        "Synthetic", "", null, ContentKind.TRACK, durationMs = 12_000)
}
