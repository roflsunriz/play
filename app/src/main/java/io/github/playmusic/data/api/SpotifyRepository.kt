package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.PlaylistMetadata
import kotlinx.coroutines.CancellationException
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
    private val accountIdentity: (() -> String?)? = null,
) {
    private val playlists = PlaylistApiClient(api, sessionManager)
    private val userProfiles = UserProfileClient(api)
    private val profileNames = AccountMemoryCache<String, UserProfileClient.Profile>(256, 256, { 1 }, 4)
    private val libraries = AccountMemoryCache<ContentKind, List<SpotifyContent>>(3, 3, { 1 }, 3)
    private val collections = AccountMemoryCache<String, List<String>>(1, 1, { 1 }, 1)
    private val details = AccountMemoryCache<String, ContentDetail>(24, 6000, { it.tracks.size.coerceAtLeast(1) }, 2)

    suspend fun createPlaylist(name: String, description: String = ""): SpotifyContent = writePlaylist {
        val username = sessionManager.username()
        val ownerName = ownerDisplayName(username)
        playlists.create(name, description).copy(ownerUsername = username, ownerName = ownerName, description = description, trackCount = 0)
    }

    suspend fun completePlaylistCreation(content: SpotifyContent): SpotifyContent = writePlaylist(content.uri) {
        playlists.completeCreation(content)
    }

    suspend fun playlistMetadata(content: SpotifyContent): PlaylistMetadata = playlists.metadata(content)

    suspend fun updatePlaylistMetadata(
        content: SpotifyContent,
        name: String,
        description: String,
        imageJpeg: ByteArray? = null,
        removeImage: Boolean = false,
    ): SpotifyContent = writePlaylist(content.uri) {
        playlists.update(content, name, description, imageJpeg, removeImage).copy(description = description)
    }

    suspend fun deletePlaylist(content: SpotifyContent) = writePlaylist(content.uri) { playlists.delete(content) }

    suspend fun addPlaylistTracks(content: SpotifyContent, trackUris: List<String>) = writePlaylist(content.uri) {
        playlists.addTracks(content, trackUris)
    }

    suspend fun removePlaylistTracks(content: SpotifyContent, trackUris: List<String>) = writePlaylist(content.uri) {
        playlists.removeTracks(content, trackUris)
    }

    suspend fun library(kind: ContentKind, forceRefresh: Boolean = false): List<SpotifyContent> {
        if (kind !in LIBRARY_KINDS) return emptyList()
        val account = currentAccount()
        val invalidateDetails = if (forceRefresh) details.invalidationFor(account) {
            it.startsWith("spotify:${kind.name.lowercase()}:")
        } else null
        val result = libraries.get(account, kind, forceRefresh) {
            checkAccount(account)
            loadLibrary(kind, forceRefresh).also { checkAccount(account) }
        }
        checkAccount(account)
        invalidateDetails?.invoke()
        return result
    }

    fun peekLibrary(kind: ContentKind): List<SpotifyContent>? {
        val account = peekAccount() ?: return null
        return libraries.peek(account, kind)
    }

    fun peekDetail(content: SpotifyContent): ContentDetail? {
        val account = peekAccount() ?: return null
        return details.peek(account, content.uri)
    }

    fun clearCache() { libraries.clear(); collections.clear(); details.clear(); profileNames.clear() }

    private suspend fun loadLibrary(kind: ContentKind, forceRefresh: Boolean): List<SpotifyContent> = coroutineScope {
        when (kind) {
            ContentKind.PLAYLIST -> libraryPlaylists(forceRefresh)
            ContentKind.ALBUM -> libraryAlbums(forceRefresh)
            ContentKind.TRACK -> libraryTracks(forceRefresh)
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<SpotifyContent> = resolveOwners(catalog.search(query))

    suspend fun detail(content: SpotifyContent, forceRefresh: Boolean = false): ContentDetail {
        val account = currentAccount()
        val result = details.get(account, content.uri, forceRefresh) {
            checkAccount(account)
            loadDetail(content).also { checkAccount(account) }
        }
        checkAccount(account)
        return result
    }

    private suspend fun loadDetail(content: SpotifyContent): ContentDetail {
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
        val ownerName = metadata.ownerUsername?.takeIf(String::isNotBlank)?.let { ownerDisplayName(it) }
        val updated = content.copy(title = metadata.name ?: content.title,
            imageUrl = metadata.images["default"] ?: metadata.images.values.firstOrNull(),
            ownerName = ownerName,
            ownerUsername = metadata.ownerUsername,
            subtitle = ownerName.orEmpty(),
            description = metadata.description,
            trackCount = total)
        return ContentDetail(updated, catalog.tracks(uris), total,
            playlistMetadata = playlists.metadata(metadata, content.uri))
    }

    private suspend fun libraryPlaylists(forceRefresh: Boolean): List<SpotifyContent> = coroutineScope {
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
        val contents = playlists.values.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { item -> async {
                val metadata = item.metadata
                if (metadata?.status in setOf(403, 404, 410)) return@async null
                if (metadata?.status != null && metadata.status !in 200..299) {
                    throw SpotifyApiException(metadata.status, "Playlist metadata request failed")
                }
                val attributes = metadata?.attributes
                if (attributes != null) playlistContent(item.uri, attributes, metadata.ownerUsername, metadata.length)
                else fetchPlaylistDetail(item.uri)
            } }.awaitAll().filterNotNull()
        }
        resolveOwners(contents, forceRefresh)
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
        return playlistContent(uri, SpClientProto.PlaylistAttributes(detail.name, detail.images, detail.deletedByOwner, detail.description),
            detail.ownerUsername, detail.totalLength)
    }

    private fun playlistContent(uri: String, attributes: SpClientProto.PlaylistAttributes,
        ownerUsername: String? = null, trackCount: Int? = null): SpotifyContent? {
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
            ownerUsername = ownerUsername,
            description = attributes.description,
            trackCount = trackCount,
        )
    }

    private suspend fun ownerDisplayName(username: String, forceRefresh: Boolean = false): String? {
        val account = currentAccount()
        val profile = profileNames.get(account, username, forceRefresh) {
            checkAccount(account)
            userProfiles.profile(username).also { checkAccount(account) }
        }
        checkAccount(account)
        return profile.displayName
    }

    private suspend fun resolveOwners(items: List<SpotifyContent>, forceRefresh: Boolean = false): List<SpotifyContent> = coroutineScope {
        val usernames = items.filter { it.kind == ContentKind.PLAYLIST &&
            (forceRefresh || it.ownerName.isNullOrBlank() || it.ownerName == it.ownerUsername) }
            .mapNotNull { it.ownerUsername?.takeIf(String::isNotBlank) }.distinct()
        val names = usernames.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { username -> async { username to ownerDisplayName(username, forceRefresh) } }.awaitAll()
        }.toMap()
        items.map { item ->
            if (item.ownerUsername in names) item.copy(ownerName = names[item.ownerUsername], subtitle = names[item.ownerUsername].orEmpty())
            else item
        }
    }

    private suspend fun currentAccount(): String {
        val account = try { sessionManager.username() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { clearCache(); throw error }
        if (accountIdentity != null && accountIdentity.invoke() != account) {
            clearCache()
            throw CancellationException("Account changed during content retrieval")
        }
        return account
    }

    private suspend fun checkAccount(expected: String) {
        if (currentAccount() != expected) throw CancellationException("Account changed during content retrieval")
    }

    private fun peekAccount(): String? {
        val reader = accountIdentity ?: return null
        val account = try { reader() } catch (_: Exception) { null }
        if (account == null) clearCache()
        return account
    }

    private suspend fun <T> writePlaylist(uri: String? = null, block: suspend () -> T): T {
        val account = currentAccount()
        try { return block() }
        finally {
            libraries.invalidate(account, ContentKind.PLAYLIST)
            if (uri != null) details.invalidate(account, uri)
        }
    }

    private suspend fun libraryAlbums(forceRefresh: Boolean): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection", forceRefresh).filter { it.startsWith("spotify:album:") }
        catalog.albums(uris)
    }

    private suspend fun libraryTracks(forceRefresh: Boolean): List<SpotifyContent> = coroutineScope {
        val uris = collectionUris("collection", forceRefresh).filter { it.startsWith("spotify:track:") }
        catalog.tracks(uris)
    }

    private suspend fun collectionUris(kind: String, forceRefresh: Boolean): List<String> {
        val account = currentAccount()
        return collections.get(account, kind, forceRefresh) {
            checkAccount(account)
            loadCollectionUris(kind).also { checkAccount(account) }
        }
    }

    private suspend fun loadCollectionUris(kind: String): List<String> {
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
        val LIBRARY_KINDS = setOf(ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.TRACK)
    }
}
