package io.github.playmusic.data.api

import io.github.playmusic.data.auth.ProtoWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpClientLibraryProtoTest {
    @Test
    fun rootlistTreatsRevisionAsBytesAndTimestampAsAnIndependentField() {
        val parsed = SpClientProto.parseRootlist(
            LibraryFixtures.rootlist(listOf("spotify:playlist:first", "spotify:playlist:second"), 120, true),
        )
        assertEquals(listOf("spotify:playlist:first", "spotify:playlist:second"), parsed.items.map { it.uri })
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 0xFF.toByte(), 2), parsed.revision)
        assertEquals(120, parsed.offset)
        assertTrue(parsed.truncated)
    }

    @Test
    fun playlistReadsTitleAfterRevisionAndUnknownFields() {
        val parsed = SpClientProto.parsePlaylist(
            ProtoWire.fieldString(30, "unknown") + LibraryFixtures.playlist("My playlist"),
        )
        assertEquals("My playlist", parsed.name)
        assertTrue(parsed.items.isEmpty())
    }

    @Test
    fun decoratedRootlistReadsDescriptionOwnerAndLengthWithoutShiftingBlankUris() {
        val contents = ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, "")) +
            ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, "spotify:playlist:valid")) +
            ProtoWire.fieldBytes(4, ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "Unused")) +
                ProtoWire.fieldString(5, "unused-owner") + ProtoWire.fieldVarint(3, 999)) +
            ProtoWire.fieldBytes(4, ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "") + ProtoWire.fieldString(2, "Description")) +
                ProtoWire.fieldString(5, "actual-owner") + ProtoWire.fieldVarint(3, 42) + ProtoWire.fieldVarint(9, 400))
        val item = SpClientProto.parseRootlist(ProtoWire.fieldBytes(5, contents)).items.single { it.uri.isNotBlank() }
        assertEquals("spotify:playlist:valid", item.uri)
        assertEquals("", item.metadata?.attributes?.name)
        assertEquals("Description", item.metadata?.attributes?.description)
        assertEquals("actual-owner", item.metadata?.ownerUsername)
        assertEquals(42, item.metadata?.length)
        assertEquals(200, item.metadata?.status)
    }

    @Test
    fun absentCountsStayUnknownAndOverflowedCountsFail() {
        assertEquals(null, SpClientProto.parsePlaylist(LibraryFixtures.playlist("Playlist")).totalLength)
        val overflow = ProtoWire.fieldVarint(2, Int.MAX_VALUE.toLong() + 1)
        assertTrue(runCatching { SpClientProto.parsePlaylist(overflow) }.isFailure)
        val contents = ProtoWire.fieldBytes(3, ProtoWire.fieldString(1, "spotify:playlist:one")) +
            ProtoWire.fieldBytes(4, ProtoWire.fieldVarint(3, Int.MAX_VALUE.toLong() + 1))
        assertTrue(runCatching { SpClientProto.parseRootlist(ProtoWire.fieldBytes(5, contents)) }.isFailure)
    }

    @Test
    fun collectionReadsScalarTimestampRemovalAndBothTokens() {
        val parsed = SpClientProto.parseCollectionPage(
            LibraryFixtures.collection(listOf("spotify:track:first"), "synthetic-next") +
                LibraryFixtures.collectionItem("spotify:track:removed", removed = true),
        )
        assertEquals("synthetic-next", parsed.nextPageToken)
        assertEquals("synthetic-sync", parsed.syncToken)
        assertEquals(1_700_000_000L, parsed.items.first().addedAtSeconds)
        assertFalse(parsed.items.first().removed)
        assertTrue(parsed.items.last().removed)
    }

    @Test
    fun metadataReadsCoverFromTrackAlbumAndZigZagDuration() {
        val track = SpClientProto.parseExtendedMetadata(LibraryFixtures.entity("spotify:track:first")).single()
        assertEquals("spotify:track:first", track.uri)
        assertEquals("Title", track.name)
        assertEquals(listOf("Artist"), track.artists)
        assertEquals("https://i.scdn.co/image/010203", track.imageUrl)
        assertEquals(180_000L, track.durationMs)
        assertEquals(200, track.status)
    }

    @Test
    fun metadataRequestsUseTypedExtensionsWithoutGuessingAccountMarket() {
        // EntityRequest{uri, query{extension_kind=ALBUM_V4(9) / TRACK_V4(10)}}
        val expected = ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "spotify:album:first") + byteArrayOf(18, 2, 8, 9)) +
            ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, "spotify:track:second") + byteArrayOf(18, 2, 8, 10))
        assertArrayEquals(expected, SpClientProto.buildExtendedMetadataRequest(listOf("spotify:album:first", "spotify:track:second")))
    }

    @Test
    fun collectionRequestEncodesContinuationTokenInFieldThree() {
        val expected = ProtoWire.fieldString(1, "user") + ProtoWire.fieldString(2, "collection") +
            ProtoWire.fieldString(3, "synthetic-next") + byteArrayOf(32, 0xC8.toByte(), 1)
        assertArrayEquals(expected, SpClientProto.buildCollectionPageRequest("user", "collection", 200, "synthetic-next"))
    }
}
