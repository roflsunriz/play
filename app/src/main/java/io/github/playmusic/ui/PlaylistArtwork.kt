package io.github.playmusic.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import android.net.Uri
import android.os.Build
import io.github.playmusic.data.model.PlaylistLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads only the selected image and bounds source bytes, decoded pixels, and upload size. */
internal object PlaylistArtwork {
    private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    private const val MAX_DECODED_EDGE = 1024
    private const val COVER_EDGE = 512

    suspend fun read(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "A selected content image is required" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= MAX_SOURCE_BYTES) { "Selected image exceeds 20 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Selected image cannot be opened")
        encode(bytes)
    }

    internal fun encode(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SOURCE_BYTES) { "Invalid image size" }
        val source = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > MAX_DECODED_EDGE) decoder.setTargetSize(
                    (info.size.width.toLong() * MAX_DECODED_EDGE / edge).toInt().coerceAtLeast(1),
                    (info.size.height.toLong() * MAX_DECODED_EDGE / edge).toInt().coerceAtLeast(1),
                )
            }
        } else decodeLegacy(bytes)
        try {
            val cropEdge = minOf(source.width, source.height)
            require(cropEdge > 0) { "Image has no pixels" }
            val crop = Rect((source.width - cropEdge) / 2, (source.height - cropEdge) / 2,
                (source.width + cropEdge) / 2, (source.height + cropEdge) / 2)
            var edge = minOf(COVER_EDGE, cropEdge)
            while (true) {
                val cover = createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(cover).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(source, crop, Rect(0, 0, edge, edge), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    for (quality in 90 downTo 40 step 10) {
                        val output = ByteArrayOutputStream()
                        check(cover.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Image conversion failed" }
                        val jpeg = output.toByteArray()
                        if (jpeg.size <= PlaylistLimits.MAX_IMAGE_BYTES) return jpeg
                    }
                } finally {
                    cover.recycle()
                }
                check(edge > 128) { "Image cannot fit within the upload limit" }
                edge = (edge * 3 / 4).coerceAtLeast(128)
            }
        } finally {
            source.recycle()
        }
    }

    private fun decodeLegacy(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a supported image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODED_EDGE) sample *= 2
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "Image decoding failed" }
        val orientation = try {
            bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: java.io.IOException) {
            // Older platform decoders accept formats whose EXIF reader does not support metadata.
            ExifInterface.ORIENTATION_NORMAL
        }
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (transform.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        } finally {
            bitmap.recycle()
        }
    }
}
