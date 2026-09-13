package io.github.playmusic

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the real frame clock: ComposeTestRule would pause rendering while shell gestures run. */
class LiveScrollPerformanceTest {
    @Test fun measureRepeatedPlaylistDrags() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("scrollPhase") ?: "measurement"
        require(phase.matches(Regex("[a-z0-9-]+")))
        val gestureMs = (arguments.getString("scrollGestureMs")?.toInt() ?: 1800).also { require(it in 50..5000) }
        val pauseMs = (arguments.getString("scrollPauseMs")?.toLong() ?: 0).also { require(it in 0..5000) }
        val repetitions = (arguments.getString("scrollRepetitions")?.toInt() ?: 1).also { require(it in 1..5) }
        val section = arguments.getString("scrollSection") ?: "playlists"
        require(section in listOf("playlists", "albums", "tracks"))
        val openIndex = arguments.getString("scrollOpenIndex")?.toInt()?.also { require(it in 0..9) }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val frames = ConcurrentLinkedQueue<Pair<Long, Long>>()
        val collecting = AtomicBoolean(false)
        val handler = HandlerThread("PlayScrollMetrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (collecting.get() && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
                if (duration > 0) frames.add(duration to deadline.coerceAtLeast(1))
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(handler.looper))
            }
            fun waitForNode(tag: String): AccessibilityNodeInfo {
                val until = SystemClock.uptimeMillis() + 30_000
                while (SystemClock.uptimeMillis() < until) {
                    findNode(instrumentation.uiAutomation.rootInActiveWindow, tag)?.let { return it }
                    SystemClock.sleep(50)
                }
                error("Visible node not found: $tag")
            }
            if (section != "playlists") {
                assertTrue(waitForNode("section-$section").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForNode(if (section == "albums") "album-filter-input" else "track-filter-input")
            }
            if (openIndex != null) {
                val kind = if (section == "playlists") "PLAYLIST" else if (section == "albums") "ALBUM" else "TRACK"
                val app = instrumentation.targetContext.applicationContext as PlayApplication
                val items = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    app.container.repository.library(io.github.playmusic.data.model.ContentKind.valueOf(kind))
                }
                val item = items[openIndex]
                assertTrue(waitForNode("content-${kind.lowercase()}-${item.id}").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForNode("detail-track-0")
            }
            val limit = SystemClock.uptimeMillis() + 30_000
            var bounds: Rect? = null
            while (bounds == null && SystemClock.uptimeMillis() < limit) {
                bounds = scrollBounds(instrumentation.uiAutomation.rootInActiveWindow)
                if (bounds == null) SystemClock.sleep(50)
            }
            val viewport = checkNotNull(bounds) { "A visible scrollable library is required" }
            val x = viewport.centerX()
            val top = (viewport.top + viewport.height() * .12f).toInt()
            val bottom = (viewport.top + viewport.height() * .88f).toInt()
            collecting.set(true)
            repeat(repetitions) {
                for (forward in listOf(true, true, true, false, false, false)) {
                    shell("input swipe $x ${if (forward) bottom else top} $x ${if (forward) top else bottom} $gestureMs")
                    SystemClock.sleep(pauseMs)
                }
            }
            collecting.set(false)
            val samples = frames.toList()
            val durations = samples.map { it.first / 1_000_000.0 }.sorted()
            fun percentile(value: Double) = durations.getOrNull(((durations.size - 1) * value).toInt())
            val report = JSONObject().put("phase", phase).put("frames", samples.size)
                .put("gestureMs", gestureMs).put("pauseMs", pauseMs).put("repetitions", repetitions)
                .put("section", section).put("openIndex", openIndex ?: JSONObject.NULL)
                .put("x", x).put("top", top).put("bottom", bottom)
                .put("p50Ms", percentile(.5) ?: JSONObject.NULL).put("p95Ms", percentile(.95) ?: JSONObject.NULL)
                .put("p99Ms", percentile(.99) ?: JSONObject.NULL).put("maxMs", durations.lastOrNull() ?: JSONObject.NULL)
                .put("over50Ms", durations.count { it > 50 }).put("missedDeadline", samples.count { it.first > it.second })
            val directory = instrumentation.targetContext.filesDir.resolve("scroll-metrics").apply { mkdirs() }
            directory.resolve("$phase.json").writeText(report.toString(2))
            android.util.Log.i("PlayScrollMetrics", report.toString())
            assertTrue("Enough rendered frames must be observed", samples.size >= 30)
            InstrumentationRegistry.getArguments().getString("maxScrollP95Ms")?.toDouble()?.let { maximum ->
                assertTrue("Scroll p95 ${percentile(.95)} ms exceeds $maximum ms", checkNotNull(percentile(.95)) <= maximum)
            }
        } finally {
            collecting.set(false)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            scenario.close()
            handler.quitSafely()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = originalAccessibilityFlags
            }
        }
    }

    private fun scrollBounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.packageName?.toString() == "io.github.playmusic" && node.isVisibleToUser && node.isScrollable &&
            node.className?.toString() != "android.widget.EditText") {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() > 100 && bounds.height() > 200) return bounds
        }
        for (index in 0 until node.childCount) scrollBounds(node.getChild(index))?.let { return it }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo?, tag: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.viewIdResourceName == tag) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), tag)?.let { return it }
        return null
    }

    companion object {
        @BeforeClass @JvmStatic fun requireExplicitLiveRun() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("liveScroll") == "true")
        }
    }
}
