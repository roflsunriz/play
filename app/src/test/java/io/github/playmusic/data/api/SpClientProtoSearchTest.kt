package io.github.playmusic.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Recovered response entities; provenance and sanitization: docs/api-contracts.md. */
class SpClientProtoSearchTest {
    @Test
    fun capturedTrackAlbumAndPlaylistShapesAreParsed() {
        val hex = checkNotNull(javaClass.getResource("/captures/search-response.hex"))
            .readText().trim()
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val parsed = SpClientProto.parseSearchView(bytes)
        assertEquals(16, parsed.size)
        assertEquals(10, parsed.count { it.uri.startsWith("spotify:track:") })
        assertEquals(4, parsed.count { it.uri.startsWith("spotify:album:") })
        assertEquals(1, parsed.count { it.uri.startsWith("spotify:playlist:") })
        assertEquals("Smells Like Teen Spirit", parsed.first().name)
        assertEquals(listOf("ニルヴァーナ"), parsed.first().artists)
        assertEquals(listOf("ニルヴァーナ"), parsed.first { it.uri.startsWith("spotify:album:") }.artists)
        assertNotNull(parsed.first().imageUrl)
    }
}
