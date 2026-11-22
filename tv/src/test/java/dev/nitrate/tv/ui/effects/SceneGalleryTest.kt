package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.effects.PageTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws page themes as pictures (build/outputs/scenes/<theme>-<t>.png) over a plain dark page, to
 * look at while drawing them. Set -Pscenes=NAME,NAME to choose; without it nothing is drawn.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class SceneGalleryTest {
    @Test fun gallery() {
        val wanted = System.getProperty("scenes")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        val out = File("build/outputs/scenes").apply { mkdirs() }
        val times = (System.getProperty("sceneTimes") ?: "3.1,9.7").split(',').map { it.trim().toFloat() }
        for (name in wanted) {
            val theme = PageTheme.valueOf(name)
            for (t in times) {
                val bitmap = ImageBitmap(1280, 720)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(1280f, 720f)) {
                    // A dull stand-in for a title's picture, as the scene is drawn over one.
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3140), Color(0xFF151820))))
                    drawCircle(Color(0x33FFFFFF), 260f, Offset(900f, 300f))
                    drawThemeForTest(theme, t, 1f)
                }
                File(out, "$name-$t.png").outputStream().use { bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
