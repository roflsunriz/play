package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.SpotifyContent
import io.github.playmusic.data.auth.DesktopClientProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class BrowserAuthorizationRequiredException : Exception("Reconnect the account in your browser")

class CatalogApiClient(
    private val session: SessionTokens,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    suspend fun search(searchTerm: String): List<SpotifyContent> = coroutineScope {
        if (searchTerm.isBlank()) return@coroutineScope emptyList()
        listOf(ContentKind.ALBUM, ContentKind.TRACK, ContentKind.PLAYLIST).map { kind -> async {
            val operation = when (kind) {
                ContentKind.ALBUM -> Operation.SEARCH_ALBUMS
                ContentKind.TRACK -> Operation.SEARCH_TRACKS
                else -> Operation.SEARCH_PLAYLISTS
            }
            CatalogJson.search(query(operation, JSONObject().put("searchTerm", searchTerm).put("offset", 0).put("limit", 30)
                .put("includePreReleases", false).put("includeAlbumPreReleases", false).put("numberOfTopResults", 20)
                .put("includeAudiobooks", true).put("includeAuthors", false).put("includeEpisodeContentRatingsV2", true)), kind)
        } }.awaitAll().flatten()
    }

    suspend fun albums(uris: List<String>): List<SpotifyContent> = coroutineScope {
        uris.chunked(PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { uri -> async { CatalogJson.content(albumPage(uri, 0, 1), ContentKind.ALBUM) } }.awaitAll()
        }
    }

    suspend fun tracks(uris: List<String>): List<SpotifyContent> = coroutineScope {
        val unique = uris.distinct()
        val metadata = unique.chunked(TRACK_BATCH_SIZE).chunked(PARALLEL_REQUESTS).flatMap { batches ->
            batches.map { batch -> async {
                CatalogJson.tracks(query(Operation.TRACKS, JSONObject().put("uris", JSONArray(batch))))
            } }.awaitAll().flatten()
        }.associateBy { it.uri }
        uris.map { uri -> metadata[uri] ?: error("Catalog response omitted a requested track") }
    }

    suspend fun detail(content: SpotifyContent): ContentDetail {
        if (content.kind == ContentKind.TRACK) {
            val track = query(Operation.TRACK, JSONObject().put("uri", content.uri)).getJSONObject("trackUnion")
            return ContentDetail(CatalogJson.content(track, ContentKind.TRACK))
        }
        require(content.kind == ContentKind.ALBUM)
        var offset = 0
        var first: JSONObject? = null
        var album: SpotifyContent? = null
        val tracks = mutableListOf<SpotifyContent>()
        var total: Int
        do {
            val page = albumPage(content.uri, offset, ALBUM_PAGE_SIZE)
            if (first == null) { first = page; album = CatalogJson.content(page, ContentKind.ALBUM) }
            val items = CatalogJson.albumTracks(page, checkNotNull(album))
            total = page.getJSONObject("tracksV2").getInt("totalCount")
            require(total >= 0 && (items.isNotEmpty() || offset >= total)) { "Album pagination did not advance" }
            tracks += items
            offset += items.size
        } while (offset < total)
        return ContentDetail(checkNotNull(album), tracks, total, CatalogJson.releaseDate(checkNotNull(first)))
    }

    private suspend fun albumPage(uri: String, offset: Int, limit: Int): JSONObject =
        query(Operation.ALBUM, JSONObject().put("uri", uri).put("locale", "intl-ja").put("offset", offset).put("limit", limit))
            .getJSONObject("albumUnion")

    private suspend fun query(operation: Operation, variables: JSONObject): JSONObject {
        if (!session.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
        val body = JSONObject().put("operationName", operation.operationName).put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", operation.hash)))
        var response = execute(operation, body, session.accessToken())
        if (response.first == 401) response = execute(operation, body, session.accessToken(forceRefresh = true))
        if (response.first !in 200..299) throw SpotifyApiException(response.first, "Catalog request failed")
        val json = JSONObject(response.second)
        if (json.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw SpotifyApiException(response.first, "Catalog returned an incomplete result")
        }
        return json.getJSONObject("data")
    }

    private suspend fun execute(operation: Operation, body: JSONObject, token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = openConnection(URI(ENDPOINT))
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            DesktopClientProfile.headers.forEach(connection::setRequestProperty)
            if (operation == Operation.ALBUM) connection.setRequestProperty("Spotify-App-Version", DesktopClientProfile.ALBUM_QUERY_VERSION)
            connection.setRequestProperty("Accept-Language", "ja")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            status to (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally { connection.disconnect() }
    }

    private enum class Operation(val operationName: String, val hash: String) {
        ALBUM("getAlbum", "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"),
        TRACK("getTrack", "612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294"),
        TRACKS("decorateContextTracks", "383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7"),
        SEARCH_ALBUMS("searchAlbums", "64ae1fe6df380b038c0a65a2606d3361bc270de6870b2fdc99cf0848b1efa6d3"),
        SEARCH_TRACKS("searchTracks", "59ee4a659c32e9ad894a71308207594a65ba67bb6b632b183abe97303a51fa55"),
        SEARCH_PLAYLISTS("searchPlaylists", "af1730623dc1248b75a61a18bad1f47f1fc7eff802fb0676683de88815c958d8"),
    }

    private companion object {
        const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val TRACK_BATCH_SIZE = 50
        const val ALBUM_PAGE_SIZE = 50
        const val PARALLEL_REQUESTS = 6
    }
}
