package dev.nitrate.mobile.platform

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nitrate.mobile.ui.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The accent colour of [model]'s artwork (a URL or file), or null until it's loaded or if it's grey. */
@Composable
fun rememberArtworkColor(model: Any?): State<Color?> {
    val context = LocalContext.current
    return produceState<Color?>(null, model) {
        if (model == null) return@produceState
        val request = ImageRequest.Builder(context).data(model).size(48).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap().let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            ArtworkPalette.accent(pixels)?.let(::Color)
        }
    }
}

/**
 * The ink colour for a Cressida card: picked from [model]'s artwork (Smart), the app accent, or
 * the row's custom colour. Smart starts as [accent] and fades to its pick once the art is read.
 */
@Composable
fun rememberInkColor(model: Any?, display: dev.nitrate.core.rows.RowDisplay, accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val smart by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.Color?>(null, model, display.inkTint) {
        if (display.inkTint != dev.nitrate.core.rows.InkTint.SMART || model == null) return@produceState
        val request = coil3.request.ImageRequest.Builder(context).data(model).size(64).allowHardware(false).build()
        val result = coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bitmap = result.image.toBitmap().let { if (it.config == android.graphics.Bitmap.Config.ARGB_8888) it else it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            androidx.compose.ui.graphics.Color(dev.nitrate.core.rows.InkPalette.smart(pixels, bitmap.width).toInt())
        }
    }
    val target = when (display.inkTint) {
        dev.nitrate.core.rows.InkTint.SMART -> smart ?: accent
        dev.nitrate.core.rows.InkTint.ACCENT -> accent
        dev.nitrate.core.rows.InkTint.CUSTOM -> androidx.compose.ui.graphics.Color(display.inkArgb.toInt())
    }
    return androidx.compose.animation.animateColorAsState(target, androidx.compose.animation.core.tween(600), label = "ink").value
}
