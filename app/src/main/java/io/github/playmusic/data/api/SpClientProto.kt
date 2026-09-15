package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.auth.ProtoParseException
import java.util.UUID

/**
 * spclient (binprot/protobuf) parsers built from real traffic capture.
 *
 * Captured wire contracts:
 * - GET  /playlist/v2/user/{username}/rootlist?decorate=...&from=0&length=120
 *   Response: field 1 = revision bytes, field 5 = items (field 1 = position, field 2 = truncated, field 3 = item),
 *   item field 1 = URI, item field 2 = timestamp/attributes.
 * - GET  /playlist/v2/playlist/{id}
 *   Response: field 3 = header (field 1 = name, field 13 = images {field 1 = key, field 2 = url}),
 *   field 5 = items (field 3 = item, item field 1 = URI), field 15 = timestamp.
 * - POST /collection/v2/paging  body: {field 1 = username, field 2 = kind, field 4 = limit}
 *   Response: field 1 repeated = item {field 1 = URI, field 2 = added_at seconds, field 3 = removed},
 *   field 2 = next page token, field 3 = sync token.
 * - POST /extended-metadata/v0/extended-metadata
 *   Response: field 2 repeated = EntityExtensionDataArray.field 3 repeated =
 *   EntityExtensionData {field 2 = uri, field 3 = Any {field 1 = type_url, field 2 = value}}.
 *   spotify.metadata.Album: field 2 = name, field 3 = artist {field 2 = name},
 *   field 17 = ImageGroup {field 1 repeated = Image {field 1 = file id, field 2 = size, field 3 = width, field 4 = height}},
 *   spotify.metadata.Track: field 2 = name, field 3 = album (including its cover group),
 *   field 4 = artist {field 2 = name}, field 7 = sint32 duration ms, field 36 = canonical uri.
 * - GET  /searchview/v3/search
 *   Response: field 1 repeated = Entity {field 1 = uri, field 2 = name, field 3 = image uri,
 *   field 5 = track {field 3 = album ref, field 4 = artist refs}, field 6 = album, field 7 = playlist}.
 */
object SpClientProto {

    data class PlaylistItem(val uri: String, val timestampMs: Long? = null, val metadata: PlaylistMetadata? = null,
        val formatAttributes: Map<String, String> = emptyMap())

    data class PlaylistAttributes(
        val name: String?,
        val images: Map<String, String>,
        val deletedByOwner: Boolean = false,
        val description: String = "",
        val formatAttributes: Map<String, String> = emptyMap(),
    )

    data class PlaylistCapabilities(
        val canEditMetadata: Boolean? = null,
        val canEditItems: Boolean? = null,
        val canEditName: Boolean? = null,
        val canEditDescription: Boolean? = null,
        val canEditPicture: Boolean? = null,
        val canDelete: Boolean? = null,
    )

    data class PlaylistMetadata(
        val attributes: PlaylistAttributes?,
        val status: Int?,
        val ownerUsername: String? = null,
        val length: Int? = null,
    )

    data class PlaylistDetail(
        val name: String? = null,
        val images: Map<String, String> = emptyMap(),
        val items: List<PlaylistItem> = emptyList(),
        val revision: ByteArray? = null,
        val totalLength: Int? = null,
        val offset: Int = 0,
        val truncated: Boolean = false,
        val hasAttributes: Boolean = false,
        val deletedByOwner: Boolean = false,
        val description: String = "",
        val ownerUsername: String? = null,
        val capabilities: PlaylistCapabilities = PlaylistCapabilities(),
        val formatAttributes: Map<String, String> = emptyMap(),
    )

    data class Rootlist(
        val items: List<PlaylistItem> = emptyList(),
        val revision: ByteArray? = null,
        val totalLength: Int? = null,
        val offset: Int = 0,
        val truncated: Boolean = false,
    )

    data class CollectionItem(val uri: String, val addedAtSeconds: Long, val removed: Boolean)

    data class CollectionPage(
        val items: List<CollectionItem> = emptyList(),
        val nextPageToken: String? = null,
        val syncToken: String? = null,
    )

    data class EntityMetadata(
        val uri: String,
        val name: String? = null,
        val artists: List<String> = emptyList(),
        val imageUrl: String? = null,
        val durationMs: Long? = null,
        val status: Int? = null,
    )

    fun parseRootlist(bytes: ByteArray): Rootlist {
        val list = parsePlaylist(bytes)
        return Rootlist(list.items, list.revision, list.totalLength, list.offset, list.truncated)
    }

