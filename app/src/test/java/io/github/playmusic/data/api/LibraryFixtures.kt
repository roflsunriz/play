package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire.fieldBytes
import io.github.playmusic.data.auth.ProtoWire.fieldString
import io.github.playmusic.data.auth.ProtoWire.fieldVarint

/** Synthetic fixtures from the field contracts documented in docs/api-contracts.md. */
internal object LibraryFixtures {
    fun accountContext(country: String = "DE", catalogue: String = "premium"): ByteArray =
        fieldBytes(1, fieldBytes(3,
            fieldBytes(1, fieldString(1, "country_code") + fieldBytes(2, fieldString(4, country))) +
                fieldBytes(1, fieldString(1, "catalogue") + fieldBytes(2, fieldString(4, catalogue)))))

    fun decoratedRootlist(): ByteArray {
        val contents = fieldVarint(1, 0) + fieldVarint(2, 0) +
            fieldBytes(3, fieldString(1, "spotify:playlist:named")) +
            fieldBytes(3, fieldString(1, "spotify:playlist:untitled")) +
            fieldBytes(4, fieldBytes(2, fieldString(1, "Named playlist")) + fieldVarint(9, 400)) +
            fieldBytes(4, fieldBytes(2, ByteArray(0)) + fieldVarint(9, 400))
        return fieldBytes(1, byteArrayOf(1, 2, 3)) + fieldVarint(2, 2) + fieldBytes(5, contents)
    }

    fun rootlist(uris: List<String>, offset: Int = 0, truncated: Boolean = false): ByteArray =
        fieldBytes(1, byteArrayOf(0x80.toByte(), 0, 0xFF.toByte(), 2)) +
            fieldBytes(5, fieldVarint(1, offset) + fieldVarint(2, if (truncated) 1 else 0) +
                uris.fold(ByteArray(0)) { bytes, uri -> bytes + fieldBytes(3, fieldString(1, uri)) }) +
            fieldVarint(15, 1_700_000_000_000L)

    fun playlist(name: String): ByteArray = rootlist(emptyList()) + fieldBytes(3, fieldString(1, name))

    fun collectionItem(uri: String, removed: Boolean = false): ByteArray =
        fieldBytes(1, fieldString(1, uri) + fieldVarint(2, 1_700_000_000) +
            fieldVarint(3, if (removed) 1 else 0))

    fun collection(uris: List<String>, next: String? = null): ByteArray =
        uris.fold(ByteArray(0)) { bytes, uri -> bytes + collectionItem(uri) } +
            (next?.let { fieldString(2, it) } ?: ByteArray(0)) + fieldString(3, "synthetic-sync")

    fun album(name: String): ByteArray = fieldString(2, name) +
        fieldBytes(3, fieldString(2, "Artist")) +
        fieldBytes(17, fieldBytes(1, fieldBytes(1, byteArrayOf(1, 2, 3))))

    fun track(name: String): ByteArray = fieldString(2, name) + fieldBytes(3, album("Album")) +
        fieldBytes(4, fieldString(2, "Artist")) + fieldVarint(7, 180_000 * 2) +
        fieldVarint(17, 1_700_000_000_000L)

    fun entity(uri: String, name: String = "Title", status: Int = 200): ByteArray {
        val isAlbum = uri.startsWith("spotify:album:")
        val type = if (isAlbum) "Album" else "Track"
        return fieldBytes(2, fieldVarint(2, if (isAlbum) 9 else 10) +
            fieldBytes(3, fieldBytes(1, fieldVarint(1, status)) + fieldString(2, uri) +
                fieldBytes(3, fieldString(1, "type.googleapis.com/spotify.metadata.$type") +
                    fieldBytes(2, if (isAlbum) album(name) else track(name)))))
    }
}
