package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoParseException
import io.github.playmusic.data.auth.ProtoWire
import java.io.ByteArrayOutputStream

/** Native playlist4 OpList/Delta/ListChanges contracts, also used by the provider web client. */
internal object PlaylistMutationProto {
    // The create endpoint takes OpList (ops = 1). Only /changes uses Delta (ops = 2).
    fun create(name: String, description: String): ByteArray = ProtoWire.fieldMessage(1, metadata(name, description))

    fun metadata(name: String, description: String, picture: ByteArray? = null, removePicture: Boolean = false): ByteArray {
        val attributes = ProtoWire.fieldString(1, name) +
            (if (description.isNotEmpty()) ProtoWire.fieldString(2, description) else byteArrayOf()) +
            (picture?.let { ProtoWire.fieldBytes(3, it) } ?: byteArrayOf())
        val partial = ProtoWire.fieldMessage(1, attributes) +
            (if (description.isEmpty()) ProtoWire.fieldVarint(2, 2) else byteArrayOf()) +
            (if (removePicture) ProtoWire.fieldVarint(2, 3) else byteArrayOf())
        return ProtoWire.fieldVarint(1, 6) + ProtoWire.fieldMessage(6, ProtoWire.fieldMessage(1, partial))
    }

    fun add(uris: List<String>, timestamp: Long, privateInRootlist: Boolean = false): ByteArray {
        val items = ByteArrayOutputStream()
        uris.forEach { uri ->
            val attributes = ProtoWire.fieldVarint(2, timestamp) +
                (if (privateInRootlist) ProtoWire.fieldVarint(10, 0) else byteArrayOf())
            items.write(ProtoWire.fieldMessage(2, ProtoWire.fieldString(1, uri) + ProtoWire.fieldMessage(2, attributes)))
        }
        return ProtoWire.fieldVarint(1, 2) +
            ProtoWire.fieldMessage(2, items.toByteArray() + ProtoWire.fieldVarint(4, 1))
    }

    fun remove(uris: List<String>): ByteArray {
        val items = ByteArrayOutputStream()
        uris.forEach { items.write(ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, it))) }
        return ProtoWire.fieldVarint(1, 3) +
            ProtoWire.fieldMessage(3, items.toByteArray() + ProtoWire.fieldVarint(7, 1))
    }

    fun changes(operations: List<ByteArray>): ByteArray =
        ProtoWire.fieldMessage(2, delta(operations)) + ProtoWire.fieldVarint(3, 1)

    private fun delta(operations: List<ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        operations.forEach { result.write(ProtoWire.fieldMessage(2, it)) }
        return result.toByteArray()
    }

    fun createdUri(bytes: ByteArray): String {
        val uri = readField(bytes, 1)?.toString(Charsets.UTF_8)
            ?: throw ProtoParseException("Playlist creation response has no URI")
        require(uri.matches(Regex("spotify:playlist:[A-Za-z0-9]{22}"))) { "Playlist creation response has an invalid URI" }
        return uri
    }

    fun registeredPicture(bytes: ByteArray): ByteArray = readField(bytes, 1)?.takeIf { it.isNotEmpty() }
        ?: throw ProtoParseException("Playlist image registration response has no picture")

    private fun readField(bytes: ByteArray, fieldNumber: Int): ByteArray? {
        val reader = ProtoWire.Reader(bytes)
        var result: ByteArray? = null
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.fieldNumber(tag) == fieldNumber) result = reader.readBytes()
            else reader.skip(reader.wireType(tag))
        }
        return result
    }
}
