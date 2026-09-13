package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.PlaylistMetadata
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
 * - メタデータ・検索: CatalogApiClientの実測済みクエリ
 */
class SpotifyRepository(
    private val api: SpotifyApiClient,
    private val sessionManager: SessionTokens,
    private val catalog: CatalogApiClient = CatalogApiClient(sessionManager),
) {
    private val playlists = PlaylistApiClient(api, sessionManager)

    suspend fun createPlaylist(name: String, description: String = ""): SpotifyContent = playlists.create(name, description)

    suspend fun completePlaylistCreation(content: SpotifyContent): SpotifyContent = playlists.completeCreation(content)

    suspend fun playlistMetadata(content: SpotifyContent): PlaylistMetadata = playlists.metadata(content)

    suspend fun updatePlaylistMetadata(
        content: SpotifyContent,
        name: String,
        description: String,
        imageJpeg: ByteArray? = null,
        removeImage: Boolean = false,
    ): SpotifyContent = playlists.update(content, name, description, imageJpeg, removeImage)

    suspend fun deletePlaylist(content: SpotifyContent) = playlists.delete(content)

    suspend fun addPlaylistTracks(content: SpotifyContent, trackUris: List<String>) = playlists.addTracks(content, trackUris)

    suspend fun removePlaylistTracks(content: SpotifyContent, trackUris: List<String>) = playlists.removeTracks(content, trackUris)

    suspend fun library(kind: ContentKind): List<SpotifyContent> = coroutineScope {
        when (kind) {
            ContentKind.PLAYLIST -> libraryPlaylists()
            ContentKind.ALBUM -> libraryAlbums()
            ContentKind.TRACK -> libraryTracks()
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<SpotifyContent> = catalog.search(query)

    suspend fun detail(content: SpotifyContent): ContentDetail {
        if (content.kind != ContentKind.PLAYLIST) return catalog.detail(content)
        val uris = mutableListOf<String>()
        var offset = 0
        var first: SpClientProto.PlaylistDetail? = null
        var total: Int
        do {
            val response = api.get("/playlist/v2/playlist/${content.id}", query = mapOf("from" to offset.toString(),
                "length" to PLAYLIST_PAGE_SIZE.toString(),
                "decorate" to "revision,length,attributes,timestamp,owner,capabilities"), acceptProto = true)
            val page = SpClientProto.parsePlaylist(response.bodyBytes)
            if (first == null) first = page
            require(page.offset == offset) { "Playlist pagination returned a different position" }
            uris += page.items.map { it.uri }.filter { it.startsWith("spotify:track:") }
            total = page.totalLength ?: (offset + page.items.size)
            if (!page.truncated) break
            check(page.items.isNotEmpty()) { "Playlist pagination did not advance" }
            offset += page.items.size
        } while (offset < total)
        val metadata = checkNotNull(first)
        val updated = content.copy(title = metadata.name ?: content.title,
            imageUrl = metadata.images["default"] ?: metadata.images.values.firstOrNull() ?: content.imageUrl)
        return ContentDetail(updated, catalog.tracks(uris), total,
            playlistMetadata = playlists.metadata(metadata, content.uri))
    }

    private suspend fun libraryPlaylists(): List<SpotifyContent> = coroutineScope {
        val username = sessionManager.username()
        val encodedUsername = java.net.URLEncoder.encode(username, Charsets.UTF_8.name()).replace("+", "%20")
        val playlists = linkedMapOf<String, SpClientProto.PlaylistItem>()
        var offset = 0
        do {
            val rootlist = api.get(
                path = "/playlist/v2/user/$encodedUsername/rootlist",
                query = mapOf(
                    "decorate" to "revision,attributes,length,owner,capabilities,status_code,timestamp",
                    "from" to offset.toString(),
                    "length" to PLAYLIST_PAGE_SIZE.toString(),
                ),
                acceptProto = true,
            )
            val parsed = SpClientProto.parseRootlist(rootlist.bodyBytes)
            parsed.items.filter { it.uri.startsWith("spotify:playlist:") }.forEach { playlists[it.uri] = it }
            if (!parsed.truncated) break
            check(parsed.offset == offset && parsed.items.isNotEmpty()) { "Playlist pagination did not advance" }
            offset += parsed.items.size
            if (parsed.totalLength != null && offset >= parsed.totalLength) break
        } while (true)
        playlists.values.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { item -> async {
                val metadata = item.metadata
                if (metadata?.status in setOf(403, 404, 410)) return@async null
                if (metadata?.status != null && metadata.status !in 200..299) {
                    throw SpotifyApiException(metadata.status, "Playlist metadata request failed")
                }
                val attributes = metadata?.attributes
                if (attributes != null) playlistContent(item.uri, attributes)
                else fetchPlaylistDetail(item.uri)
            } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun fetchPlaylistDetail(uri: String): SpotifyContent? {
        val id = uri.removePrefix("spotify:playlist:")
        if (id.isBlank()) return null
        val response = try {
            api.get(path = "/playlist/v2/playlist/$id", acceptProto = true)
        } catch (exception: SpotifyApiException) {
            if (exception.status in setOf(403, 404, 410)) return null
            throw exception
        }
        val detail = SpClientProto.parsePlaylist(response.bodyBytes)
        check(detail.hasAttributes) { "Playlist metadata is missing attributes" }
        return playlistContent(uri, SpClientProto.PlaylistAttributes(detail.name, detail.images, detail.deletedByOwner))
    }

    private fun playlistContent(uri: String, attributes: SpClientProto.PlaylistAttributes): SpotifyContent? {
        if (attributes.deletedByOwner) return null
        return SpotifyContent(
            id = uri.removePrefix("spotify:playlist:"),
            uri = uri,
            title = attributes.name.orEmpty(),
            subtitle = "",
            imageUrl = attributes.images["default"]
                ?: attributes.images["xlarge"]
                ?: attributes.images.values.firstOrNull(),
            kind = ContentKind.PLAYLIST,
        )
    }

    private suspend fun libraryAlbums(): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection").filter { it.startsWith("spotify:album:") }
        catalog.albums(uris)
    }

    private suspend fun libraryTracks(): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection").filter { it.startsWith("spotify:track:") }
        catalog.tracks(uris)
    }

    private suspend fun collectionUris(kind: String): List<String> {
        val username = sessionManager.username()
        val uris = linkedSetOf<String>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val response = api.postProto(
                path = "/collection/v2/paging",
                body = SpClientProto.buildCollectionPageRequest(username, kind, COLLECTION_PAGE_SIZE, pageToken),
                contentType = "application/vnd.collection-v2.spotify.proto",
            )
            val page = SpClientProto.parseCollectionPage(response.bodyBytes)
            page.items.forEach { item ->
                if (item.removed) uris.remove(item.uri)
                else if (item.uri.isNotBlank()) uris.add(item.uri)
            }
            pageToken = page.nextPageToken
            check(pageToken == null || seenTokens.add(pageToken)) { "Collection pagination did not advance" }
        } while (pageToken != null)
        return uris.toList()
    }

    private companion object {
        const val PLAYLIST_PAGE_SIZE = 120
        const val COLLECTION_PAGE_SIZE = 200
        const val MAX_PARALLEL_REQUESTS = 6
    }
}
