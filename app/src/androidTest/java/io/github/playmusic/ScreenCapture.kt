package io.github.playmusic

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry

/** Optional images of synthetic test screens, kept in app-private storage. */
fun captureScreen(node: SemanticsNodeInteraction, name: String) {
    val prefix = InstrumentationRegistry.getArguments().getString("screenshotPrefix") ?: return
    require(prefix.matches(Regex("[a-z0-9-]+")))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = context.filesDir.resolve("ui-verification").apply { mkdirs() }
    directory.resolve("$prefix-$name.png").outputStream().use { output ->
        check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
    }
}
