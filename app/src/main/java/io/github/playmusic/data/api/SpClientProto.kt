package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import java.util.UUID

/**
 * spclient (binprot/protobuf) parsers built from real traffic capture.
 *
 * Captured wire contracts:
 * - GET  /playlist/v2/user/{username}/rootlist?decorate=...&from=0&length=120
 *   Response: field 5 = items (field 1 = start, field 2 = end, field 3 = item),
 *   item field 1 = URI, item field 2 = timestamp/attributes.
 * - GET  /playlist/v2/playlist/{id}
 *   Response: field 3 = header (field 1 = name, field 13 = images {field 1 = key, field 2 = url}),
 *   field 5 = items (field 3 = item, item field 1 = URI), field 15 = revision.
 * - POST /collection/v2/paging  body: {field 1 = username, field 2 = kind, field 4 = limit}
 *   Response: field 1 repeated = item {field 1 = URI, field 2 = timestamp}, field 2 = change token.
 * - POST /extended-metadata/v0/extended-metadata
 *   Response: field 2 repeated = EntityExtensionDataArray.field 3 repeated =
 *   EntityExtensionData {field 2 = uri, field 3 = Any {field 1 = type_url, field 2 = value}}.
 *   spotify.metadata.Album: field 2 = name, field 3 = artist {field 2 = name},
 *   field 17 = ImageGroup {field 1 repeated = Image {field 1 = file id, field 2 = size, field 3 = width, field 4 = height}},
 *   field 35 = uri, field 36 = artist.
 *   spotify.metadata.Track: field 2 = name, field 4 = artist {field 2 = name}, field 7 = duration ms,
 *   field 17 = ImageGroup, field 36 = canonical uri.
 * - GET  /searchview/v3/search
 *   Response: field 1 repeated = Entity {field 1 = uri, field 2 = name, field 3 = image uri,
 *   field 5 = track {field 3 = album ref, field 4 = artist refs}, field 6 = album, field 7 = playlist}.
 */
object SpClientProto {

    data class PlaylistItem(val uri: String, val timestampMs: Long? = null)

    data class PlaylistDetail(
        val name: String? = null,
        val images: Map<String, String> = emptyMap(),
        val isPlayable: Boolean = true,
        val items: List<PlaylistItem> = emptyList(),
        val revision: Long? = null,
    )

    data class Rootlist(
        val items: List<PlaylistItem> = emptyList(),
        val revision: Long? = null,
    )

    data class CollectionPage(
        val items: List<PlaylistItem> = emptyList(),
        val changeToken: String? = null,
    )

    data class EntityMetadata(
        val uri: String,
        val name: String? = null,
        val artists: List<String> = emptyList(),
        val imageUrl: String? = null,
        val durationMs: Long? = null,
    )

    fun parseRootlist(bytes: ByteArray): Rootlist {
        val reader = ProtoWire.Reader(bytes)
        val items = mutableListOf<PlaylistItem>()
        var revision: Long? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                5 -> items += parseItems(reader.readBytes())
                1 -> reader.readVarint() // format header
                2 -> reader.readVarint() // length
                15 -> revision = reader.readVarint()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return Rootlist(items, revision)
    }

    fun parsePlaylist(bytes: ByteArray): PlaylistDetail {
        val reader = ProtoWire.Reader(bytes)
        var header: ByteArray? = null
        val items = mutableListOf<PlaylistItem>()
        var revision: Long? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> header = reader.readBytes()
                5 -> items += parseItems(reader.readBytes())
                15 -> revision = reader.readVarint()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val parsedHeader = header?.let(::parseHeader)
        return PlaylistDetail(
            name = parsedHeader?.name,
            images = parsedHeader?.images ?: emptyMap(),
            isPlayable = parsedHeader?.isPlayable ?: true,
            items = items,
            revision = revision,
        )
    }

    fun parseCollectionPage(bytes: ByteArray): CollectionPage {
        val reader = ProtoWire.Reader(bytes)
        val items = mutableListOf<PlaylistItem>()
        var changeToken: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> parseItem(reader.readBytes())?.let { items += it }
                2 -> changeToken = reader.readBytes().toString(Charsets.UTF_8)
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return CollectionPage(items, changeToken)
    }

    /**
     * Parses /extended-metadata/v0/extended-metadata response into entity metadata.
     */
    fun parseExtendedMetadata(bytes: ByteArray): List<EntityMetadata> {
        val reader = ProtoWire.Reader(bytes)
        val result = mutableListOf<EntityMetadata>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> result += parseEntityExtensionDataArray(reader.readBytes())
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return result
    }

