package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.effects.Occasion
import dev.nitrate.core.effects.PageTheme
import org.junit.Test
import androidx.compose.ui.graphics.drawscope.scale
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every drawn theme and occasion, at many moments and amounts: none may throw while drawing. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeDrawTest {
    private fun drawAll(what: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float) -> Unit): List<String> {
        val bitmap = ImageBitmap(960, 540)
        val scope = CanvasDrawScope()
        val failures = mutableListOf<String>()
        for (k in listOf(0.5f, 1.6f)) {
            var t = 0f
            while (t < 300f) {
                try {
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(960f, 540f)) { draw(t, k) }
                } catch (e: Throwable) {
                    failures += "$what t=$t k=$k: $e"
                    break
                }
                t += 4.37f
            }
        }
        return failures
    }

    /** How much of the screen a pass covers (share of pixels more than faintly drawn), at a few moments. */
    private fun coverage(depths: Set<Depth>, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float, Set<Depth>) -> Unit): Float {
        var worst = 0f
        for (t in listOf(2.3f, 7.9f, 15.1f)) {
            val bitmap = ImageBitmap(480, 270)
            val scope = CanvasDrawScope()
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(480f, 270f)) { scale(0.5f, 0.5f, androidx.compose.ui.geometry.Offset.Zero) { (this as androidx.compose.ui.graphics.drawscope.DrawScope).draw(t, 1f, depths) } }
            val px = IntArray(480 * 270)
            bitmap.readPixels(px)
            worst = maxOf(worst, px.count { (it ushr 24) > 60 } / px.size.toFloat())
        }
        return worst
    }

    /**
     * Each scene in layers: the front (drawn over the cards) stays light, a few passing things, never a
     * fill; and something is drawn behind the rows.
     */
    @Test fun frontLayersStayLight() {
        val heavy = mutableListOf<String>()
        val empty = mutableListOf<String>()
        fun check(name: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float, Set<Depth>) -> Unit) {
            val front = coverage(INFRONT, draw)
            if (front > 0.12f) heavy += "$name front covers ${(front * 100).toInt()}%"
            if (coverage(BEHIND, draw) < 0.002f) empty += name
        }
        PageTheme.entries.filter { it != PageTheme.NONE }.forEach { theme -> check(theme.name) { t, k, d -> drawThemeForTest(theme, t, k, d) } }
        Occasion.entries.forEach { o -> check(o.name) { t, k, d -> drawOccasionForTest(o, t, k, d) } }
        check(heavy.isEmpty()) { heavy.joinToString("\n") }
        check(empty.isEmpty()) { "nothing behind the rows: ${empty.joinToString()}" }
    }

    @Test fun everyThemeDraws() {
        val failures = PageTheme.entries.flatMap { theme -> drawAll(theme.name) { t, k -> drawThemeForTest(theme, t, k) } } +
            Occasion.entries.flatMap { o -> drawAll(o.name) { t, k -> drawOccasionForTest(o, t, k) } }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}
