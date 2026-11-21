package dev.nitrate.tv.ui.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nitrate.core.effects.CardSpark
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sparks over a whole screen (full art, rows with their cards hidden) are drawn at the screen's scale. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ScreenSparkTest {
    private val moments = mapOf(
        CardSpark.LIGHTNING to 0.02f, CardSpark.FIREWORKS to 0.45f, CardSpark.SPARKLES to 0.4f,
        CardSpark.EMBERS to 0.5f, CardSpark.SHIMMER to 0.45f,
    )

    private fun draw(spark: CardSpark, p: Float): ImageBitmap {
        val bitmap = ImageBitmap(1920, 1080)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bitmap), Size(1920f, 1080f)) {
            drawRect(Color(0xFF101820))
            drawSparkAnySize(spark, p, 1f, 1234)
        }
        return bitmap
    }

    @Test fun eachSparkReachesAcrossTheScreen() {
        val narrow = mutableListOf<String>()
        val sheet = android.graphics.Bitmap.createBitmap(960, 540 * 5, android.graphics.Bitmap.Config.ARGB_8888)
        val out = android.graphics.Canvas(sheet)
        moments.entries.forEachIndexed { i, (spark, p) ->
            val bitmap = draw(spark, p)
            val px = IntArray(1920 * 1080)
            bitmap.readPixels(px)
            // Columns with something lit: a card-sized spark would light a quarter of them at most.
            val base = px[0]
            val lit = (0 until 1920 step 8).count { x -> (0 until 1080 step 8).any { y -> px[y * 1920 + x] != base } }
            if (lit < 1920 / 8 * 0.45f) narrow += "$spark lights only ${lit * 8} px across"
            out.drawBitmap(android.graphics.Bitmap.createScaledBitmap(bitmap.asAndroidBitmap(), 960, 540, true), 0f, 540f * i, null)
        }
        java.io.File("build/outputs/roborazzi").mkdirs()
        java.io.File("build/outputs/roborazzi/tv-screen-sparks.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        check(narrow.isEmpty()) { narrow.joinToString("\n") }
    }

    /** The strike is as bold on a 4K screen as on 1080p: its bright core grows with the picture. */
    @Test fun lightningScalesWithTheScreen() {
        fun core(w: Int, h: Int): Int {
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color.Black)
                drawScreenSpark(CardSpark.LIGHTNING, 0.02f, 1f, 1234)
            }
            val px = IntArray(w * h)
            bitmap.readPixels(px)
            // The white-hot core: all three channels near full.
            return px.count { (it shr 16 and 0xFF) > 235 && (it shr 8 and 0xFF) > 235 && (it and 0xFF) > 235 }
        }
        val hd = core(1920, 1080)
        val uhd = core(3840, 2160)
        // Twice as long and twice as thick: about four times the pixels (fixed strokes would give two).
        check(uhd > hd * 3) { "4K core $uhd px vs 1080p $hd px: the bolt didn't thicken" }
    }

    @Test fun cardsKeepTheirOwnSparks() {
        // A large art card is still a card: its spark is the card's, not the screen's.
        val bitmap = ImageBitmap(1280, 680)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bitmap), Size(1280f, 680f)) {
            CardSpark.entries.forEach { drawSparkAnySize(it, 0.4f, 1f, 9) }
        }
    }
}
