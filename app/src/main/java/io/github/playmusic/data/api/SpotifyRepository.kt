package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.model.PlaylistMetadata
import io.github.playmusic.data.cache.PlaylistDiskCache
import io.github.playmusic.data.cache.PlaylistCacheEntry
import io.github.playmusic.data.cache.PlaylistCacheSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val playlistDiskCache: PlaylistDiskCache? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val cacheFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playlistCacheFailures = cacheFailures.asSharedFlow()
    private val playlists = PlaylistApiClient(api, sessionManager)
    private val collectionWriter = CollectionApiClient(api)
    private val membershipWrites = Mutex()
    private val userProfiles = UserProfileClient(api)
    private val profileNames = AccountMemoryCache<String, UserProfileClient.Profile>(256, 256, { 1 }, 4)
    private val libraries = AccountMemoryCache<ContentKind, List<SpotifyContent>>(3, 3, { 1 }, 3)
    private val collections = AccountMemoryCache<String, List<String>>(1, 1, { 1 }, 1)
    private val details = AccountMemoryCache<String, ContentDetail>(24, 6000, { detail ->
        (detail.tracks.size + detail.relatedContent.size + (detail.artistPage?.let { page ->
            page.discography.size + page.appearsOn.size + page.featuringPlaylists.size +
                page.discoveredOnPlaylists.size + page.suggestedArtists.size + page.songRadioSeeds.size
        } ?: 0)).coerceAtLeast(1)
    }, 2)

    suspend fun createPlaylist(name: String, description: String = ""): SpotifyContent = writePlaylist(
        onSuccess = { account, content -> playlistDiskCache?.updateItem(account, content) }) {
        val username = sessionManager.username()
        val ownerName = ownerDisplayName(username)
        playlists.create(name, description).copy(ownerUsername = username, ownerName = ownerName, description = description, trackCount = 0)
    }

    suspend fun completePlaylistCreation(content: SpotifyContent): SpotifyContent = writePlaylist(content.uri,
        onSuccess = { account, updated -> playlistDiskCache?.updateItem(account, updated) }) {
        playlists.completeCreation(content)
    }

    suspend fun playlistMetadata(content: SpotifyContent): PlaylistMetadata = playlists.metadata(content)

    suspend fun updatePlaylistMetadata(
        content: SpotifyContent,
        name: String,
        description: String,
        imageJpeg: ByteArray? = null,
        removeImage: Boolean = false,
    ): SpotifyContent = writePlaylist(content.uri,
        onSuccess = { account, updated -> playlistDiskCache?.updateItem(account, updated) }) {
        playlists.update(content, name, description, imageJpeg, removeImage).copy(description = description)
    }

    suspend fun deletePlaylist(content: SpotifyContent) = writePlaylist(content.uri,
        onSuccess = { account, _ -> playlistDiskCache?.removeItem(account, content.uri) }) { playlists.delete(content) }

    suspend fun addPlaylistTracks(content: SpotifyContent, trackUris: List<String>) = writePlaylist(content.uri) {
        playlists.addTracks(content, trackUris)
    }

    suspend fun removePlaylistTracks(content: SpotifyContent, trackUris: List<String>) = writePlaylist(content.uri) {
        playlists.removeTracks(content, trackUris)
    }

    suspend fun isSaved(content: SpotifyContent, forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        require(content.kind == ContentKind.TRACK || content.kind == ContentKind.ALBUM) { "Only tracks and albums can be saved" }
        content.uri in collectionUris("collection", forceRefresh)
    }

    suspend fun setSaved(content: SpotifyContent, saved: Boolean) = withContext(Dispatchers.IO) {
        membershipWrites.withLock {
            require(content.kind == ContentKind.TRACK || content.kind == ContentKind.ALBUM) { "Only tracks and albums can be saved" }
            val account = currentAccount()
            try {
                collectionWriter.setSaved(account, content.uri, saved)
                checkAccount(account)
                collections.invalidate(account, "collection")
                check((content.uri in collectionUris("collection", true)) == saved) { "Saved item did not match the requested change" }
            } finally {
                collections.invalidate(account, "collection")
                libraries.invalidate(account, ContentKind.TRACK)
                libraries.invalidate(account, ContentKind.ALBUM)
                details.invalidate(account, LIKED_SONGS_URI)
                details.invalidate(account, content.uri)
            }
        }
    }

    /** Checked means every track in the selection is present. Partial albums add only missing tracks. */
    suspend fun playlistMembership(content: SpotifyContent): List<PlaylistMembership> = withContext(Dispatchers.IO) {
        val account = currentAccount()
        val selectedUris = selectionTrackUris(content)
        val owned = library(ContentKind.PLAYLIST).filter { it.ownerUsername == account }
        val rows = coroutineScope {
            owned.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
                batch.map { playlist -> async {
                    val snapshot = playlists.trackSnapshot(playlist)
                    if (!snapshot.canEdit) return@async null
                    val members = snapshot.uris.toSet()
                    PlaylistMembership(playlist, selectedUris.all { it in members })
                } }.awaitAll().filterNotNull()
            }
        }
        checkAccount(account)
        rows
    }

    suspend fun setPlaylistMembership(playlist: SpotifyContent, content: SpotifyContent, present: Boolean) = withContext(Dispatchers.IO) {
        membershipWrites.withLock {
            val account = currentAccount()
            val selected = selectionTrackUris(content)
            val before = playlists.trackUris(playlist, requireEditable = true)
            val changed = selected.filter { if (present) it !in before else it in before }
            writePlaylist(playlist.uri) {
                var pending = changed
                var remainingCount = before.count { it in selected }
                var after: List<String>
                do {
                    pending.chunked(100).forEach { batch ->
                        checkAccount(account)
                        if (present) playlists.addTracks(playlist, batch) else playlists.removeTracks(playlist, batch)
                    }
                    after = playlists.trackUris(playlist)
                    if (present) break
                    pending = selected.filter { it in after }
                    val count = after.count { it in selected }
                    // Key-based removal is verified against original entries, including duplicates.
                    check(count == 0 || count < remainingCount) { "Playlist removal did not advance" }
                    remainingCount = count
                } while (pending.isNotEmpty())
                check(if (present) selected.all { it in after } else selected.none { it in after }) {
                    "Playlist membership did not match the requested change"
                }
            }
        }
    }

    private suspend fun selectionTrackUris(content: SpotifyContent): List<String> {
        val tracks = when (content.kind) {
            ContentKind.TRACK -> listOf(content.uri)
            ContentKind.ALBUM -> detail(content).tracks.map { it.uri }
            else -> throw IllegalArgumentException("Only tracks and albums can be added to playlists")
        }.distinct()
        require(tracks.isNotEmpty() && tracks.all { it.matches(Regex("spotify:track:[A-Za-z0-9]{22}")) }) { "Invalid playlist tracks" }
        return tracks
    }

    suspend fun library(kind: ContentKind, forceRefresh: Boolean = false,
        refreshOwnerNames: Boolean = forceRefresh): List<SpotifyContent> = withContext(Dispatchers.IO) {
        if (kind !in LIBRARY_KINDS) return@withContext emptyList()
        val account = currentAccount()
        val invalidateDetails = if (forceRefresh) details.invalidationFor(account) {
            it.startsWith("spotify:${kind.name.lowercase()}:")
        } else null
        val result = libraries.get(account, kind, forceRefresh) {
            checkAccount(account)
            loadLibrary(kind, forceRefresh, refreshOwnerNames).also { checkAccount(account) }
        }
        checkAccount(account)
        invalidateDetails?.invoke()
        result
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

    suspend fun cachedPlaylists(): List<SpotifyContent>? = withContext(Dispatchers.IO) {
        val account = currentAccount()
        val snapshot = playlistDiskCache?.read(account)?.snapshot
        checkAccount(account)
        val time = nowMs()
        snapshot?.entries.orEmpty().filter { it.content.ownerUsername != null && it.ownerCheckedAtMs > 0 &&
            time >= it.ownerCheckedAtMs && time - it.ownerCheckedAtMs < OWNER_CACHE_AGE_MS }
            .distinctBy { it.content.ownerUsername }.forEach { entry ->
                profileNames.get(account, checkNotNull(entry.content.ownerUsername), false) {
                    UserProfileClient.Profile(entry.content.ownerName)
                }
            }
        checkAccount(account)
        snapshot?.entries?.map { it.content }
    }

    suspend fun clearPlaylistDiskCache(account: String) {
        cacheAfterWrite { playlistDiskCache?.clear(account) }
    }

    private suspend fun loadLibrary(kind: ContentKind, forceRefresh: Boolean, refreshOwnerNames: Boolean): List<SpotifyContent> = coroutineScope {
        when (kind) {
            ContentKind.PLAYLIST -> libraryPlaylists(refreshOwnerNames)
            ContentKind.ALBUM -> libraryAlbums(forceRefresh)
            ContentKind.TRACK -> libraryTracks(forceRefresh)
            else -> emptyList()
        }
    }

    suspend fun search(query: String, filter: io.github.playmusic.data.model.SearchFilter = io.github.playmusic.data.model.SearchFilter.ALL): List<SpotifyContent> =
        withContext(Dispatchers.IO) { resolveOwners(catalog.search(query, filter)) }

    suspend fun radio(content: SpotifyContent): SpotifyContent = withContext(Dispatchers.IO) { catalog.radio(content) }

    suspend fun setArtistFollowed(content: SpotifyContent, followed: Boolean): Boolean = withContext(Dispatchers.IO) {
        require(content.kind == ContentKind.ARTIST)
        val account = currentAccount()
        try { catalog.setArtistFollowed(content.uri, followed).also { checkAccount(account) } }
        finally { details.invalidate(account, content.uri) }
    }

    suspend fun detail(content: SpotifyContent, forceRefresh: Boolean = false): ContentDetail = withContext(Dispatchers.IO) {
        // This is a view of the saved-track library, so do not retain a second, stale copy.
        if (isLikedSongs(content)) return@withContext loadDetail(content, forceRefresh)
        val account = currentAccount()
        val result = details.get(account, content.uri, forceRefresh) {
            checkAccount(account)
            loadDetail(content, forceRefresh).also { checkAccount(account) }
        }
        checkAccount(account)
        result
    }

    private suspend fun loadDetail(content: SpotifyContent, forceRefresh: Boolean): ContentDetail {
        if (isLikedSongs(content)) {
            val tracks = library(ContentKind.TRACK, forceRefresh)
            return ContentDetail(content.copy(trackCount = tracks.size), tracks, tracks.size)
        }
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

    private suspend fun libraryPlaylists(refreshOwnerNames: Boolean): List<SpotifyContent> {
        val account = currentAccount()
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val previous = playlistDiskCache?.read(account)
            val source = playlistSource(account)
            val entries = resolvePlaylistEntries(source, previous?.snapshot?.entries.orEmpty(), refreshOwnerNames)
            checkAccount(account)
            val snapshot = PlaylistCacheSnapshot(entries, nowMs())
            if (playlistDiskCache == null || playlistDiskCache.reconcile(account, checkNotNull(previous).generation, snapshot))
                return entries.map { it.content }
        }
        error("Playlist library changed during synchronization; retry the update")
    }

    private suspend fun playlistSource(username: String): List<SpClientProto.PlaylistItem> {
        val encodedUsername = java.net.URLEncoder.encode(username, Charsets.UTF_8.name()).replace("+", "%20")
        val items = linkedMapOf<String, SpClientProto.PlaylistItem>()
        var offset = 0
        var revision: ByteArray? = null
        do {
            val rootlist = api.get(
                path = "/playlist/v2/user/$encodedUsername/rootlist",
                query = mapOf("decorate" to "revision,attributes,length,owner,capabilities,status_code,timestamp",
                    "from" to offset.toString(), "length" to PLAYLIST_PAGE_SIZE.toString()),
                acceptProto = true,
            )
            val parsed = SpClientProto.parseRootlist(rootlist.bodyBytes)
            check(parsed.revision?.isNotEmpty() == true) { "Playlist library response is missing its revision" }
            check(parsed.offset == offset) { "Playlist pagination returned a different position" }
            if (offset == 0) revision = parsed.revision
            else check(revision.contentEquals(parsed.revision)) { "Playlist library changed between pages" }
            parsed.items.filter { it.uri.startsWith("spotify:playlist:") }.forEach { items[it.uri] = it }
            if (!parsed.truncated) {
                check(parsed.totalLength == null || offset + parsed.items.size >= parsed.totalLength) { "Playlist library response is incomplete" }
                break
            }
            check(parsed.items.isNotEmpty()) { "Playlist pagination did not advance" }
            offset += parsed.items.size
            check(parsed.totalLength == null || offset < parsed.totalLength) { "Playlist pagination is inconsistent" }
        } while (true)
        return items.values.toList()
    }

    private suspend fun resolvePlaylistEntries(source: List<SpClientProto.PlaylistItem>, previous: List<PlaylistCacheEntry>,
        refreshOwnerNames: Boolean): List<PlaylistCacheEntry> = coroutineScope {
        val previousByUri = previous.associateBy { it.content.uri }
        val contents = source.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { item -> async {
                val metadata = item.metadata
                if (metadata?.status in setOf(403, 404, 410)) return@async null
                if (metadata?.status != null && metadata.status !in 200..299)
                    throw SpotifyApiException(metadata.status, "Playlist metadata request failed")
                val attributes = metadata?.attributes
                if (attributes?.deletedByOwner == true) return@async null
                val fingerprint = playlistFingerprint(item)
                val cached = previousByUri[item.uri]
                val content = if (fingerprint != null && fingerprint == cached?.fingerprint) cached.content
                    else if (attributes != null) playlistContent(item.uri, attributes, metadata.ownerUsername, metadata.length)
                    else fetchPlaylistDetail(item.uri)
                content?.let { PlaylistCacheEntry(it, fingerprint) }
            } }.awaitAll().filterNotNull()
        }
        val checkedAt = nowMs()
        val knownNames = previous.filter { it.content.ownerUsername != null && it.ownerCheckedAtMs > 0 }
            .groupBy { checkNotNull(it.content.ownerUsername) }.mapValues { (_, rows) -> rows.maxBy { it.ownerCheckedAtMs } }
        val owners = contents.mapNotNull { it.content.ownerUsername?.takeIf(String::isNotBlank) }.distinct()
        val names = owners.chunked(MAX_PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { owner -> async {
                val old = knownNames[owner]
                val fresh = old != null && checkedAt >= old.ownerCheckedAtMs && checkedAt - old.ownerCheckedAtMs < OWNER_CACHE_AGE_MS
                val nameAndTime = if (!refreshOwnerNames && fresh) old.content.ownerName to old.ownerCheckedAtMs
                    else ownerDisplayName(owner, forceRefresh = true) to checkedAt
                owner to nameAndTime
            } }.awaitAll()
        }.toMap()
        contents.map { entry ->
            val name = names[entry.content.ownerUsername]
            val content = entry.content.copy(ownerName = name?.first, subtitle = name?.first.orEmpty())
            val old = previousByUri[content.uri]
            val updated = entry.copy(content = if (content == old?.content) old.content else content,
                ownerCheckedAtMs = name?.second ?: 0)
            if (updated == old) old else updated
        }
    }

    private fun playlistFingerprint(item: SpClientProto.PlaylistItem): String? {
        val metadata = item.metadata ?: return null
        val attributes = metadata.attributes ?: return null
        val fields = org.json.JSONArray().put(item.uri).put(attributes.name).put(attributes.description)
            .put(attributes.deletedByOwner).put(metadata.ownerUsername).put(metadata.length).put(metadata.status)
        attributes.images.toSortedMap().forEach { (size, url) -> fields.put(size).put(url) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(fields.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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

    private suspend fun <T> writePlaylist(uri: String? = null,
        onSuccess: suspend (String, T) -> Unit = { _, _ -> }, block: suspend () -> T): T {
        val account = currentAccount()
        try {
            val result = block()
            checkAccount(account)
            cacheAfterWrite { onSuccess(account, result) }
            return result
        }
        finally {
            libraries.invalidate(account, ContentKind.PLAYLIST)
            if (uri != null) details.invalidate(account, uri)
            // Remote writes can partly succeed; force the next complete sync to verify this item.
            if (uri != null) cacheAfterWrite { playlistDiskCache?.invalidateItem(account, uri) }
        }
    }

    private suspend fun cacheAfterWrite(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cacheFailures.tryEmit(Unit) }
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
            check(response.bodyBytes.isNotEmpty()) { "Collection response is empty" }
            page.items.forEach { item ->
                if (item.removed) uris.remove(item.uri)
                else if (item.uri.isNotBlank()) uris.add(item.uri)
            }
            pageToken = page.nextPageToken
            check(pageToken == null || seenTokens.add(pageToken)) { "Collection pagination did not advance" }
        } while (pageToken != null)
        return uris.toList()
    }

    companion object {
        const val LIKED_SONGS_URI = "spotify:collection:tracks"
        fun likedSongsContent(title: String) = SpotifyContent("tracks", LIKED_SONGS_URI, title, "", null, ContentKind.PLAYLIST)
        fun isLikedSongs(content: SpotifyContent): Boolean = content.uri == LIKED_SONGS_URI
        const val PLAYLIST_PAGE_SIZE = 120
        const val COLLECTION_PAGE_SIZE = 200
        const val MAX_PARALLEL_REQUESTS = 6
        const val OWNER_CACHE_AGE_MS = 24 * 60 * 60 * 1_000L
        val LIBRARY_KINDS = setOf(ContentKind.PLAYLIST, ContentKind.ALBUM, ContentKind.TRACK)
    }
}
