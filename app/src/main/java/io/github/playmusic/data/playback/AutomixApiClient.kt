package io.github.playmusic.data.playback

import io.github.playmusic.data.api.PlaylistApiClient
import io.github.playmusic.data.api.SessionTokens
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Uses service-declared playlist eligibility and cuepoints, including the shuffled ordered pair. */
class AutomixApiClient(
    private val session: SessionTokens,
    private val settings: () -> PlaybackTransitionSettings,
    private val api: SpotifyApiClient = SpotifyApiClient(session),
    private val now: () -> Long = System::currentTimeMillis,
) : AutomixResolver {
    private data class ContextInfo(val members: Set<String>?, val expires: Long)
    private data class TrackInfo(val cues: AutomixMetadata.Cues?, val expires: Long)
    private val mutex = Mutex()
    private var owner: String? = null
    private val contexts = linkedMapOf<String, ContextInfo>()
    private val tracks = linkedMapOf<String, TrackInfo>()
    private val playlists = PlaylistApiClient(api, session)

    override suspend fun resolve(contextUri: String, fromUri: String, toUri: String): AutomixTransition? = withContext(Dispatchers.IO) {
        if (!contextUri.matches(PLAYLIST_URI) || !fromUri.matches(TRACK_URI) || !toUri.matches(TRACK_URI) ||
            fromUri == toUri || !settings().automixEnabled) return@withContext null
        mutex.withLock {
            val account = session.username()
            if (owner != account) { contexts.clear(); tracks.clear(); owner = account }
            val context = contexts[contextUri]?.takeIf { now() < it.expires } ?: run {
                val key = AutomixMetadata.Key(contextUri, AutomixMetadata.MODE)
                val mode = query(listOf(key))[key]
                val members = if (mode != null && AutomixMetadata.defaultMode(mode)) {
                    val content = SpotifyContent(contextUri.substringAfterLast(':'), contextUri, "", "", null, ContentKind.PLAYLIST)
                    playlists.trackUris(content).toSet()
                } else null
                check(session.username() == account) { "Account changed while reading transition context" }
                ContextInfo(members, now() + CACHE_MS).also { contexts[contextUri] = it; trim(contexts, 8) }
            }
            if (context.members == null || fromUri !in context.members || toUri !in context.members) return@withLock null
            val missing = listOf(fromUri, toUri).filter { tracks[it]?.let { item -> now() < item.expires } != true }
                .map { AutomixMetadata.Key(it, AutomixMetadata.CUEPOINTS) }
            if (missing.isNotEmpty()) {
                val values = query(missing)
                check(session.username() == account) { "Account changed while reading transition cuepoints" }
                for (key in missing) tracks[key.uri] = TrackInfo(values[key]?.let(AutomixMetadata::cues), now() + CACHE_MS)
                trim(tracks, 64)
            }
            val from = tracks[fromUri]?.cues ?: return@withLock null
            val to = tracks[toUri]?.cues ?: return@withLock null
            AutomixMetadata.transition(fromUri, toUri, from, to, settings().crossfadeSeconds)
        }
    }
    private suspend fun query(keys: List<AutomixMetadata.Key>): Map<AutomixMetadata.Key, ByteArray?> =
        AutomixMetadata.response(api.postProto("/extended-metadata/v0/extended-metadata", AutomixMetadata.request(keys)).bodyBytes, keys.toSet())

    private fun <T> trim(values: LinkedHashMap<String, T>, limit: Int) { while (values.size > limit) values.remove(values.keys.first()) }
    companion object {
        private val PLAYLIST_URI = Regex("spotify:playlist:[A-Za-z0-9]{22}")
        private val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}")
        private const val CACHE_MS = 300_000L
    }
}
