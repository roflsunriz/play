package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContextProtoTest {
    @Test
    fun countryAndCatalogueComeFromTheAccountResponse() {
        val context = AccountContextProto.parse(LibraryFixtures.accountContext("DE", "premium"))
        assertEquals(AccountContext("DE", "premium"), context)
        val bytes = SpClientProto.buildExtendedMetadataRequest(listOf("spotify:track:synthetic"), context)
        val root = ProtoWire.Reader(bytes)
        assertEquals(10, root.readTag())
        val header = ProtoWire.Reader(root.readBytes())
        assertEquals(10, header.readTag())
        assertEquals("DE", header.readString())
        assertEquals(18, header.readTag())
        assertEquals("premium", header.readString())
        assertEquals(26, header.readTag())
        assertEquals(16, header.readBytes().size)
    }

    @Test
    fun missingAttributesAndErrorsAreNotReplacedWithGuessedValues() {
        assertThrows(IllegalArgumentException::class.java) { AccountContextProto.parse(LibraryFixtures.accountContext("", "free")) }
        assertThrows(IllegalArgumentException::class.java) { AccountContextProto.parse(LibraryFixtures.accountContext("JP", "")) }
        assertThrows(IllegalStateException::class.java) { AccountContextProto.parse(ProtoWire.fieldBytes(2, byteArrayOf(8, 1))) }
        assertThrows(IllegalStateException::class.java) { AccountContextProto.parse(ByteArray(0)) }
    }
}
