package io.github.playmusic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.playmusic.ui.SleepTimerDialogContent
import io.github.playmusic.ui.theme.PlayTheme
import io.github.playmusic.data.playback.SleepTimerState
import io.github.playmusic.data.playback.SleepTimerRequest
import io.github.playmusic.data.playback.SleepTimerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SleepTimerDialogTest {
    @get:Rule val compose = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test fun missingPermissionExplainsLockAndPreventsScheduling() {
        var closed = false
        var lockRequested = false
        var alarmRequested = false
        compose.setContent { PlayTheme {
            SleepTimerDialogContent(SleepTimerState(), { closed = true }, {}, {},
                { lockRequested = true }, { alarmRequested = true })
        } }
        compose.onNodeWithTag("sleep-timer-start").assertIsNotEnabled()
        captureScreen(compose.onNodeWithTag("sleep-timer-dialog"), "sleep-timer-permissions")
        compose.onNodeWithTag("sleep-timer-lock-permission").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("sleep-timer-alarm-permission").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-timer-clock").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("24")
        compose.onNodeWithTag("sleep-timer-start").assertIsNotEnabled()
        compose.onNodeWithTag("sleep-timer-duration").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("0")
        compose.onNodeWithTag("sleep-timer-minutes").performTextReplacement("0")
        compose.onNodeWithTag("sleep-timer-start").assertIsNotEnabled()
        compose.onNodeWithTag("sleep-timer-close").performClick()
        compose.runOnIdle { assertTrue(closed); assertTrue(lockRequested); assertTrue(alarmRequested) }
    }

    @Test fun permittedTimerValidatesInputAndOffersScheduleAndCancel() {
        var scheduled: SleepTimerRequest? = null
        var cancelled = false
        compose.setContent { PlayTheme {
            SleepTimerDialogContent(SleepTimerState(deadlineMillis = System.currentTimeMillis() + 60_000,
                canLock = true, canSchedule = true), {}, { scheduled = it }, { cancelled = true }, {}, {})
        } }
        compose.onNodeWithTag("sleep-timer-start").assertIsEnabled()
        captureScreen(compose.onNodeWithTag("sleep-timer-dialog"), "sleep-timer-scheduled-duration")
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("0")
        compose.onNodeWithTag("sleep-timer-minutes").performTextReplacement("0")
        compose.onNodeWithTag("sleep-timer-start").assertIsNotEnabled()
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("1")
        compose.onNodeWithTag("sleep-timer-minutes").performTextReplacement("15")
        captureScreen(compose.onNodeWithTag("sleep-timer-dialog"), "sleep-timer-duration-input")
        compose.onNodeWithTag("sleep-timer-start").performClick()
        compose.runOnIdle { assertEquals(SleepTimerRequest(SleepTimerMode.DURATION, 1, 15), scheduled) }
        compose.onNodeWithTag("sleep-timer-clock").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("24")
        compose.onNodeWithTag("sleep-timer-start").assertIsNotEnabled()
        compose.onNodeWithTag("sleep-timer-hours").performTextReplacement("23")
        captureScreen(compose.onNodeWithTag("sleep-timer-dialog"), "sleep-timer-clock-input")
        compose.onNodeWithTag("sleep-timer-start").performClick()
        compose.runOnIdle { assertEquals(SleepTimerRequest(SleepTimerMode.CLOCK, 23, 15), scheduled) }
        compose.onNodeWithTag("sleep-timer-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
