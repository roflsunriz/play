package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.PlaylistLimits
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class PlaylistApiClientTest {
    @Test
    fun creationUsesAnOpListAndMakesTheNewPlaylistPrivateBeforeAddingItToTheLibrary() = runTest {
        val server = Server()
        val created = server.client.create("作成テスト", "説明文")
        assertEquals(NEW_URI, created.uri)
        assertEquals("作成テスト", created.title)
        assertEquals("BLOCKED", server.permission)
        assertEquals(listOf(OTHER_URI, NEW_URI), server.rootlist)
        val create = server.requests.single { it.url.path == "/playlist/v2/playlist" }
        val operation = fields(create.body).bytes(1)
        assertEquals(6L, fields(operation).number(1))
        assertEquals("作成テスト", attributes(operation).string(1))
        val rootAdd = server.requests.single { it.url.path.endsWith("rootlist/changes") }
        val add = fields(fields(fields(rootAdd.body).bytes(2)).bytes(2)).bytes(2)
        assertEquals(NEW_URI, fields(fields(add).bytes(2)).string(1))
        assertEquals(0L, fields(fields(fields(add).bytes(2)).bytes(2)).number(10))
        assertEquals(1L, fields(add).number(4))
    }

    @Test
    fun creationWireMatchesTheProviderOpListContractRatherThanDelta() {
        val expected = "0a0e0806320a0a080a060a0141120142".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, PlaylistMutationProto.create("A", "B"))
    }

    @Test
    fun creationIsNotReportedCompleteWhenTheServerIgnoresInitialMetadata() = runTest {
        val server = Server().apply { ignoreInitialMetadata = true }
        val failure = runCatching { server.client.create("New name", "New description") }.exceptionOrNull()
        assertTrue(failure is PlaylistCreationException)
        assertEquals(NEW_URI, (failure as PlaylistCreationException).createdContent.uri)
        assertEquals(1, server.requests.count { it.url.path == "/playlist/v2/playlist" })
    }

    @Test
    fun failedSetupRetainsTheCreatedUriAndResumesWithoutCreatingADuplicate() = runTest {
        val server = Server().apply { failPermission = true }
        val failure = runCatching { server.client.create("名前", "説明") }.exceptionOrNull()
        assertTrue(failure is PlaylistCreationException)
        val created = (failure as PlaylistCreationException).createdContent
        assertEquals(NEW_URI, created.uri)
        assertEquals(listOf(OTHER_URI), server.rootlist)
        server.failPermission = false
        server.client.completeCreation(created)
        server.client.completeCreation(created)
        assertEquals(1, server.requests.count { it.url.path == "/playlist/v2/playlist" })
        assertEquals(1, server.requests.count { it.url.path.endsWith("rootlist/changes") })
        assertEquals(listOf(OTHER_URI, NEW_URI), server.rootlist)
    }

    @Test
    fun imageUploadFailureDoesNotPartiallySaveNamesOrDescriptions() = runTest {
        val server = Server().apply { failImage = true }
        val failure = runCatching { server.client.update(content(), "新しい名前", "新しい説明", JPEG) }.exceptionOrNull()
        assertTrue(failure is ServiceApiException)
        assertFalse(server.requests.any { it.url.path.endsWith("/changes") })
        assertEquals("Original", server.name)
        assertEquals("Original description", server.description)
        server.failImage = false
        val updated = server.client.update(content(), "新しい名前", "新しい説明", JPEG)
        assertEquals("新しい名前", updated.title)
        assertEquals(IMAGE_URL, updated.imageUrl)
        val upload = server.requests.last { it.url.host == "image-upload.spotify.com" }
        assertArrayEquals(JPEG, upload.body)
        assertEquals("image/jpeg", upload.getRequestProperty("Content-Type"))
        val register = server.requests.single { it.url.path.endsWith("/register-image") }
        assertEquals("synthetic-upload-token", fields(register.body).string(1))
        val changes = server.requests.single { it.url.path.endsWith("/changes") }
        val operation = fields(fields(changes.body).bytes(2)).bytes(2)
        assertArrayEquals(PICTURE, attributes(operation).bytes(3))
        assertEquals("新しい説明", attributes(operation).string(2))
    }

    @Test
    fun clearingDescriptionAndPictureUsesNoValueInsteadOfWritingAmbiguousEmptyValues() = runTest {
        val server = Server().apply { picture = PICTURE }
        val updated = server.client.update(content(), "Updated", "", removeImage = true)
        assertEquals("", server.description)
        assertNull(updated.imageUrl)
        val changes = server.requests.single { it.url.path.endsWith("/changes") }
        val operation = fields(fields(changes.body).bytes(2)).bytes(2)
        val partial = partialAttributes(operation)
        assertEquals(listOf(2L, 3L), partial.filter { it.number == 2 }.map { it.integer })
        assertFalse(fields(partial.bytes(1)).any { it.number == 2 || it.number == 3 })
    }

    @Test
    fun uploadedPictureIsDisplayedWhenTheResponseContainsOnlyItsBinaryId() = runTest {
        val server = Server().apply { decorateImages = false }
        val updated = server.client.update(content(), "Changed", "Description", JPEG)
        assertEquals("https://i.scdn.co/image/03040506", updated.imageUrl)
        val cleared = server.client.update(updated, "Changed", "Description", removeImage = true)
        assertNull(cleared.imageUrl)
    }

    @Test
    fun decoratedPictureSizesTakePrecedenceOverTheBinaryIdAndEmptyIdsHaveNoUrl() {
        val picture = ProtoWire.fieldBytes(3, PICTURE)
        val decorated = ProtoWire.fieldMessage(13, ProtoWire.fieldString(1, "default") + ProtoWire.fieldString(2, IMAGE_URL))
        assertEquals(IMAGE_URL, SpClientProto.parsePlaylist(ProtoWire.fieldMessage(3, picture + decorated)).images["default"])
        assertTrue(SpClientProto.parsePlaylist(ProtoWire.fieldMessage(3, ProtoWire.fieldBytes(3, byteArrayOf()))).images.isEmpty())
    }

    @Test
    fun deletionOnlyRemovesTheSelectedOwnedPlaylistFromTheRootlist() = runTest {
        val server = Server().apply { rootlist += NEW_URI }
        server.client.delete(content())
        assertEquals(listOf(OTHER_URI), server.rootlist)
        assertEquals("Original", server.name)
        assertEquals("Original description", server.description)
        val mutation = server.requests.single { it.requestMethod == "POST" }
        assertTrue(mutation.url.path.endsWith("rootlist/changes"))
        val operation = fields(fields(mutation.body).bytes(2)).bytes(2)
        assertEquals(3L, fields(operation).number(1))
        val remove = fields(fields(operation).bytes(3))
        assertEquals(1L, remove.number(7))
        assertEquals(NEW_URI, fields(remove.bytes(3)).string(1))
    }

    @Test
    fun foreignPlaylistsAndExplicitPermissionDenialsCannotBeMutated() = runTest {
        for (foreignOwner in listOf(true, false)) {
            val server = Server().apply {
                owner = if (foreignOwner) "another-user" else USER
                permissions = ProtoWire.fieldVarint(4, 0) + ProtoWire.fieldVarint(13, 0)
            }
            val metadata = server.client.metadata(content())
            assertFalse(metadata.canEdit)
            assertFalse(metadata.canDelete)
            assertTrue(runCatching { server.client.update(content(), "Changed", "") }.isFailure)
            assertTrue(runCatching { server.client.delete(content()) }.isFailure)
            assertFalse(server.requests.any { it.requestMethod != "GET" })
        }
    }

    @Test
    fun picturePermissionDenialStopsBeforeImageUpload() = runTest {
        val server = Server().apply {
            permissions = ProtoWire.fieldVarint(4, 1) + ProtoWire.fieldMessage(8,
                ProtoWire.fieldMessage(3, ProtoWire.fieldVarint(1, 0)))
        }
        assertTrue(server.client.metadata(content()).canEdit)
        assertTrue(runCatching { server.client.update(content(), "Changed", "", JPEG) }.isFailure)
        assertFalse(server.requests.any { it.requestMethod != "GET" })
    }

    @Test
    fun invalidInputIsRejectedBeforeAnyRequest() = runTest {
        val server = Server()
        for ((name, description) in listOf(" " to "", "x".repeat(101) to "", "Name" to "x".repeat(301))) {
            assertTrue(runCatching { server.client.create(name, description) }.isFailure)
        }
        assertTrue(runCatching { server.client.update(content(), "Name", "", byteArrayOf(1, 2, 3, 4)) }.isFailure)
        assertTrue(runCatching { server.client.update(content(), "Name", "", JPEG, true) }.isFailure)
        assertTrue(runCatching { server.client.delete(content().copy(id = "../other")) }.isFailure)
        assertEquals(0, server.requests.size)
        PlaylistLimits.validate("x".repeat(100), "x".repeat(300), JPEG)
    }

    @Test
    fun nativeMetadataParsesDescriptionOwnerAndAttributeCapabilities() {
        val capabilities = ProtoWire.fieldVarint(4, 1) + ProtoWire.fieldMessage(8,
            ProtoWire.fieldMessage(1, ProtoWire.fieldVarint(1, 1)) +
                ProtoWire.fieldMessage(2, ProtoWire.fieldVarint(1, 0)) +
                ProtoWire.fieldMessage(3, ProtoWire.fieldVarint(1, 1))) +
            ProtoWire.fieldVarint(13, 1)
        val bytes = ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, "名前") + ProtoWire.fieldString(2, "説明")) +
            ProtoWire.fieldString(16, USER) + ProtoWire.fieldMessage(18, capabilities)
        val detail = SpClientProto.parsePlaylist(bytes)
        assertEquals("説明", detail.description)
        assertEquals(USER, detail.ownerUsername)
        assertEquals(true, detail.capabilities.canEditName)
        assertEquals(false, detail.capabilities.canEditDescription)
        assertEquals(true, detail.capabilities.canEditPicture)
        assertEquals(true, detail.capabilities.canDelete)
    }

    private class Server {
        val requests = mutableListOf<Connection>()
        var name = "Original"
        var description = "Original description"
        var owner = USER
        var permission = "VIEWER"
        var picture: ByteArray? = null
        var permissions = ProtoWire.fieldVarint(4, 1) + ProtoWire.fieldVarint(5, 1) + ProtoWire.fieldVarint(13, 1)
        var failPermission = false
        var failImage = false
        var ignoreInitialMetadata = false
        var decorateImages = true
        val rootlist = mutableListOf(OTHER_URI)
        private val tokens = object : SessionTokens {
            override suspend fun username() = USER
            override suspend fun accessToken(forceRefresh: Boolean) = "synthetic-access"
            override suspend fun clientToken(forceRefresh: Boolean) = "synthetic-client"
            override suspend fun usesBrowserAuthorization() = true
        }
        val api = ServiceApiClient(tokens) { Connection(it, ::reply).also(requests::add) }
        val client = PlaylistApiClient(api, tokens)

        fun reply(request: Connection): Reply {
            val path = request.url.path
            return when {
                path == "/playlist/v2/playlist" -> {
                    if (!ignoreInitialMetadata) applyMetadata(fields(request.body).bytes(1))
                    Reply(bytes = ProtoWire.fieldString(1, NEW_URI))
                }
                path.endsWith("permission/base") -> {
                    if (request.requestMethod == "POST") {
                        if (failPermission) return Reply(status = 403)
                        permission = JSONObject(request.body.toString(Charsets.UTF_8)).getString("permissionLevel")
                    }
                    Reply(bytes = """{"permissionLevel":"$permission","revision":"AQID"}""".toByteArray())
                }
                path.endsWith("/rootlist") -> Reply(bytes = ProtoWire.fieldMessage(5,
                    rootlist.fold(ProtoWire.fieldVarint(1, 0) + ProtoWire.fieldVarint(2, 0)) { result, uri ->
                        result + ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, uri))
                    }))
                path.endsWith("rootlist/changes") -> {
                    val operation = fields(fields(request.body).bytes(2)).bytes(2)
                    when (fields(operation).number(1)) {
                        2L -> {
                            assertEquals("BLOCKED", permission)
                            val item = fields(fields(operation).bytes(2)).bytes(2)
                            rootlist += fields(item).string(1)
                        }
                        3L -> {
                            val item = fields(fields(operation).bytes(3)).bytes(3)
                            rootlist.remove(fields(item).string(1))
                        }
                        else -> error("Unexpected rootlist mutation")
                    }
                    Reply()
                }
                path.endsWith("/register-image") -> Reply(bytes = ProtoWire.fieldBytes(1, PICTURE))
                path == "/v4/playlist" -> if (failImage) Reply(status = 403)
                    else Reply(bytes = """{"uploadToken":"synthetic-upload-token"}""".toByteArray())
                path.endsWith("/changes") -> {
                    applyMetadata(fields(fields(request.body).bytes(2)).bytes(2))
                    Reply()
                }
                path == "/playlist/v2/playlist/$ID" -> Reply(bytes =
                    ProtoWire.fieldMessage(3, ProtoWire.fieldString(1, name) + ProtoWire.fieldString(2, description) +
                        (picture?.let { ProtoWire.fieldBytes(3, it) } ?: byteArrayOf()) +
                        if (picture != null && decorateImages) ProtoWire.fieldMessage(13, ProtoWire.fieldString(1, "default") +
                            ProtoWire.fieldString(2, IMAGE_URL)) else byteArrayOf()) +
                        ProtoWire.fieldString(16, owner) + ProtoWire.fieldMessage(18, permissions))
                else -> error("Unexpected request path: $path")
            }
        }

        private fun applyMetadata(operation: ByteArray) {
            val partial = partialAttributes(operation)
            val values = fields(partial.bytes(1))
            values.firstOrNull { it.number == 1 }?.bytes?.let { name = it.toString(Charsets.UTF_8) }
            values.firstOrNull { it.number == 2 }?.bytes?.let { description = it.toString(Charsets.UTF_8) }
            values.firstOrNull { it.number == 3 }?.bytes?.let { picture = it }
            partial.filter { it.number == 2 }.forEach {
                if (it.integer == 2L) description = ""
                if (it.integer == 3L) picture = null
            }
        }
    }

    private data class Reply(val status: Int = 200, val bytes: ByteArray = byteArrayOf())
    private class Connection(uri: URI, private val reply: (Connection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        private val response by lazy { reply(this) }
        val body get() = output.toByteArray()
        override fun getOutputStream() = output
        override fun getResponseCode() = response.status
        override fun getInputStream() = ByteArrayInputStream(response.bytes)
        override fun getErrorStream() = ByteArrayInputStream(response.bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private data class Field(val number: Int, val bytes: ByteArray? = null, val integer: Long? = null)

    companion object {
        private const val USER = "synthetic-user"
        private const val ID = "0123456789ABCDEFGHIJKL"
        private const val NEW_URI = "spotify:playlist:$ID"
        private const val OTHER_URI = "spotify:playlist:abcdefghijklmnopqrstuv"
        private const val IMAGE_URL = "https://image.invalid/playlist-picture"
        private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        private val PICTURE = byteArrayOf(3, 4, 5, 6)
        private fun content() = MusicContent(ID, NEW_URI, "Original", "", null, ContentKind.PLAYLIST)
        private fun partialAttributes(operation: ByteArray): List<Field> = fields(fields(fields(operation).bytes(6)).bytes(1))
        private fun attributes(operation: ByteArray): List<Field> = fields(partialAttributes(operation).bytes(1))
        private fun List<Field>.bytes(number: Int) = checkNotNull(single { it.number == number }.bytes)
        private fun List<Field>.number(number: Int) = checkNotNull(single { it.number == number }.integer)
        private fun List<Field>.string(number: Int) = bytes(number).toString(Charsets.UTF_8)
        private fun fields(bytes: ByteArray): List<Field> {
            val reader = ProtoWire.Reader(bytes)
            val result = mutableListOf<Field>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.wireType(tag)) {
                    0 -> result += Field(reader.fieldNumber(tag), integer = reader.readVarint())
                    2 -> result += Field(reader.fieldNumber(tag), bytes = reader.readBytes())
                    else -> error("Unexpected test fixture wire type")
                }
            }
            return result
        }
    }
}
