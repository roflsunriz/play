package io.github.playmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.playmusic.data.model.PlaylistLimits
import io.github.playmusic.ui.PlaylistArtwork
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistArtworkTest {
    @Test fun largeImageProducesBoundedSquareJpeg() {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val colors = IntArray(1600 * 1200) { index ->
            val n = (index * 1103515245 + 12345)
            Color.rgb(n ushr 16 and 255, n ushr 8 and 255, n and 255)
        }
        bitmap.setPixels(colors, 0, 1600, 0, 0, 1600, 1200)
        val source = ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, source)) } finally { bitmap.recycle() }
        val jpeg = PlaylistArtwork.encode(source.toByteArray())
        assertTrue(jpeg.size <= PlaylistLimits.MAX_IMAGE_BYTES)
        assertEquals(0xff, jpeg[0].toInt() and 255)
        assertEquals(0xd8, jpeg[1].toInt() and 255)
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            assertEquals(decoded.width, decoded.height)
            assertTrue(decoded.width in 128..512)
        } finally { decoded.recycle() }
    }

    @Test fun transparentImageHasOpaqueWhiteBackground() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, source)) } finally { bitmap.recycle() }
        val jpeg = PlaylistArtwork.encode(source.toByteArray())
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            assertEquals(32, decoded.width)
            assertEquals(32, decoded.height)
            val pixel = decoded.getPixel(16, 16)
            assertTrue(Color.red(pixel) >= 250 && Color.green(pixel) >= 250 && Color.blue(pixel) >= 250)
        } finally { decoded.recycle() }
    }

    @Test fun invalidImageDoesNotBecomeAnUpload() {
        assertTrue(runCatching { PlaylistArtwork.encode("not an image".toByteArray()) }.isFailure)
        assertTrue(runCatching { PlaylistArtwork.encode(byteArrayOf()) }.isFailure)
    }
}