    /**
     * Parses /searchview/v3/search response into entity metadata.
     */
    fun parseSearchView(bytes: ByteArray): List<EntityMetadata> {
        val reader = ProtoWire.Reader(bytes)
        val result = mutableListOf<EntityMetadata>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> parseSearchEntity(reader.readBytes())?.let { result += it }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return result
    }

    private fun parseItems(bytes: ByteArray): List<PlaylistItem> {
        val reader = ProtoWire.Reader(bytes)
        val items = mutableListOf<PlaylistItem>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> parseItem(reader.readBytes())?.let { items += it }
                1 -> reader.readVarint() // from
                2 -> reader.readVarint() // to
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return items
    }

    private fun parseItem(bytes: ByteArray): PlaylistItem? {
        val reader = ProtoWire.Reader(bytes)
        var uri: String? = null
        var timestamp: Long? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> uri = reader.readString()
                2 -> {
                    val nested = reader.readBytes()
                    timestamp = parseTimestamp(nested) ?: timestamp
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return uri?.takeIf { it.isNotBlank() }?.let { PlaylistItem(it, timestamp) }
    }

    private fun parseTimestamp(bytes: ByteArray): Long? {
        val reader = ProtoWire.Reader(bytes)
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> return reader.readVarint()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return null
    }

    private data class ParsedHeader(val name: String?, val images: Map<String, String>, val isPlayable: Boolean)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        val images = mutableMapOf<String, String>()
        var isPlayable = true
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> name = reader.readString()
                13 -> parseImage(reader.readBytes())?.let { images[it.first] = it.second }
                16 -> isPlayable = reader.readVarint() != 0L
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return ParsedHeader(name, images, isPlayable)
    }

    private fun parseImage(bytes: ByteArray): Pair<String, String>? {
        val reader = ProtoWire.Reader(bytes)
        var key: String? = null
        var url: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> key = reader.readString()
                2 -> url = reader.readString()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return if (key != null && url != null) key to url else null
    }

    private fun parseEntityExtensionDataArray(bytes: ByteArray): List<EntityMetadata> {
        val reader = ProtoWire.Reader(bytes)
        val result = mutableListOf<EntityMetadata>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> result += parseEntityExtensionData(reader.readBytes())
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return result
    }

