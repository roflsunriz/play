package io.github.playmusic

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.ui.ContentList
import io.github.playmusic.ui.theme.PlayTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class ViewportPrefetchTest {
    @get:Rule val composeRule = createAndroidComposeRule<PlaylistUiTestActivity>()

    @Test fun evenASlowDragWithUnchangedVisibleRowsDefersPrefetchUntilIdle() {
        val state = LazyListState()
        val reports = ConcurrentLinkedQueue<List<SpotifyContent>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CompletableDeferred<Unit>()
        try {
            composeRule.runOnIdle { scope.launch { state.scroll { release.await() } } }
            composeRule.setContent { PlayTheme { Surface(Modifier.fillMaxSize()) {
                ContentList((0..30).map { SpotifyContent("$it", "spotify:playlist:$it", "Item $it", "", null, ContentKind.PLAYLIST) },
                    {}, {}, { reports += it }, listState = state)
            } } }
            composeRule.waitUntil { reports.isNotEmpty() && state.isScrollInProgress }
            // Real delay exceeds either debounce: old code starts work while a slow drag holds the same rows.
            SystemClock.sleep(600)
            assertTrue(reports.all { it.isEmpty() })
            release.complete(Unit)
            composeRule.waitUntil(3_000) { reports.any { it.isNotEmpty() } }
            assertTrue(reports.filter { it.isNotEmpty() }.all { it.size <= 4 })
        } finally { scope.cancel() }
    }
}
