package io.github.playmusic.data.api

import io.github.playmusic.data.model.ContentDetail
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import io.github.playmusic.data.model.SearchFilter
import io.github.playmusic.data.model.ArtistRelease
import io.github.playmusic.data.api.CatalogJson.objects
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

class BrowserAuthorizationRequiredException : Exception("Sign in to access music content")

class CatalogApiClient(
    private val session: SessionTokens,
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    suspend fun search(searchTerm: String, filter: SearchFilter = SearchFilter.ALL): List<MusicContent> = coroutineScope {
        if (searchTerm.isBlank()) return@coroutineScope emptyList()
        filter.kinds.chunked(PARALLEL_REQUESTS).flatMap { kinds -> kinds.map { kind -> async {
            val operation = when (kind) {
                ContentKind.ALBUM -> Operation.SEARCH_ALBUMS
                ContentKind.TRACK -> Operation.SEARCH_TRACKS
                ContentKind.PLAYLIST -> Operation.SEARCH_PLAYLISTS
                ContentKind.ARTIST -> Operation.SEARCH_ARTISTS
                ContentKind.SHOW -> Operation.SEARCH_PODCASTS
                ContentKind.EPISODE -> Operation.SEARCH_EPISODES
                ContentKind.GENRE -> Operation.SEARCH_GENRES
            }
            CatalogJson.search(query(operation, JSONObject().put("searchTerm", searchTerm).put("offset", 0).put("limit", 30)
                .put("includePreReleases", false).put("includeAlbumPreReleases", false).put("numberOfTopResults", 20)
                .put("includeAudiobooks", true).put("includeAuthors", false).put("includeEpisodeContentRatingsV2", true)), kind)
        } }.awaitAll().flatten() }
    }

    suspend fun albums(uris: List<String>): List<MusicContent> = coroutineScope {
        uris.chunked(PARALLEL_REQUESTS).flatMap { batch ->
            batch.map { uri -> async { CatalogJson.content(albumPage(uri, 0, 1), ContentKind.ALBUM) } }.awaitAll()
        }
    }

    suspend fun tracks(uris: List<String>): List<MusicContent> = coroutineScope {
        val unique = uris.distinct()
        val metadata = unique.chunked(TRACK_BATCH_SIZE).chunked(PARALLEL_REQUESTS).flatMap { batches ->
            batches.map { batch -> async {
                CatalogJson.tracks(query(Operation.TRACKS, JSONObject().put("uris", JSONArray(batch))))
            } }.awaitAll().flatten()
        }.associateBy { it.uri }
        uris.map { uri -> metadata[uri] ?: error("Catalog response omitted a requested track") }
    }

    suspend fun detail(content: MusicContent): ContentDetail {
        if (content.kind == ContentKind.ARTIST) return artistDetail(content.uri)
        if (content.kind == ContentKind.SHOW) return showDetail(content.uri)
        if (content.kind == ContentKind.GENRE) return genreDetail(content)
        if (content.kind == ContentKind.EPISODE) {
            val episode = query(Operation.EPISODE, JSONObject().put("uri", content.uri).put("includeEpisodeContentRatingsV2", true))
                .getJSONObject("episodeUnionV2")
            return ContentDetail(CatalogJson.content(episode, ContentKind.EPISODE))
        }
        if (content.kind == ContentKind.TRACK) {
            val track = query(Operation.TRACK, JSONObject().put("uri", content.uri)).getJSONObject("trackUnion")
            return ContentDetail(CatalogJson.content(track, ContentKind.TRACK))
        }
        require(content.kind == ContentKind.ALBUM)
        var offset = 0
        var first: JSONObject? = null
        var album: MusicContent? = null
        val tracks = mutableListOf<MusicContent>()
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

    suspend fun radio(track: MusicContent): MusicContent {
        require(track.kind == ContentKind.TRACK && track.uri.matches(TRACK_URI)) { "Radio requires a track" }
        if (!session.usesBrowserAuthorization()) throw BrowserAuthorizationRequiredException()
        val uri = URI("$RADIO_ENDPOINT/${track.uri}?response-format=json")
        var response = execute(null, null, session.accessToken(), uri)
        if (response.first == 401) response = execute(null, null, session.accessToken(forceRefresh = true), uri)
        if (response.first !in 200..299) throw ServiceApiException(response.first, "Radio request failed")
        val items = JSONObject(response.second).getJSONArray("mediaItems")
        require(items.length() > 0) { "Radio playlist is unavailable" }
        val playlistUri = items.getJSONObject(0).getString("uri")
        require(playlistUri.matches(PLAYLIST_URI)) { "Unexpected radio result" }
        // The resolver supplies the context URI; the repository loads its canonical title and artwork.
        return MusicContent(playlistUri.substringAfterLast(':'), playlistUri, track.title, "", track.imageUrl, ContentKind.PLAYLIST)
    }

    private suspend fun artistDetail(uri: String): ContentDetail = coroutineScope {
        require(uri.matches(ARTIST_URI)) { "Invalid artist URI" }
        val overview = async { artistOverview(uri) }
        val releases = async { artistDiscography(uri) }
        val uris = mutableListOf<String>()
        var offset = 0
        do {
            val artist = query(Operation.ARTIST_TRACKS, JSONObject().put("uri", uri).put("offset", offset).put("limit", 50))
                .getJSONObject("artistUnion")
            require(artist.getString("__typename") == "Artist") { "Artist is unavailable" }
            val page = artist.getJSONObject("discography").getJSONObject("topTracks")
            val items = page.getJSONArray("items").objects()
            uris += items.map { it.getJSONObject("track").getString("uri").also { trackUri ->
                require(trackUri.matches(TRACK_URI)) { "Unexpected artist track" }
            } }
            val paging = page.getJSONObject("pagingInfo")
            val next = if (paging.isNull("nextOffset")) null else paging.getInt("nextOffset")
            require(next == null || (next > offset && items.isNotEmpty())) { "Artist pagination did not advance" }
            offset = next ?: -1
        } while (offset >= 0)
        val artist = overview.await()
        val popularTracks = tracks(uris)
        ContentDetail(CatalogJson.content(artist, ContentKind.ARTIST), popularTracks,
            artistPage = ArtistCatalogJson.overview(artist, releases.await(), popularTracks))
    }

    /** Follow/unfollow uses the same service library mutation as the public artist page. */
    suspend fun setArtistFollowed(uri: String, followed: Boolean): Boolean {
        require(uri.matches(ARTIST_URI)) { "Invalid artist URI" }
        val username = session.username()
        query(if (followed) Operation.ADD_TO_LIBRARY else Operation.REMOVE_FROM_LIBRARY,
            JSONObject().put("libraryItemUris", JSONArray().put(uri)))
        check(session.username() == username) { "Account changed while updating artist follow state" }
        val saved = artistOverview(uri).getBoolean("saved")
        check(session.username() == username) { "Account changed while confirming artist follow state" }
        check(saved == followed) { "Artist follow change was not confirmed" }
        return saved
    }

    internal suspend fun artistOverview(uri: String, locale: String = "intl-ja"): JSONObject {
        require(uri.matches(ARTIST_URI)) { "Invalid artist URI" }
        val artist = query(Operation.ARTIST, JSONObject().put("uri", uri).put("locale", locale)
            .put("preReleaseV2", false)).getJSONObject("artistUnion")
        require(artist.getString("__typename") == "Artist" && artist.getString("uri") == uri) { "Unexpected artist overview" }
        return artist
    }

    private suspend fun artistDiscography(uri: String): List<ArtistRelease> {
        val releases = mutableListOf<ArtistRelease>()
        var offset = 0
        do {
            val artist = query(Operation.ARTIST_DISCOGRAPHY, JSONObject().put("uri", uri).put("offset", offset)
                .put("limit", ALBUM_PAGE_SIZE).put("order", "DATE_DESC")).getJSONObject("artistUnion")
            require(artist.getString("__typename") == "Artist") { "Artist discography is unavailable" }
            val page = artist.getJSONObject("discography").getJSONObject("all")
            val count = page.getJSONArray("items").length()
            val total = page.getInt("totalCount")
            require(total >= 0 && (count > 0 || offset >= total)) { "Artist discography pagination did not advance" }
            releases += ArtistCatalogJson.releaseGroups(page)
            offset += count
        } while (offset < total)
        return releases.distinctBy { it.content.uri }
    }

    private suspend fun showDetail(uri: String): ContentDetail = coroutineScope {
        val metadata = async {
            CatalogJson.content(query(Operation.SHOW, JSONObject().put("uri", uri).put("includeContentCapabilityTrait", false)
                .put("includeEpisodeContentRatingsV2", true)).getJSONObject("podcastUnionV2"), ContentKind.SHOW)
        }
        val episodes = mutableListOf<MusicContent>()
        var offset = 0
        do {
            val show = query(Operation.SHOW_EPISODES, JSONObject().put("uri", uri).put("offset", offset).put("limit", 100)
                .put("includeEpisodeContentRatingsV2", true)).getJSONObject("podcastUnionV2")
            require(show.getString("__typename") == "Podcast") { "Show is unavailable" }
            val page = show.getJSONObject("episodesV2")
            require(page.getString("__typename") == "ContextEpisodePage") { "Episodes are unavailable" }
            episodes += CatalogJson.episodes(page)
            offset = nextOffset(page, offset)
        } while (offset >= 0)
        ContentDetail(metadata.await().also { require(it.uri == uri) { "Unexpected show result" } }, relatedContent = episodes)
    }

    private suspend fun genreDetail(content: MusicContent): ContentDetail {
        require(content.uri.startsWith("spotify:genre:") || content.uri.startsWith("spotify:page:")) { "Invalid genre URI" }
        // The public route resolves opaque 22-character genre IDs to page URIs and keeps named genres as genres.
        val uri = if (content.id.matches(Regex("[0-9A-Za-z_-]{22}"))) "spotify:page:${content.id}" else content.uri
        var offset = 0
        var header = content
        val results = mutableListOf<MusicContent>()
        do {
            val page = query(Operation.BROWSE, browseVariables(uri)
                .put("pagePagination", pagination(offset, 10)).put("sectionPagination", pagination(0, 20))).getJSONObject("browse")
            require(page.getString("__typename") == "BrowseSectionContainer") { "Genre is unavailable" }
            if (offset == 0) header = CatalogJson.browseHeader(page, content)
            val sections = page.getJSONObject("sections")
            for (section in sections.getJSONArray("items").objects()) {
                var items = section.getJSONObject("sectionItems")
                results += CatalogJson.browseItems(items)
                // browsePage embeds a preview with totalCount but no pagingInfo. The full section has its own query.
                var itemOffset = if (items.has("pagingInfo")) nextOffset(items, 0, zeroMeansEnd = true) else {
                    val total = items.getInt("totalCount")
                    require(total >= items.getJSONArray("items").length()) { "Genre section count is invalid" }
                    if (total == items.getJSONArray("items").length()) -1 else 0
                }
                while (itemOffset >= 0) {
                    val nextSection = query(Operation.BROWSE_SECTION, browseVariables(section.getString("uri"))
                        .put("pagination", pagination(itemOffset, 50))).getJSONObject("browseSection")
                    require(nextSection.getString("__typename") == "BrowseSection") { "Genre section is unavailable" }
                    items = nextSection.getJSONObject("sectionItems")
                    results += CatalogJson.browseItems(items)
                    itemOffset = nextOffset(items, itemOffset, zeroMeansEnd = true)
                }
            }
            offset = nextOffset(sections, offset)
        } while (offset >= 0)
        return ContentDetail(header, relatedContent = results.distinctBy { it.uri })
    }

    private fun browseVariables(uri: String): JSONObject = JSONObject().put("uri", uri)
        .put("browseEndUserIntegration", "INTEGRATION_WEB_PLAYER").put("includeEpisodeContentRatingsV2", true)

    private fun pagination(offset: Int, limit: Int): JSONObject = JSONObject().put("offset", offset).put("limit", limit)

    private fun nextOffset(page: JSONObject, offset: Int, zeroMeansEnd: Boolean = false): Int {
        val paging = page.getJSONObject("pagingInfo")
        if (paging.isNull("nextOffset")) return -1
        val next = paging.getInt("nextOffset")
        if (zeroMeansEnd && next == 0) return -1
        require(next > offset && page.getJSONArray("items").length() > 0) { "Catalog pagination did not advance" }
        return next
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
        if (response.first !in 200..299) throw ServiceApiException(response.first, "Catalog request failed")
        val json = JSONObject(response.second)
        if (json.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw ServiceApiException(response.first, "Catalog returned an incomplete result")
        }
        return json.getJSONObject("data")
    }

    private suspend fun execute(operation: Operation?, body: JSONObject?, token: String, uri: URI = URI(ENDPOINT)): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = openConnection(uri)
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            DesktopClientProfile.headers.forEach(connection::setRequestProperty)
            if (operation == Operation.ALBUM) connection.setRequestProperty("Spotify-App-Version", DesktopClientProfile.ALBUM_QUERY_VERSION)
            if (operation == Operation.ARTIST) connection.setRequestProperty("Spotify-App-Version", "896000000")
            connection.setRequestProperty("Accept-Language", "ja")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
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
        SEARCH_ARTISTS("searchArtists", "270905851ba5c7faca81cfe053c2dbd8ceb4f156a0e0ef4b385af75ab69ffd13"),
        SEARCH_PODCASTS("searchPodcasts", "0195d9f61b43606d490bca64c3456e3593528cea6cc05c7e822c7c42beed0f4e"),
        SEARCH_EPISODES("searchEpisodes", "c9ee277c533bd3f191f9f09bee04f8e4e81bdc48f4d2fadbf67e504c75dc3fe1"),
        SEARCH_GENRES("searchGenres", "9e1c0e056c46239dd1956ea915b988913c87c04ce3dadccdb537774490266f46"),
        ARTIST("queryArtistOverview", "7bdc7185c219898c7a2b659cfff2f8ce066dd2d9a97f8b7c4bde92ccfec28310"),
        ARTIST_TRACKS("getArtistNameAndTracks", "0adaf1a1a8a94c7ed095639c4d9456d2b1cfac16ac511d5dd2b01b6dd89f748a"),
        ARTIST_DISCOGRAPHY("queryArtistDiscographyAll", "5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599"),
        ADD_TO_LIBRARY("addToLibrary", "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"),
        REMOVE_FROM_LIBRARY("removeFromLibrary", "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"),
        SHOW("queryShowMetadataV2", "40202837452991ffa80ced96987bc1a937e21d5a89df5bf1fb743110e4d6e93a"),
        SHOW_EPISODES("queryPodcastEpisodes", "06046f9b939d56c8eb7cdbb687da938de1164c006871aec91dc26e4dc7d8eb08"),
        EPISODE("getEpisodeOrChapter", "3416929067571ac4b79db16716be3c6ea5f6265f7975a0ee94b1fc5ee1dc1e9d"),
        BROWSE("browsePage", "f5c4e6d668f5716464a231c1cc8b22c1cbf6ad68b09929fd7de813a30581298b"),
        BROWSE_SECTION("browseSection", "b13c1cccbfcb6947753c2613411b3566485c21fd5f36d80a80bb64be61ba2d51"),
    }

    private companion object {
        const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val RADIO_ENDPOINT = "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist"
        val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}")
        val ARTIST_URI = Regex("spotify:artist:[A-Za-z0-9]{22}")
        val PLAYLIST_URI = Regex("spotify:playlist:[A-Za-z0-9]{22}")
        const val TRACK_BATCH_SIZE = 50
        const val ALBUM_PAGE_SIZE = 50
        const val PARALLEL_REQUESTS = 6
    }
}
