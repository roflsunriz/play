package io.github.playmusic

import android.graphics.Rect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.audio.AudioEffectsState
import io.github.playmusic.data.audio.EqualizerPreset
import io.github.playmusic.data.audio.EqualizerSettings
import io.github.playmusic.data.audio.EqualizerSlot
import io.github.playmusic.data.audio.settings
import io.github.playmusic.ui.AudioEffectsScreen
import io.github.playmusic.ui.HomeScreen
import io.github.playmusic.ui.PlayUiState
import io.github.playmusic.ui.theme.PlayTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pure UI callbacks: no account, player, preference storage, or network is used. */
class AudioEffectsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()
    private var state by mutableStateOf(AudioEffectsState(isReady = true))
    private val presets = mutableListOf<EqualizerPreset>()
    private var saved = 0
    private var renamed = 0
    private var deleted = 0
    private var retries = 0
    private var back = 0
    private var failWrites = false

    @Test fun audioSettingsAreAvailableFromTheApplicationMenu() {
        var opened by mutableStateOf(false)
        render {
            if (opened) Effects(onBack = { opened = false }) else HomeScreen(PlayUiState(isLoggedIn = true),
                onSectionSelected = {}, onSearchChanged = {}, onSearch = {}, onRefresh = {}, onLogout = {}, onPlay = {}, onPlayPause = {},
                onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {}, onAudioEffects = { opened = true })
        }
        composeRule.onNodeWithTag("settings-button").performClick()
        composeRule.onNodeWithTag("audio-effects-menu-item").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("audio-effects-enable").assertIsDisplayed()
        composeRule.onNodeWithTag("audio-effects-back").performClick()
        composeRule.onNodeWithTag("settings-button").assertIsDisplayed()
        composeRule.onNodeWithTag("audio-effects-screen").assertDoesNotExist()
    }

    @Test fun loadingAndDisabledStatesPreventAdjustmentsAndBackStillWorks() {
        state = state.copy(isReady = false)
        render { Effects() }
        composeRule.onNodeWithTag("audio-effects-enable").assertIsNotEnabled()
        composeRule.onNodeWithTag("audio-effects-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("audio-effects-back").performClick()
        composeRule.runOnIdle { assertEquals(1, back); state = state.copy(isReady = true) }
        composeRule.onNodeWithTag("audio-effects-loading").assertDoesNotExist()
        adjustment("audio-effects-preamp-slider").assertIsNotEnabled()
        adjustment("audio-effects-band-slider-0").assertIsNotEnabled()
        composeRule.onNodeWithTag("audio-effects-group-previous").assertIsNotEnabled()
        selectGroup(4)
        composeRule.onNodeWithTag("audio-effects-group-next").assertIsNotEnabled()
        adjustment("audio-effects-band-slider-29").assertIsNotEnabled()
        composeRule.onNodeWithTag("audio-effects-group-previous").performClick()
        composeRule.onNodeWithTag("audio-effects-group-next").assertIsEnabled()
        composeRule.onNodeWithTag("audio-effects-enable").performClick()
        adjustment("audio-effects-preamp-slider").assertIsEnabled()
        setGain("audio-effects-preamp-slider", -7.5f)
        composeRule.onNodeWithTag("audio-effects-enable").performClick()
        adjustment("audio-effects-preamp-slider").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(-7.5f, state.settings.preampDb, 0f); assertFalse(state.settings.enabled) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(2, back) }
    }

    @Test fun everyBandAndPreampCanReachTheirLimitsAndResetIndependently() {
        state = state.copy(settings = EqualizerSettings(enabled = true))
        render { Effects() }
        setGain("audio-effects-preamp-slider", -10f)
        assertSignBeforeDigits("audio-effects-preamp-value")
        composeRule.runOnIdle { assertEquals(-10f, state.settings.preampDb, 0f) }
        setGain("audio-effects-preamp-slider", 10f)
        assertSignBeforeDigits("audio-effects-preamp-value")
        composeRule.runOnIdle { assertEquals(10f, state.settings.preampDb, 0f) }
        adjustment("audio-effects-preamp-reset").performClick()
        for (group in 0..4) {
            selectGroup(group)
            for (band in group * 6 until group * 6 + 6) {
                val expected = if (band % 2 == 0) -12f else 12f
                setGain("audio-effects-band-slider-$band", expected)
                composeRule.runOnIdle { assertEquals(expected, state.settings.bandGainsDb[band], 0f) }
            }
        }
        assertFullyVisible("audio-effects-band-slider-29")
        captureScreen(composeRule.onRoot(), "audio-effects-last-band")
        setGain("audio-effects-band-slider-29", 3.3f, expected = 3.5f)
        composeRule.runOnIdle {
            assertEquals(3.5f, state.settings.bandGainsDb[29], 0f)
            assertEquals(-12f, state.settings.bandGainsDb[28], 0f)
            assertEquals(0f, state.settings.preampDb, 0f)
        }
        adjustment("audio-effects-band-reset-29").performClick().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0f, state.settings.bandGainsDb[29], 0f) }
    }

    @Test fun nativeSlidersHandleTapDragAccessibilityAndVerticalScrollingWithoutCrossBandChanges() {
        state = state.copy(settings = EqualizerSettings(enabled = true))
        render { Effects() }
        val slider = adjustment("audio-effects-band-slider-0")
        val rtl = slider.fetchSemanticsNode().layoutInfo.layoutDirection == LayoutDirection.Rtl
        fun physical(value: Float) = if (rtl) 1f - value else value
        slider.performTouchInput { click(Offset(width * physical(.8f), center.y)) }
        composeRule.runOnIdle { assertTrue(state.settings.bandGainsDb[0] > 5f) }
        slider.performTouchInput {
            swipe(Offset(width * physical(.8f), center.y), Offset(width * physical(.2f), center.y), 300)
        }
        composeRule.runOnIdle { assertTrue(state.settings.bandGainsDb[0] < -5f) }
        setGain("audio-effects-band-slider-0", 0f)
        slider.performTouchInput { swipe(center, Offset(center.x, center.y - 150f), 300) }
        composeRule.runOnIdle { assertEquals(List(30) { 0f }, state.settings.bandGainsDb) }
        adjustment("audio-effects-preamp-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2.5f) }
        composeRule.runOnIdle { assertEquals(2.5f, state.settings.preampDb, 0f) }
    }

    @Test fun allPresetsDispatchAndFlatResetsTheWholeCurve() {
        render { Effects() }
        for (preset in EqualizerPreset.entries) {
            adjustment("audio-effects-preset-menu").performClick()
            composeRule.onNodeWithTag("audio-effects-preset-${preset.name.lowercase(Locale.ROOT)}")
                .performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(preset, presets.last()); assertEquals(preset.settings(), state.settings) }
        }
        adjustment("audio-effects-flat").performClick()
        composeRule.runOnIdle {
            assertEquals(EqualizerPreset.FLAT, presets.last())
            assertEquals(0f, state.settings.preampDb, 0f)
            assertEquals(List(30) { 0f }, state.settings.bandGainsDb)
        }
        adjustment("audio-effects-overview")
        captureScreen(composeRule.onRoot(), "audio-effects-adjust")
    }

    @Test fun fiveSlotsCanBeSavedLoadedRenamedOverwrittenAndDeletedWithConfirmation() {
        val initial = EqualizerSettings(enabled = true, preampDb = -3f, bandGainsDb = List(30) { if (it == 29) 6f else 0f })
        state = state.copy(settings = initial)
        render { Effects() }
        composeRule.onNodeWithTag("audio-effects-tab-slots").performClick()
        for (index in 0..4) {
            slot("audio-effects-slot-save-$index").performClick()
            composeRule.onNodeWithTag("audio-effects-name-input").performTextReplacement("Setting ${index + 1}")
            composeRule.onNodeWithTag("audio-effects-name-submit").performScrollTo().performClick()
            slot("audio-effects-slot-name-$index").assertTextEquals("Setting ${index + 1}")
        }
        composeRule.runOnIdle { assertEquals(5, saved); assertTrue(state.slots.all { it?.settings == initial }) }
        slotAction(4, "rename")
        composeRule.onNodeWithTag("audio-effects-name-input").assertTextContains("Setting 5").performTextReplacement("  Evening  ")
        composeRule.onNodeWithTag("audio-effects-name-submit").performScrollTo().performClick()
        slot("audio-effects-slot-name-4").assertTextEquals("Evening")
        val replacement = EqualizerSettings(preampDb = -10f, bandGainsDb = List(30) { -1f })
        composeRule.runOnIdle { state = state.copy(settings = replacement) }
        slotAction(4, "overwrite")
        composeRule.onNodeWithTag("audio-effects-confirm-cancel").performClick()
        composeRule.runOnIdle { assertEquals(initial, state.slots[4]?.settings); assertEquals(5, saved) }
        slotAction(4, "overwrite")
        captureScreen(composeRule.onNodeWithTag("audio-effects-confirm"), "audio-effects-overwrite")
        composeRule.onNodeWithTag("audio-effects-confirm-submit").performClick()
        composeRule.runOnIdle { assertEquals(replacement, state.slots[4]?.settings); assertEquals(6, saved) }
        slot("audio-effects-slot-load-0").performClick()
        composeRule.runOnIdle { assertEquals(initial, state.settings) }
        slotAction(4, "delete")
        composeRule.onNodeWithTag("audio-effects-confirm-cancel").performClick()
        composeRule.runOnIdle { assertNotNull(state.slots[4]); assertEquals(0, deleted) }
        slotAction(4, "delete")
        composeRule.onNodeWithTag("audio-effects-confirm-submit").performClick()
        slot("audio-effects-slot-save-4").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(state.slots[4]); assertTrue(state.slots.take(4).all { it != null })
            assertEquals(initial, state.settings); assertEquals(1, renamed); assertEquals(1, deleted)
        }
        captureScreen(composeRule.onRoot(), "audio-effects-slots")
    }

    @Test fun namesValidateUnicodeLengthAndControlsAndCancelledChangesAreNotApplied() {
        render { Effects() }
        composeRule.onNodeWithTag("audio-effects-tab-slots").performClick()
        slot("audio-effects-slot-save-0").performClick()
        for (invalid in listOf("   ", "🎧".repeat(41), "A\u0001B")) {
            composeRule.onNodeWithTag("audio-effects-name-input").performScrollTo().performTextReplacement(invalid)
            composeRule.onNodeWithTag("audio-effects-name-submit").performScrollTo().performClick()
            composeRule.onNodeWithTag("audio-effects-name-error", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(0, saved); assertNull(state.slots[0]) }
        }
        val name = "🎧".repeat(40)
        composeRule.onNodeWithTag("audio-effects-name-input").performScrollTo().performTextReplacement(name)
        composeRule.onNodeWithTag("audio-effects-name-input").performImeAction()
        composeRule.runOnIdle { assertEquals(name, state.slots[0]?.name); assertEquals(1, saved) }
        slotAction(0, "rename")
        composeRule.onNodeWithTag("audio-effects-name-input").performTextReplacement("Discarded")
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(name, state.slots[0]?.name); assertEquals(0, renamed) }
        slotAction(0, "rename")
        composeRule.onNodeWithTag("audio-effects-name-input").assertTextContains(name)
        composeRule.onNodeWithTag("audio-effects-name-cancel").performScrollTo().performClick()
        composeRule.onNodeWithTag("audio-effects-name-editor").assertDoesNotExist()
    }

    @Test fun keyboardSaveAndStorageRetryKeepTheNameAndCurrentSettings() {
        state = state.copy(settings = EqualizerSettings(enabled = true, preampDb = -4f))
        failWrites = true
        render { Effects() }
        composeRule.onNodeWithTag("audio-effects-tab-slots").performClick()
        slot("audio-effects-slot-save-4").performClick()
        composeRule.onNodeWithTag("audio-effects-name-input").performTextReplacement("Night listening")
        composeRule.onNodeWithTag("audio-effects-name-input").performTouchInput { click() }
        composeRule.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val saveButton = composeRule.onNodeWithTag("audio-effects-name-submit")
        try {
            composeRule.waitUntil(5_000) { saveButton.performScrollTo(); fullyVisible("audio-effects-name-submit") }
        } finally { captureDeviceScreen("audio-effects-keyboard") }
        saveButton.assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("audio-effects-storage-error").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("Night listening", state.slots[4]?.name)
            assertEquals(-4f, state.slots[4]?.settings?.preampDb ?: 0f, 0f)
            assertTrue(state.storageFailed)
        }
        composeRule.onNodeWithTag("audio-effects-retry-save").performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertTrue(state.storageFailed); failWrites = false }
        captureScreen(composeRule.onRoot(), "audio-effects-save-error")
        composeRule.onNodeWithTag("audio-effects-retry-save").performClick()
        composeRule.onNodeWithTag("audio-effects-storage-error").assertDoesNotExist()
        slot("audio-effects-slot-name-4").assertTextEquals("Night listening")
        composeRule.runOnIdle { assertEquals(2, retries); assertFalse(state.storageFailed); assertEquals(1, saved) }
    }

    @Composable private fun Effects(onBack: () -> Unit = { back++ }) {
        AudioEffectsScreen(state, onSettingsChanged = { state = state.copy(settings = it) }, onPresetSelected = {
            presets += it; state = state.copy(settings = it.settings())
        }, onLoadSlot = { index -> state.slots[index]?.let { state = state.copy(settings = it.settings) } },
            onSaveSlot = { index, name ->
                saved++
                state = state.copy(slots = state.slots.mapIndexed { position, slot ->
                    if (position == index) EqualizerSlot(name, state.settings) else slot
                }, storageFailed = failWrites)
            }, onRenameSlot = { index, name ->
                renamed++
                state = state.copy(slots = state.slots.mapIndexed { position, slot ->
                    if (position == index) slot?.copy(name = name) else slot
                }, storageFailed = failWrites)
            }, onDeleteSlot = { index ->
                deleted++
                state = state.copy(slots = state.slots.mapIndexed { position, slot -> if (position == index) null else slot },
                    storageFailed = failWrites)
            }, onRetrySave = { retries++; state = state.copy(storageFailed = failWrites) }, onBack = onBack)
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize(), content = content) } }
    }

    private fun adjustment(tag: String) = composeRule.onNodeWithTag("audio-effects-adjust-list").let {
        it.performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag)
    }

    private fun slot(tag: String) = composeRule.onNodeWithTag("audio-effects-slots-list").let {
        it.performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag)
    }

    private fun selectGroup(group: Int) {
        composeRule.onNodeWithTag("audio-effects-group-menu").performClick()
        composeRule.onNodeWithTag("audio-effects-group-$group").performScrollTo().performClick()
    }

    private fun setGain(tag: String, value: Float, expected: Float = value) {
        adjustment(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        assertEquals(expected,
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,
            0f)
    }

    private fun slotAction(index: Int, action: String) {
        slot("audio-effects-slot-menu-$index").performClick()
        composeRule.onNodeWithTag("audio-effects-slot-$action-$index").performScrollTo().performClick()
    }

    private fun fullyVisible(tag: String): Boolean {
        val element = composeRule.onNodeWithTag(tag)
        val full = element.getUnclippedBoundsInRoot()
        val visible = element.getBoundsInRoot()
        val node = element.fetchSemanticsNode()
        val frame = Rect()
        composeRule.runOnUiThread { composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(frame) }
        return full.bottom > full.top && full.bottom - full.top == visible.bottom - visible.top &&
            full.right - full.left == visible.right - visible.left && node.positionOnScreen.y >= frame.top &&
            node.positionOnScreen.y + node.size.height <= frame.bottom
    }

    private fun assertFullyVisible(tag: String) { assertTrue("$tag must fit the visible display", fullyVisible(tag)) }

    private fun assertSignBeforeDigits(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        adjustment(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val text = layout.layoutInput.text.text
        val sign = text.indexOfFirst { it == '+' || it == '-' || it == '\u2212' }
        val digit = text.indexOfFirst { it.isDigit() }
        assertTrue("$tag must contain a sign and digits", sign >= 0 && digit > sign)
        assertTrue("$tag sign must appear to the left of its digits",
            layout.getBoundingBox(sign).center.x < layout.getBoundingBox(digit).center.x)
    }

    private fun captureDeviceScreen(name: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("screenshotPrefix") ?: return
        require(prefix.matches(Regex("[a-z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = instrumentation.targetContext.filesDir.resolve("ui-verification").apply { mkdirs() }
            directory.resolve("$prefix-$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
