package io.github.playmusic.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApIdentityProtoTest {
    @Test fun usesTheCanonicalIdentityFromAnAuthenticatedWelcome() {
        val response = ProtoWire.fieldVarint(20, 0) + ProtoWire.fieldString(10, "canonical-user") +
            ProtoWire.fieldBytes(40, byteArrayOf(1, 2, 3))
        assertEquals("canonical-user", ApIdentityProto.username(0xac, response))
    }

    @Test fun aServerRejectionCannotBecomeAnAuthenticatedSession() {
        assertThrows(SpotifyAuthException::class.java) {
            ApIdentityProto.username(0xad, ProtoWire.fieldVarint(10, 12))
        }
    }

    @Test fun rejectsMissingIdentityAndUnexpectedPackets() {
        assertThrows(SpotifyAuthException::class.java) { ApIdentityProto.username(0xac, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ApIdentityProto.username(0x04, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ApIdentityProto.username(0xac, ProtoWire.fieldString(10, "")) }
    }
}
