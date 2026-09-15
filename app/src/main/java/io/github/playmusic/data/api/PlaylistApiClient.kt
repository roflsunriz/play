package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.PlaylistLimits
import io.github.playmusic.data.model.PlaylistMetadata
import io.github.playmusic.data.model.SpotifyContent
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder

/** Mutates only the explicitly selected playlist; playlist deletion is removal from the rootlist. */
class PlaylistApiClient(
    private val api: SpotifyApiClient,
    private val sessionTokens: SessionTokens,
) {
    suspend fun metadata(content: SpotifyContent): PlaylistMetadata =
        metadata(read(content), content.uri, sessionTokens.username())

    internal suspend fun metadata(detail: SpClientProto.PlaylistDetail, uri: String): PlaylistMetadata =
        metadata(detail, uri, sessionTokens.username())

    private fun metadata(detail: SpClientProto.PlaylistDetail, uri: String, username: String): PlaylistMetadata {
        check(detail.hasAttributes) { "Playlist metadata is missing attributes" }
        val owned = detail.ownerUsername == username
        val capabilities = detail.capabilities
        return PlaylistMetadata(
            uri = uri,
            name = detail.name.orEmpty(),
            description = detail.description,
            imageUrl = detail.images["default"] ?: detail.images["xlarge"] ?: detail.images.values.firstOrNull(),
            ownerUsername = detail.ownerUsername,
            canEdit = owned && !detail.deletedByOwner && capabilities.canEditMetadata != false &&
                capabilities.canEditName != false && capabilities.canEditDescription != false,
            canDelete = owned && capabilities.canDelete != false,
            isOwned = owned,
        )
    }

    suspend fun create(name: String, description: String): SpotifyContent {
        PlaylistLimits.validate(name, description)
        val reply = api.postProto("/playlist/v2/playlist", PlaylistMutationProto.create(name, description))
        val uri = PlaylistMutationProto.createdUri(reply.bodyBytes)
        val created = SpotifyContent(uri.substringAfterLast(':'), uri, name, "", null, ContentKind.PLAYLIST)
        try {
            val completed = completeCreation(created)
            val saved = metadata(completed)
            check(saved.name == name && saved.description == description) { "The created playlist metadata did not match the requested values" }
            return completed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keep the created URI: retrying the create endpoint would produce a duplicate.
            throw PlaylistCreationException(created, error)
        }
    }

    /** Resumes a known newly created playlist without creating another remote object. */
    suspend fun completeCreation(content: SpotifyContent): SpotifyContent {
        val current = metadata(content)
        check(current.isOwned) { "Only the playlist owner can finish creation" }
        makePrivate(content)
        if (!rootlistContains(content.uri)) {
            applyRootlist(PlaylistMutationProto.add(listOf(content.uri), System.currentTimeMillis(), privateInRootlist = true))
        }
        check(rootlistContains(content.uri)) { "The created playlist has not appeared in the library" }
        return content.copy(title = current.name, imageUrl = current.imageUrl)
    }

    suspend fun update(
        content: SpotifyContent,
        name: String,
        description: String,
        imageJpeg: ByteArray? = null,
        removeImage: Boolean = false,
    ): SpotifyContent {
        PlaylistLimits.validate(name, description, imageJpeg)
        require(imageJpeg == null || !removeImage) { "Cannot replace and remove the image together" }
        val detail = read(content)
        val current = metadata(detail, content.uri)
        check(current.canEdit) { "This playlist cannot be edited by the current account" }
        if (imageJpeg != null || removeImage) {
            check(detail.capabilities.canEditPicture != false) { "The playlist picture cannot be edited" }
        }
        // Register first, then commit name, description and picture in a single metadata operation.
        val picture = imageJpeg?.let { uploadPicture(content, it) }
        apply(content, PlaylistMutationProto.metadata(name, description, picture, removeImage))
        val updated = metadata(content)
        check(updated.name == name && updated.description == description) { "Playlist metadata did not match the saved changes" }
        return content.copy(title = updated.name, imageUrl = updated.imageUrl)
    }

    suspend fun delete(content: SpotifyContent) {
        check(metadata(content).canDelete) { "This playlist cannot be deleted by the current account" }
        applyRootlist(PlaylistMutationProto.remove(listOf(content.uri)))
        check(!rootlistContains(content.uri)) { "The playlist is still in the library" }
    }

    suspend fun addTracks(content: SpotifyContent, trackUris: List<String>) {
        checkEditableItems(content, trackUris)
        if (trackUris.isNotEmpty()) apply(content, PlaylistMutationProto.add(trackUris, System.currentTimeMillis()))
    }

    suspend fun removeTracks(content: SpotifyContent, trackUris: List<String>) {
        checkEditableItems(content, trackUris)
        if (trackUris.isNotEmpty()) apply(content, PlaylistMutationProto.remove(trackUris))
    }

    /** Reads original entries, including tracks whose catalog metadata is unavailable. */
    suspend fun trackUris(content: SpotifyContent, requireEditable: Boolean = false): List<String> =
        trackSnapshot(content, requireEditable).uris

    internal data class TrackSnapshot(val uris: List<String>, val canEdit: Boolean)

    internal suspend fun trackSnapshot(content: SpotifyContent, requireEditable: Boolean = false): TrackSnapshot {
        val id = playlistId(content)
        val uris = mutableListOf<String>()
        var offset = 0
        var revision: ByteArray? = null
        var canEdit = false
        do {
            val page = SpClientProto.parsePlaylist(api.getProto("/playlist/v2/playlist/$id", mapOf(
                "from" to offset.toString(), "length" to "120",
                "decorate" to "revision,length,attributes,timestamp,owner,capabilities",
            )).bodyBytes)
            check(page.offset == offset) { "Playlist pagination returned a different position" }
            check(page.revision?.isNotEmpty() == true) { "Playlist response is missing its revision" }
            if (offset == 0) {
                revision = page.revision
                canEdit = metadata(page, content.uri).isOwned && !page.deletedByOwner && page.capabilities.canEditItems != false
                if (requireEditable) check(canEdit) {
                    "Playlist tracks cannot be edited by the current account"
                }
            } else check(revision.contentEquals(page.revision)) { "Playlist changed between pages" }
            uris += page.items.map { it.uri }.filter { it.matches(TRACK_URI) }
            if (!page.truncated) {
                check(page.totalLength == null || offset + page.items.size >= page.totalLength) { "Playlist response is incomplete" }
                return TrackSnapshot(uris, canEdit)
            }
            check(page.items.isNotEmpty()) { "Playlist pagination did not advance" }
            offset += page.items.size
            check(page.totalLength == null || offset < page.totalLength) { "Playlist pagination is inconsistent" }
        } while (true)
    }

    private suspend fun checkEditableItems(content: SpotifyContent, uris: List<String>) {
        require(uris.size <= 100 && uris.all { it.matches(TRACK_URI) }) { "Invalid playlist tracks" }
        val detail = read(content)
        check(metadata(detail, content.uri).isOwned && detail.capabilities.canEditItems != false) {
            "Playlist tracks cannot be edited by the current account"
        }
    }

    private suspend fun read(content: SpotifyContent): SpClientProto.PlaylistDetail = SpClientProto.parsePlaylist(
        api.getProto("/playlist/v2/playlist/${playlistId(content)}", mapOf("from" to "0", "length" to "0",
            "decorate" to "revision,length,attributes,timestamp,owner,capabilities")).bodyBytes,
    )

    private suspend fun apply(content: SpotifyContent, operation: ByteArray) {
        api.postProto("/playlist/v2/playlist/${playlistId(content)}/changes", PlaylistMutationProto.changes(listOf(operation)))
    }

    private suspend fun makePrivate(content: SpotifyContent) {
        val path = "/playlist-permission/v1/playlist/${playlistId(content)}/permission/base"
        val permission = JSONObject(api.get(path).body)
        if (permission.optString("permissionLevel") != "BLOCKED") {
            permission.put("permissionLevel", "BLOCKED")
            api.post(path, body = permission)
        }
        check(JSONObject(api.get(path).body).optString("permissionLevel") == "BLOCKED") {
            "The new playlist could not be made private"
        }
    }

    private suspend fun uploadPicture(content: SpotifyContent, bytes: ByteArray): ByteArray {
        val response = api.postBytes("/v4/playlist", bytes, "image/jpeg", "https://image-upload.spotify.com")
        val token = JSONObject(response.body).optString("uploadToken")
        check(token.isNotBlank()) { "Playlist image upload returned no registration token" }
        val registered = api.postProto("/playlist/v2/playlist/${playlistId(content)}/register-image", ProtoWire.fieldString(1, token))
        return PlaylistMutationProto.registeredPicture(registered.bodyBytes)
    }

    private suspend fun applyRootlist(operation: ByteArray) {
        api.postProto("${rootlistPath()}/changes", PlaylistMutationProto.changes(listOf(operation)))
    }

    private suspend fun rootlistContains(uri: String): Boolean {
        val path = rootlistPath()
        var offset = 0
        do {
            val page = SpClientProto.parseRootlist(api.getProto(path, mapOf("from" to offset.toString(), "length" to "120")).bodyBytes)
            check(page.offset == offset) { "Playlist library pagination returned a different position" }
            if (page.items.any { it.uri == uri }) return true
            if (!page.truncated) return false
            check(page.items.isNotEmpty()) { "Playlist library pagination did not advance" }
            offset += page.items.size
        } while (true)
    }

    private suspend fun rootlistPath(): String {
        val username = URLEncoder.encode(sessionTokens.username(), Charsets.UTF_8.name()).replace("+", "%20")
        return "/playlist/v2/user/$username/rootlist"
    }

    private fun playlistId(content: SpotifyContent): String {
        require(content.kind == ContentKind.PLAYLIST && content.id.matches(PLAYLIST_ID) &&
            content.uri == "spotify:playlist:${content.id}") { "Invalid playlist identifier" }
        return content.id
    }

    private companion object {
        val PLAYLIST_ID = Regex("[A-Za-z0-9]{22}")
        val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}")
    }
}

class PlaylistCreationException(val createdContent: SpotifyContent, cause: Exception) :
    Exception("The playlist was created, but its library setup needs to be retried", cause)
