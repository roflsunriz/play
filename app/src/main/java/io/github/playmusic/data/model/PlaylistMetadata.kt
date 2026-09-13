package io.github.playmusic.data.model

data class PlaylistMetadata(
    val uri: String,
    val name: String,
    val description: String,
    val imageUrl: String?,
    val ownerUsername: String?,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val isOwned: Boolean,
)

/** Play's input and upload bounds, not a claim about every provider endpoint. */
object PlaylistLimits {
    const val MAX_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 300
    const val MAX_IMAGE_BYTES = 192 * 1024

    fun validate(name: String, description: String, imageJpeg: ByteArray? = null) {
        require(name.isNotBlank()) { "Playlist name is empty" }
        require(name.length <= MAX_NAME_LENGTH) { "Playlist name is too long" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "Playlist description is too long" }
        imageJpeg?.let {
            require(it.size in 4..MAX_IMAGE_BYTES) { "Playlist image is too large or empty" }
            require(it[0] == 0xff.toByte() && it[1] == 0xd8.toByte() &&
                it[it.lastIndex - 1] == 0xff.toByte() && it.last() == 0xd9.toByte()) {
                "Playlist image must be JPEG"
            }
        }
    }
}
