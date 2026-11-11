package dev.nitrate.mobile.platform

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