    private fun parseEntityExtensionData(bytes: ByteArray): EntityMetadata {
        val reader = ProtoWire.Reader(bytes)
        var uri: String? = null
        var typeUrl: String? = null
        var value: ByteArray? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> uri = reader.readString()
                3 -> {
                    val anyReader = ProtoWire.Reader(reader.readBytes())
                    while (anyReader.hasNext()) {
                        val anyTag = anyReader.readTag()
                        when (anyReader.fieldNumber(anyTag)) {
                            1 -> typeUrl = anyReader.readString()
                            2 -> value = anyReader.readBytes()
                            else -> anyReader.skip(anyReader.wireType(anyTag))
                        }
                    }
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val metadata = when {
            typeUrl?.endsWith(".Album") == true && value != null -> parseAlbum(value)
            typeUrl?.endsWith(".Track") == true && value != null -> parseTrack(value)
            else -> null
        } ?: EntityMetadata(uri = uri.orEmpty())
        val resolvedUri = uri?.takeIf { it.isNotBlank() } ?: metadata.uri
        return metadata.copy(uri = resolvedUri)
    }

    private fun parseAlbum(bytes: ByteArray): EntityMetadata {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        var uri: String? = null
        val artists = mutableListOf<String>()
        var imageUrl: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> name = reader.readString()
                3 -> parseArtistName(reader.readBytes())?.let { artists += it }
                17 -> imageUrl = parseImageGroup(reader.readBytes()) ?: imageUrl
                35 -> uri = reader.readString()
                36 -> parseArtistName(reader.readBytes())?.let { artists += it }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return EntityMetadata(
            uri = uri.orEmpty(),
            name = name,
            artists = artists.distinct(),
            imageUrl = imageUrl,
        )
    }

    private fun parseTrack(bytes: ByteArray): EntityMetadata {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        var uri: String? = null
        var durationMs: Long? = null
        val artists = mutableListOf<String>()
        var imageUrl: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> name = reader.readString()
                4 -> parseArtistName(reader.readBytes())?.let { artists += it }
                7 -> durationMs = reader.readVarint()
                17 -> imageUrl = parseImageGroup(reader.readBytes()) ?: imageUrl
                36 -> uri = reader.readString()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return EntityMetadata(
            uri = uri.orEmpty(),
            name = name,
            artists = artists.distinct(),
            imageUrl = imageUrl,
            durationMs = durationMs,
        )
    }

    private fun parseArtistName(bytes: ByteArray): String? {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> name = reader.readString()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns an image URL for the ImageGroup, preferring the large image (size 0).
     */
    private fun parseImageGroup(bytes: ByteArray): String? {
        val reader = ProtoWire.Reader(bytes)
        var best: String? = null
        var bestSize = Int.MAX_VALUE
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> {
                    val imageReader = ProtoWire.Reader(reader.readBytes())
                    var fileId: ByteArray? = null
                    var size = Int.MAX_VALUE
                    while (imageReader.hasNext()) {
                        val imageTag = imageReader.readTag()
                        when (imageReader.fieldNumber(imageTag)) {
                            1 -> fileId = imageReader.readBytes()
                            2 -> size = imageReader.readVarint().toInt()
                            else -> imageReader.skip(imageReader.wireType(imageTag))
                        }
                    }
                    if (fileId != null && size < bestSize) {
                        bestSize = size
                        best = "https://i.scdn.co/image/${fileId.toHex()}"
                    }
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return best
    }

    /**
     * Parses a single searchview entity (field 1 repeated at top level).
     *
     * Wire layout (from capture #1252):
     *   field 1 = uri
     *   field 2 = name
     *   field 3 = image uri
     *   field 5 / 6 / 7 = sub entity (track / album / playlist) bytes
     *   other fields (e.g. field 8..17) are NOT name-bearing sub-entities and
     *   must be skipped rather than parsed as subtitle bytes.
     */
    private fun parseSearchEntity(bytes: ByteArray): EntityMetadata? {
        val reader = ProtoWire.Reader(bytes)
        var uri: String? = null
        var name: String? = null
        var imageUri: String? = null
        var sub: ByteArray? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> uri = reader.readString()
                2 -> name = reader.readString()
                3 -> imageUri = reader.readString()
                5 -> sub = reader.readBytes()
                6 -> sub = reader.readBytes()
                7 -> sub = reader.readBytes()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val entityUri = uri?.takeIf { it.isNotBlank() } ?: return null
        val subtitle = sub?.let(::parseSearchSubtitle).orEmpty()
        return EntityMetadata(
            uri = entityUri,
            name = name,
            artists = listOfNotNull(subtitle.takeIf { it.isNotBlank() }),
            imageUrl = normalizeImageUri(imageUri),
        )
    }

    private fun parseSearchSubtitle(bytes: ByteArray): String? {
        System.err.println("DBG subtitle bytes len=" + bytes.size)

        val reader = ProtoWire.Reader(bytes)
        var albumName: String? = null
        var artistName: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> albumName = parseRefName(reader.readBytes()) ?: albumName
                4 -> artistName = parseRefName(reader.readBytes()) ?: artistName
                else -> {
                    System.err.println("DBG subtitle skip field=" + reader.fieldNumber(tag) + " wire=" + reader.wireType(tag))
                    reader.skip(reader.wireType(tag))
                }
            }
        }
        return artistName ?: albumName
    }

    private fun parseRefName(bytes: ByteArray): String? {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> name = reader.readString()
                1 -> reader.readString()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }

    fun normalizeImageUri(imageUri: String?): String? {
        if (imageUri.isNullOrBlank()) return null
        return when {
            imageUri.startsWith("https://") -> imageUri
            imageUri.startsWith("spotify:image:") -> "https://i.scdn.co/image/${imageUri.removePrefix("spotify:image:")}"
            else -> null
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Builds an /extended-metadata/v0/extended-metadata request body for the given URIs.
     *
     * The market context field 3 is a 16-byte request-scoped gid that varies per request
     * in the real capture (#302 vs #319 differ), so a random value is used.
     */
    fun buildExtendedMetadataRequest(uris: List<String>, mask: ByteArray): ByteArray {
        val marketContext = ProtoWire.fieldBytes(
            1,
            ProtoWire.fieldString(1, "JP") +
                ProtoWire.fieldString(2, "free") +
                ProtoWire.fieldBytes(3, randomGid()),
        )
        val entities = uris.map { uri ->
            ProtoWire.fieldBytes(
                2,
                ProtoWire.fieldString(1, uri) +
                    ProtoWire.fieldBytes(2, mask),
            )
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(marketContext)
        entities.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun randomGid(): ByteArray {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun newSearchRequestId(): String = UUID.randomUUID().toString()
}




