package dev.nitrate.tv.ui.effects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil3.toBitmap
import coil3.request.allowHardware
import dev.nitrate.core.effects.PageBackdrop
import dev.nitrate.tv.ui.components.Backdrop

/**
 * What lies behind a menu page, kept by `MainMenu` for as long as the menu shows, so switching tabs
 * (or pages) never tears it down: the page's backdrop ([PageBackdrop]: the focused title's picture, a
 * colour from it, a gradient or a flat colour) and over it the back and middle of the page's theme.
 * A page with a focused title reports its picture with [PageBackdropSlot]; [titles] says whether one
 * does (else a picture-based backdrop has nothing to show and the page's own background shows).
 */
class ThemeBehind {
    var artwork by mutableStateOf<String?>(null)
    /**
     * The page captions its title in a corner with its own shade (rows one at a time, full art), so
     * the picture is drawn as the screensaver draws it: whole and unwashed.
     */
    var clean by mutableStateOf(false)
    var titles by mutableIntStateOf(0)
}

val LocalThemeBehind = staticCompositionLocalOf<ThemeBehind?> { null }

/**
 * Where a page with a focused title puts its backdrop: under a menu, the menu draws it (with the
 * theme over it, both kept across tab switches), the page only says which picture; with no menu
 * (previews, tests) the picture is drawn here.
 */
@Composable
fun PageBackdropSlot(url: String?, modifier: Modifier = Modifier, clean: Boolean = false) {
    val behind = LocalThemeBehind.current
    if (behind == null) {
        if (clean) dev.nitrate.tv.ui.components.CleanBackdrop(url, modifier.fillMaxSize()) else Backdrop(url, modifier.fillMaxSize())
        return
    }
    DisposableEffect(behind) {
        behind.titles++
        onDispose { behind.titles--; if (behind.titles == 0) behind.artwork = null }
    }
    SideEffect { behind.artwork = url; behind.clean = clean }
}

/** The page's backdrop as chosen; [artwork] is the focused title's picture (null: none to follow). */
@Composable
fun PageBackground(choice: PageBackdrop, artwork: String?, modifier: Modifier = Modifier, clean: Boolean = false) {
    when (choice.kind) {
        PageBackdrop.Kind.ARTWORK -> if (clean) dev.nitrate.tv.ui.components.CleanBackdrop(artwork, modifier.fillMaxSize()) else Backdrop(artwork, modifier.fillMaxSize())
        PageBackdrop.Kind.GRADIENT -> Box(modifier.fillMaxSize().background(Brush.linearGradient(choice.colors.map { Color(it) }, start = Offset.Zero, end = Offset.Infinite)))
        PageBackdrop.Kind.SOLID -> Box(modifier.fillMaxSize().background(Color(choice.colors.first())))
        PageBackdrop.Kind.SMART_GRADIENT, PageBackdrop.Kind.SMART_COLOUR -> {
            val picked = rememberArtColor(artwork) ?: Color(0xFF16181E)
            val base by animateColorAsState(Color(PageBackdrop.darkened(argbOf(picked)).toInt()), tween(700), label = "smart")
            if (choice.kind == PageBackdrop.Kind.SMART_COLOUR) Box(modifier.fillMaxSize().background(base))
            else Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(lighten(base), base, Color(0xFF050608)), start = Offset.Zero, end = Offset.Infinite)))
        }
    }
}

private fun argbOf(c: Color): Long {
    fun ch(v: Float) = (v * 255f + 0.5f).toLong().coerceIn(0, 255)
    return (0xFFL shl 24) or (ch(c.red) shl 16) or (ch(c.green) shl 8) or ch(c.blue)
}

private fun lighten(c: Color) = Color((c.red * 1.7f).coerceAtMost(1f), (c.green * 1.7f).coerceAtMost(1f), (c.blue * 1.7f).coerceAtMost(1f))

/** The main colour of a picture (as Cressida's smart ink picks it), or null until it's read. */
@Composable
fun rememberArtColor(url: String?): Color? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cache = remember { HashMap<String, Color>() }
    val colour by produceState<Color?>(url?.let { cache[it] }, url) {
        if (url == null) return@produceState
        cache[url]?.let { value = it; return@produceState }
        val request = coil3.request.ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build()
        val result = coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bitmap = result.image.toBitmap().let { if (it.config == android.graphics.Bitmap.Config.ARGB_8888) it else it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            Color(dev.nitrate.core.rows.InkPalette.smart(pixels, bitmap.width).toInt())
        }.also { cache[url] = it }
    }
    return colour
}
