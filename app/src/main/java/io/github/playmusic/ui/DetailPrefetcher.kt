package io.github.playmusic.ui

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A small, replaceable queue. Scrolling pauses new work without repeatedly aborting the same socket. */
internal class DetailPrefetcher(
    private val scope: CoroutineScope,
    private val isCached: (SpotifyContent) -> Boolean,
    private val load: suspend (SpotifyContent) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val requests = MutableStateFlow<List<SpotifyContent>>(emptyList())
    private var worker: Job? = null

    fun update(items: List<SpotifyContent>) {
        requests.value = items.filter { it.kind == ContentKind.PLAYLIST || it.kind == ContentKind.ALBUM }
            .distinctBy { it.uri }.take(MAX_ITEMS)
        if (requests.value.isEmpty() || worker?.isActive == true) return
        worker = scope.launch(dispatcher) {
            requests.collect { selection ->
                for (item in selection) {
                    if (requests.value != selection) break
                    try {
                        if (!isCached(item)) load(item)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Foreground opening and explicit refresh remain the visible retry paths.
                    }
                }
            }
        }
    }

    fun cancel() {
        requests.value = emptyList()
        worker?.cancel()
        worker = null
    }

    companion object { const val MAX_ITEMS = 4 }
}
