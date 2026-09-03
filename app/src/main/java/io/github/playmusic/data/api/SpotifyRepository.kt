package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.Playback
import io.github.playmusic.data.model.RepeatMode
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * spclient ネイティブのリポジトリ。
 *
 * 実キャプチャ（mitmproxy flows.flow）から確定した契約:
 * - プレイリスト一覧: GET /playlist/v2/user/{username}/rootlist?decorate=...&from=0&length=120
 * - プレイリスト詳細: GET /playlist/v2/playlist/{id}
 * - 保存アイテム一覧: POST /collection/v2/paging (body: username + kind + limit)
 * - メタデータ:       POST /extended-metadata/v0/extended-metadata
 * - 検索:             GET  /searchview/v3/search (gae2-spclient)
 * - 再生:             PUT  /connect-state/v1/devices/{id}
 */
class SpotifyRepository(
    private val api: SpotifyApiClient,
    private val sessionManager: SessionManager,
    private val deviceIdProvider: () -> String,
) {
    suspend fun library(kind: ContentKind): List<SpotifyContent> = coroutineScope {
        when (kind) {
            ContentKind.PLAYLIST -> libraryPlaylists()
            ContentKind.ALBUM -> libraryAlbums()
            ContentKind.TRACK -> libraryTracks()
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<SpotifyContent> {
        if (query.isBlank()) return emptyList()
        val response = api.get(
            path = "/searchview/v3/search",
            base = SpotifyApiClient.GAE2_BASE,
            query = buildSearchQuery(query),
            acceptProto = true,
        )
        if (response.status !in 200..299) return emptyList()
        return SpClientProto.parseSearchView(response.bodyBytes).mapNotNull { it.toContent() }
    }

    suspend fun playback(): Playback = Playback()

    suspend fun play(content: SpotifyContent) {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildConnectStateBody(content),
            contentType = "application/json",
        )
        if (response.status !in 200..299) throw SpotifyApiException(response.status, "Playback request failed")
    }

    suspend fun resume() {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildPlaybackCommandBody(isPlaying = true),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun pause() {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildPlaybackCommandBody(isPlaying = false),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun next() {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildControlCommandBody("skip_next"),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun previous() {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildControlCommandBody("skip_prev"),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun seek(positionMs: Long) {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildSeekBody(positionMs),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun setRepeat(mode: RepeatMode) {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildRepeatBody(mode.apiValue),
            contentType = "application/json",
        )
        requireOk(response)
    }

    suspend fun setShuffle(enabled: Boolean) {
        val response = api.put(
            path = "/connect-state/v1/devices/${deviceIdProvider()}",
            query = mapOf("is_caused_by_driver" to "false"),
            stringBody = buildShuffleBody(enabled),
            contentType = "application/json",
        )
        requireOk(response)
    }

    private suspend fun libraryPlaylists(): List<SpotifyContent> = coroutineScope {
        val username = sessionManager.username()
        val rootlist = api.get(
            path = "/playlist/v2/user/$username/rootlist",
            query = mapOf(
                "decorate" to "revision,attributes,length,owner,capabilities,status_code,timestamp",
                "from" to "0",
                "length" to "120",
            ),
            acceptProto = true,
        )
        if (rootlist.status !in 200..299) return@coroutineScope emptyList()
        val parsed = SpClientProto.parseRootlist(rootlist.bodyBytes)
        val playlistUris = parsed.items.map { it.uri }.filter { it.startsWith("spotify:playlist:") }.take(PLAYLIST_LIMIT)
        playlistUris.map { uri ->
            async { fetchPlaylistDetail(uri) }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchPlaylistDetail(uri: String): SpotifyContent? {
        val id = uri.removePrefix("spotify:playlist:")
        if (id.isBlank()) return null
        val response = api.get(path = "/playlist/v2/playlist/$id", acceptProto = true)
        if (response.status !in 200..299) return null
        val detail = SpClientProto.parsePlaylist(response.bodyBytes)
        val name = detail.name?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyContent(
            id = id,
            uri = uri,
            title = name,
            subtitle = "",
            imageUrl = detail.images["default"]
                ?: detail.images["xlarge"]
                ?: detail.images.values.firstOrNull(),
            kind = ContentKind.PLAYLIST,
        )
    }

    private suspend fun libraryAlbums(): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection").filter { it.startsWith("spotify:album:") }
        enrichUris(uris, ContentKind.ALBUM)
    }

    private suspend fun libraryTracks(): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection").filter { it.startsWith("spotify:track:") }
        enrichUris(uris, ContentKind.TRACK)
    }

    /**
     * Batches URIs into /extended-metadata/v0/extended-metadata calls and resolves
     * names, artists, images, and durations.
     */
    private suspend fun enrichUris(uris: List<String>, kind: ContentKind): List<SpotifyContent> = coroutineScope {
        val mask = SpClientProto.randomGid()
        uris.chunked(MAX_EM_BATCH).map { batch ->
            async {
                runCatching {
                    val response = api.postProto(
                        path = "/extended-metadata/v0/extended-metadata",
                        body = SpClientProto.buildExtendedMetadataRequest(batch, mask),
                        contentType = "application/protobuf",
                    )
                    if (response.status !in 200..299) return@async emptyList()
                    val metadata = SpClientProto.parseExtendedMetadata(response.bodyBytes)
                    batch.mapNotNull { uri ->
                        metadata.firstOrNull { it.uri == uri }?.toContent(kind)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private suspend fun collectionUris(kind: String): List<String> = coroutineScope {
        val username = sessionManager.username()
        val body = ProtoWire.fieldBytes(1, username.toByteArray(Charsets.UTF_8)) +
            ProtoWire.fieldBytes(2, kind.toByteArray(Charsets.UTF_8)) +
            ProtoWire.fieldVarint(4, COLLECTION_LIMIT)
        val response = api.postProto(
            path = "/collection/v2/paging",
            body = body,
            contentType = "application/vnd.collection-v2.spotify.proto",
        )
        if (response.status !in 200..299) return@coroutineScope emptyList()
        SpClientProto.parseCollectionPage(response.bodyBytes).items.map { it.uri }
    }

    private fun SpClientProto.EntityMetadata.toContent(kind: ContentKind? = null): SpotifyContent? {
        val resolvedKind = kind ?: when {
            uri.startsWith("spotify:album:") -> ContentKind.ALBUM
            uri.startsWith("spotify:artist:") -> ContentKind.ARTIST
            uri.startsWith("spotify:playlist:") -> ContentKind.PLAYLIST
            uri.startsWith("spotify:show:") -> ContentKind.SHOW
            uri.startsWith("spotify:episode:") -> ContentKind.EPISODE
            else -> ContentKind.TRACK
        }
        val id = when (resolvedKind) {
            ContentKind.PLAYLIST -> uri.removePrefix("spotify:playlist:")
            ContentKind.ALBUM -> uri.removePrefix("spotify:album:")
            ContentKind.ARTIST -> uri.removePrefix("spotify:artist:")
            ContentKind.SHOW -> uri.removePrefix("spotify:show:")
            ContentKind.EPISODE -> uri.removePrefix("spotify:episode:")
            else -> uri.removePrefix("spotify:track:")
        }
        if (id.isBlank()) return null
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyContent(
            id = id,
            uri = uri,
            title = title,
            subtitle = artists.joinToString(", "),
            imageUrl = imageUrl,
            kind = resolvedKind,
            previewUrl = null,
        )
    }

    private fun buildSearchQuery(query: String): Map<String, String> {
        val timestamp = System.currentTimeMillis().toString()
        return linkedMapOf(
            "request_id" to SpClientProto.newSearchRequestId(),
            "query" to query,
            "locale" to "ja_JP",
            "entity_types" to "album,artist,genre,playlist,user_profile,track,show,audio_episode,audiobook,section,author,podcast_chapter",
            "timestamp" to timestamp,
            "limit" to "16",
            "page_token" to "",
            "show_type" to "podcast",
            "query_complete" to "submit",
            "album_states" to "live,prerelease",
            "features" to "abdesc,fullflatfilterlist,vidfilter,recsection,track_classification,pl_spotify_logic,crossword,sectioner,videometadata,stpoptimized,crosscontentrelated,trackversions,recentsfilters,showverified",
            "audiobook_states" to "live,prerelease",
        )
    }

    private fun buildConnectStateBody(content: SpotifyContent): String {
        val json = org.json.JSONObject().put("is_playing", true)
        val context = org.json.JSONObject()
        when (content.kind) {
            ContentKind.PLAYLIST, ContentKind.ALBUM -> context.put("uri", content.uri)
            else -> {
                context.put("uri", content.uri)
                context.put("url", content.uri)
            }
        }
        json.put("context", context)
        json.put("play_options", org.json.JSONObject().put("repeat_mode", "off").put("shuffle", false))
        json.put("suppressions", org.json.JSONArray())
        json.put("play_origin", org.json.JSONObject().put("feature_identifier", "play").put("feature_version", "1.0.0"))
        val options = org.json.JSONObject()
            .put("license", "premium")
            .put("player_options_override", org.json.JSONObject().put("reconnect", true))
        json.put("options", options)
        return json.toString()
    }

    private fun buildPlaybackCommandBody(isPlaying: Boolean): String =
        org.json.JSONObject().put("is_playing", isPlaying).toString()

    private fun buildControlCommandBody(command: String): String =
        org.json.JSONObject().put("command", command).toString()

    private fun buildSeekBody(positionMs: Long): String =
        org.json.JSONObject().put("position_ms", positionMs).toString()

    private fun buildRepeatBody(mode: String): String =
        org.json.JSONObject().put("repeat_mode", mode).toString()

    private fun buildShuffleBody(enabled: Boolean): String =
        org.json.JSONObject().put("shuffle", enabled).toString()

    private fun requireOk(response: SpotifyApiClient.Response) {
        if (response.status !in 200..299) throw SpotifyApiException(response.status, "Playback request failed")
    }

    private companion object {
        const val PLAYLIST_LIMIT = 50
        const val COLLECTION_LIMIT = 200L
        const val MAX_EM_BATCH = 50
    }
}