    fun parsePlaylist(bytes: ByteArray): PlaylistDetail {
        val reader = ProtoWire.Reader(bytes)
        var header: ByteArray? = null
        var contents = ItemPage()
        var revision: ByteArray? = null
        var totalLength: Int? = null
        var ownerUsername: String? = null
        var capabilities = PlaylistCapabilities()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> revision = reader.readBytes()
                2 -> totalLength = readCount(reader)
                3 -> header = reader.readBytes()
                5 -> contents = parseItems(reader.readBytes())
                16 -> ownerUsername = reader.readString()
                18 -> capabilities = parseCapabilities(reader.readBytes())
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val parsedHeader = header?.let(::parseHeader)
        return PlaylistDetail(
            name = parsedHeader?.name,
            images = parsedHeader?.images ?: emptyMap(),
            items = contents.items,
            revision = revision,
            totalLength = totalLength,
            offset = contents.offset,
            truncated = contents.truncated,
            hasAttributes = parsedHeader != null,
            deletedByOwner = parsedHeader?.deletedByOwner ?: false,
            description = parsedHeader?.description.orEmpty(),
            ownerUsername = ownerUsername,
            capabilities = capabilities,
            formatAttributes = parsedHeader?.formatAttributes.orEmpty(),
        )
    }

    fun parseCollectionPage(bytes: ByteArray): CollectionPage {
        val reader = ProtoWire.Reader(bytes)
        val items = mutableListOf<CollectionItem>()
        var nextPageToken: String? = null
        var syncToken: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> items += parseCollectionItem(reader.readBytes())
                2 -> nextPageToken = reader.readString().takeIf(String::isNotBlank)
                3 -> syncToken = reader.readString().takeIf(String::isNotBlank)
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return CollectionPage(items, nextPageToken, syncToken)
    }

