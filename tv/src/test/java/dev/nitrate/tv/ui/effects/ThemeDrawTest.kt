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

    @Test fun everyThemeDraws() {
        val failures = PageTheme.entries.flatMap { theme -> drawAll(theme.name) { t, k -> drawThemeForTest(theme, t, k) } } +
            Occasion.entries.flatMap { o -> drawAll(o.name) { t, k -> drawOccasionForTest(o, t, k) } }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}
