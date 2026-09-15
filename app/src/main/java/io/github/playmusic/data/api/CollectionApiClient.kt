package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire

/** Native collection writes; field numbers follow collection2v2.proto WriteRequest/CollectionItem. */
internal class CollectionApiClient(private val api: SpotifyApiClient) {
    suspend fun setSaved(username: String, uri: String, saved: Boolean) {
        require(uri.matches(SAVED_URI)) { "Invalid saved item identifier" }
        val item = ProtoWire.fieldString(1, uri) +
            if (saved) byteArrayOf() else ProtoWire.fieldVarint(3, 1)
        val body = ProtoWire.fieldString(1, username) + ProtoWire.fieldString(2, "collection") +
            ProtoWire.fieldBytes(3, item)
        api.postProto("/collection/v2/write", body, "application/vnd.collection-v2.spotify.proto")
    }

    companion object {
        private val SAVED_URI = Regex("spotify:(track|album):[A-Za-z0-9]{22}")
    }
}