    private fun parseCollectionItem(bytes: ByteArray): CollectionItem {
        val reader = ProtoWire.Reader(bytes)
        var uri = ""
        var addedAtSeconds = 0L
        var removed = false
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> uri = reader.readString()
                2 -> addedAtSeconds = reader.readVarint()
                3 -> removed = reader.readVarint() != 0L
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return CollectionItem(uri, addedAtSeconds, removed)
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

    private data class ItemPage(
        val items: List<PlaylistItem> = emptyList(),
        val offset: Int = 0,
        val truncated: Boolean = false,
    )

    private fun parseItems(bytes: ByteArray): ItemPage {
        val reader = ProtoWire.Reader(bytes)
        val items = mutableListOf<PlaylistItem>()
        val metadata = mutableListOf<PlaylistMetadata>()
        var offset = 0
        var truncated = false
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> items += parseItem(reader.readBytes())
                4 -> metadata += parsePlaylistMetadata(reader.readBytes())
                1 -> offset = reader.readVarint().toInt()
                2 -> truncated = reader.readVarint() != 0L
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return ItemPage(items.mapIndexed { index, item -> item.copy(metadata = metadata.getOrNull(index)) }, offset, truncated)
    }

    private fun parsePlaylistMetadata(bytes: ByteArray): PlaylistMetadata {
        val reader = ProtoWire.Reader(bytes)
        var attributes: PlaylistAttributes? = null
        var status: Int? = null
        var ownerUsername: String? = null
        var length: Int? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> attributes = parseHeader(reader.readBytes())
                3 -> length = readCount(reader)
                5 -> ownerUsername = reader.readString().takeIf(String::isNotBlank)
                9 -> {
                    val encoded = reader.readVarint()
                    status = ((encoded ushr 1) xor -(encoded and 1)).toInt()
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return PlaylistMetadata(attributes, status, ownerUsername, length)
    }

    private fun readCount(reader: ProtoWire.Reader): Int {
        val value = reader.readVarint()
        if (value !in 0..Int.MAX_VALUE.toLong()) throw ProtoParseException("Playlist length is invalid")
        return value.toInt()
    }

    private fun parseItem(bytes: ByteArray): PlaylistItem {
        val reader = ProtoWire.Reader(bytes)
        var uri: String? = null
        var timestamp: Long? = null
        val attributes = mutableMapOf<String, String>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> uri = reader.readString()
                2 -> {
                    val nested = reader.readBytes()
                    val parsed = parseItemAttributes(nested)
                    timestamp = parsed.first ?: timestamp
                    attributes += parsed.second
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val itemUri = uri ?: throw ProtoParseException("Playlist item is missing a URI")
        return PlaylistItem(itemUri, timestamp, formatAttributes = attributes)
    }

    private fun parseItemAttributes(bytes: ByteArray): Pair<Long?, Map<String, String>> {
        val reader = ProtoWire.Reader(bytes)
        var timestamp: Long? = null
        val attributes = mutableMapOf<String, String>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> timestamp = reader.readVarint()
                11 -> parseFormatAttribute(reader.readBytes())?.let { attributes[it.first] = it.second }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return timestamp to attributes
    }

    private fun parseFormatAttribute(bytes: ByteArray): Pair<String, String>? {
        val reader = ProtoWire.Reader(bytes)
        var key: String? = null
        var value: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> key = reader.readString()
                2 -> value = reader.readString()
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return key?.takeIf { it.isNotBlank() }?.let { name -> value?.let { name to it } }
    }

    private fun parseHeader(bytes: ByteArray): PlaylistAttributes {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        var description = ""
        val images = mutableMapOf<String, String>()
        var picture: ByteArray? = null
        var deletedByOwner = false
        val attributes = mutableMapOf<String, String>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> name = reader.readString()
                2 -> description = reader.readString()
                3 -> picture = reader.readBytes()
                6 -> deletedByOwner = reader.readVarint() != 0L
                12 -> parseFormatAttribute(reader.readBytes())?.let { attributes[it.first] = it.second }
                13 -> parseImage(reader.readBytes())?.let { images[it.first] = it.second }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        // Newly uploaded covers may contain only the image ID, without decorated picture sizes.
        if (images.isEmpty()) picture?.takeIf { it.isNotEmpty() }?.let {
            images["default"] = "https://i.scdn.co/image/${it.toHex()}"
        }
        return PlaylistAttributes(name, images, deletedByOwner, description, attributes)
    }

    private fun parseCapabilities(bytes: ByteArray): PlaylistCapabilities {
        val reader = ProtoWire.Reader(bytes)
        var result = PlaylistCapabilities()
        var directCanDelete: Boolean? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            result = when (reader.fieldNumber(tag)) {
                4 -> result.copy(canEditMetadata = reader.readVarint() != 0L)
                5 -> result.copy(canEditItems = reader.readVarint() != 0L)
                8 -> {
                    val attributes = ProtoWire.Reader(reader.readBytes())
                    var next = result
                    while (attributes.hasNext()) {
                        val attributeTag = attributes.readTag()
                        val field = attributes.fieldNumber(attributeTag)
                        if (field in 1..3 || field == 6) {
                            val capability = ProtoWire.Reader(attributes.readBytes())
                            var canEdit: Boolean? = null
                            while (capability.hasNext()) {
                                val capabilityTag = capability.readTag()
                                if (capability.fieldNumber(capabilityTag) == 1) canEdit = capability.readVarint() != 0L
                                else capability.skip(capability.wireType(capabilityTag))
                            }
                            next = when (field) {
                                1 -> next.copy(canEditName = canEdit)
                                2 -> next.copy(canEditDescription = canEdit)
                                3 -> next.copy(canEditPicture = canEdit)
                                else -> next.copy(canDelete = canEdit)
                            }
                        } else attributes.skip(attributes.wireType(attributeTag))
                    }
                    next
                }
                // Current provider web client also exposes a direct can_delete capability.
                13 -> { directCanDelete = reader.readVarint() != 0L; result }
                else -> { reader.skip(reader.wireType(tag)); result }
            }
        }
        return result.copy(canDelete = directCanDelete ?: result.canDelete)
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
                1 -> {
                    val status = parseStatus(reader.readBytes())
                    if (status != null && status != 0 && status !in 200..299) {
                        throw SpotifyApiException(status, "Metadata provider request failed")
                    }
                }
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
        var status: Int? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> status = parseStatus(reader.readBytes())
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
        return metadata.copy(uri = resolvedUri, status = status)
    }

    private fun parseStatus(bytes: ByteArray): Int? {
        val reader = ProtoWire.Reader(bytes)
        var status: Int? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.fieldNumber(tag) == 1) status = reader.readVarint().toInt()
            else reader.skip(reader.wireType(tag))
        }
        return status
    }

