package dev.nitrate.tv.ui.effects

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

/** The photo sets this build ships (`assets/fx/photos/<set>/photos.json`). */
fun shippedPhotoSets(context: android.content.Context): Set<String> = runCatching {
    // Folders under fx/photos (some asset managers list nested files too: take the first part) with a manifest.
    context.assets.list("fx/photos").orEmpty().map { it.substringBefore('/') }.distinct()
        .filter { set -> runCatching { context.assets.open("fx/photos/$set/photos.json").close(); true }.getOrDefault(false) }.toSet()
}.getOrDefault(emptySet())

private fun photoNames(context: android.content.Context, set: String): List<String> = runCatching {
    org.json.JSONArray(context.assets.open("fx/photos/$set/photos.json").bufferedReader().readText()).let { a -> (0 until a.length()).map { a.getString(it) } }
}.getOrDefault(emptyList())

private fun loadPhoto(context: android.content.Context, set: String, name: String): ImageBitmap? = runCatching {
    context.assets.open("fx/photos/$set/$name.jpg").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
}.getOrNull()

/**
 * A set of photos shown one after another, each slowly zooming and drifting (alternating ways) and
 * crossfading into the next: real pictures given a little life. Only the photo showing and the next
 * are in memory. [at] holds a moment (screenshots); [over] draws on top (particles, light).
 */
@Composable
fun PhotoStage(set: String, modifier: Modifier = Modifier, seconds: Float = 9f, at: Float? = null, over: (DrawScope.(Float) -> Unit)? = null) {
    val context = LocalContext.current
    val names = remember(set) { photoNames(context, set) }
    if (names.isEmpty()) return
    val t by produceState(at ?: 0f, at) {
        if (at != null) return@produceState
        val start = System.nanoTime()
        while (true) { value = (System.nanoTime() - start) / 1e9f; delay(33) }
    }
    val index = floor(t / seconds).toInt()
    val current by produceState<ImageBitmap?>(null, set, index % names.size) {
        value = withContext(Dispatchers.IO) { loadPhoto(context, set, names[index.mod(names.size)]) }
    }
    val next by produceState<ImageBitmap?>(null, set, (index + 1) % names.size) {
        value = withContext(Dispatchers.IO) { loadPhoto(context, set, names[(index + 1).mod(names.size)]) }
    }
    val fade = 1.6f
    Canvas(modifier.fillMaxSize()) {
        val u = t - index * seconds
        current?.let { kenBurns(it, index, u / seconds, 1f) }
        if (u > seconds - fade) next?.let { kenBurns(it, index + 1, 0f, ((u - (seconds - fade)) / fade).coerceIn(0f, 1f)) }
        over?.invoke(this, t)
    }
}

/** One photo filling the area (cropped to fit), zoomed in a little more as [p] goes 0→1, drifting. */
private fun DrawScope.kenBurns(image: ImageBitmap, n: Int, p: Float, alpha: Float) {
    val zoom = 1.04f + 0.12f * p
    val dstAspect = size.width / size.height
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    // The part of the photo that covers the area, then smaller by the zoom.
    var sw = if (iw / ih > dstAspect) ih * dstAspect else iw
    var sh = if (iw / ih > dstAspect) ih else iw / dstAspect
    sw /= zoom; sh /= zoom
    val dir = if (n % 2 == 0) 1f else -1f
    val cx = iw / 2 + dir * (iw - sw) * 0.4f * (p - 0.5f)
    val cy = ih / 2 + (if (n % 3 == 0) 1f else -0.5f) * (ih - sh) * 0.3f * (p - 0.5f)
    val sx = (cx - sw / 2).coerceIn(0f, iw - sw)
    val sy = (cy - sh / 2).coerceIn(0f, ih - sh)
    drawImage(
        image,
        srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
        srcSize = IntSize(sw.roundToInt().coerceAtMost(image.width), sh.roundToInt().coerceAtMost(image.height)),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
    )
}

/** Photos along the bottom of a page, fading out towards their top edge (like [ClipBand]). */
@Composable
fun PhotoBand(set: String, band: Float, modifier: Modifier = Modifier, at: Float? = null) {
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().fillMaxHeight(band)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.8f)), blendMode = BlendMode.DstIn)
            },
    ) {
        PhotoStage(set, at = at)
    }
}

/** Over the jellyfish: specks drifting through the light, some close and soft. */
object JellyWater {
    fun DrawScope.draw(t: Float) = jellyWater(t)
}

internal fun DrawScope.jellyWater(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 110) {
        val near = rnd(i, 2400).let { it * it }
        val y = ((rnd(i, 2401) - t * 0.008f * (0.4f + near)) % 1f + 1f) % 1f * h
        val x = rnd(i, 2402) * w + kotlin.math.sin(t * 0.3f + i) * 14f
        if (near > 0.65f) glow(Offset(x, y), 8f + near * 20f, Color(0xFFCFD8FF), 0.22f)
        else drawCircle(Color(0xFFE0E6FF).copy(alpha = 0.5f), 0.8f + near * 1.8f, Offset(x, y))
    }
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99060A2A)), center = Offset(w / 2, h / 2), radius = w * 0.7f))
}