    private fun parseAlbum(bytes: ByteArray): EntityMetadata {
        val reader = ProtoWire.Reader(bytes)
        var name: String? = null
        val artists = mutableListOf<String>()
        var imageUrl: String? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                2 -> name = reader.readString()
                3 -> parseArtistName(reader.readBytes())?.let { artists += it }
                17 -> imageUrl = parseImageGroup(reader.readBytes()) ?: imageUrl
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return EntityMetadata(
            uri = "",
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
                3 -> imageUrl = parseAlbum(reader.readBytes()).imageUrl ?: imageUrl
                4 -> parseArtistName(reader.readBytes())?.let { artists += it }
                7 -> {
                    val encoded = reader.readVarint()
                    durationMs = (encoded ushr 1) xor -(encoded and 1)
                }
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
     * Returns an image URL for the ImageGroup, preferring the default size (0).
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
                    var size = 0
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
        var subType = 0
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> uri = reader.readString()
                2 -> name = reader.readString()
                3 -> imageUri = reader.readString()
                5, 6, 7 -> {
                    subType = reader.fieldNumber(tag)
                    sub = reader.readBytes()
                }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        val entityUri = uri?.takeIf { it.isNotBlank() } ?: return null
        val artists = when (subType) {
            5 -> sub?.let(::parseSearchTrackArtists).orEmpty()
            6 -> sub?.let(::parseSearchAlbumArtists).orEmpty()
            else -> emptyList()
        }
        return EntityMetadata(
            uri = entityUri,
            name = name,
            artists = artists,
            imageUrl = normalizeImageUri(imageUri),
        )
    }

    private fun parseSearchTrackArtists(bytes: ByteArray): List<String> {
        val reader = ProtoWire.Reader(bytes)
        var albumName: String? = null
        val artistNames = mutableListOf<String>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                3 -> albumName = parseRefName(reader.readBytes()) ?: albumName
                4 -> parseRefName(reader.readBytes())?.let { artistNames += it }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return artistNames.distinct().ifEmpty { listOfNotNull(albumName) }
    }

    private fun parseSearchAlbumArtists(bytes: ByteArray): List<String> {
        val reader = ProtoWire.Reader(bytes)
        val names = mutableListOf<String>()
        val refs = mutableListOf<String>()
        while (reader.hasNext()) {
            val tag = reader.readTag()
            when (reader.fieldNumber(tag)) {
                1 -> reader.readString().takeIf(String::isNotBlank)?.let { names += it }
                7 -> parseRefName(reader.readBytes())?.let { refs += it }
                else -> reader.skip(reader.wireType(tag))
            }
        }
        return refs.distinct().ifEmpty { names.distinct() }
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
     * EntityRequest.query is an ExtensionQuery message, not a random mask.
     * Country/catalogue come from authenticated account attributes when provided.
     * Contract: librespot protocol/proto/extended_metadata.proto and extension_kind.proto.
     */
    fun buildExtendedMetadataRequest(uris: List<String>, context: AccountContext? = null): ByteArray {
        val entities = uris.map { uri ->
            val extensionKind = when {
                uri.startsWith("spotify:album:") -> 9 // ALBUM_V4
                uri.startsWith("spotify:track:") -> 10 // TRACK_V4
                else -> throw IllegalArgumentException("Unsupported metadata entity type")
            }
            ProtoWire.fieldBytes(
                2,
                ProtoWire.fieldString(1, uri) +
                    ProtoWire.fieldBytes(2, ProtoWire.fieldVarint(1, extensionKind)),
            )
        }
        val out = java.io.ByteArrayOutputStream()
        if (context != null) {
            val task = UUID.randomUUID()
            val taskId = java.nio.ByteBuffer.allocate(16).putLong(task.mostSignificantBits).putLong(task.leastSignificantBits).array()
            out.write(ProtoWire.fieldBytes(1,
                ProtoWire.fieldString(1, context.country) + ProtoWire.fieldString(2, context.catalogue) + ProtoWire.fieldBytes(3, taskId)))
        }
        entities.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun buildCollectionPageRequest(username: String, set: String, limit: Int, pageToken: String? = null): ByteArray {
        require(limit > 0)
        return ProtoWire.fieldString(1, username) + ProtoWire.fieldString(2, set) +
            (pageToken?.let { ProtoWire.fieldString(3, it) } ?: ByteArray(0)) +
            ProtoWire.fieldVarint(4, limit)
    }

    fun newSearchRequestId(): String = UUID.randomUUID().toString()
}




