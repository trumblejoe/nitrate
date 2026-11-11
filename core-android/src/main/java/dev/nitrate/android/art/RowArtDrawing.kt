package dev.nitrate.android.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import dev.nitrate.core.rows.RowArt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Drawing for the Film strip, Lightning split and Cressida row styles, shared by phone and TV. */
object RowArtDrawing {
    /** Film base: nearly black, a little warm. */
    val FilmBase = Color(0xFF14110C)
    private val Sprocket = Color(0xFF2C2A26)

    /** One side of a lightning-split card: [right] is the part right of the bolt. */
    fun boltSide(width: Float, height: Float, right: Boolean) = Path().apply {
        moveTo(if (right) width else 0f, 0f)
        RowArt.bolt.forEach { (x, y) -> lineTo(x * width, y * height) }
        lineTo(if (right) width else 0f, height)
        close()
    }

    /** The marked (monochrome) side of a Cressida card, bounded by a gently bowed seam. */
    fun cressidaMarked(width: Float, height: Float) = Path().apply {
        val s = RowArt.CRESSIDA_SPLIT
        val b = RowArt.CRESSIDA_BOW
        moveTo(0f, 0f)
        lineTo(width * s, 0f)
        cubicTo(width * (s + b), height * 0.3f, width * (s - b), height * 0.65f, width * s, height)
        lineTo(0f, height)
        close()
    }
}

/** Sprocket holes along the top and bottom of a piece of film. */
fun DrawScope.drawSprockets(holeWidth: Float, holeHeight: Float, pitch: Float, margin: Float) {
    var x = pitch / 2 - holeWidth / 2
    while (x < size.width) {
        drawRoundRect(Color(0xFF2C2A26), Offset(x, margin), Size(holeWidth, holeHeight), CornerRadius(holeHeight / 3))
        drawRoundRect(Color(0xFF2C2A26), Offset(x, size.height - margin - holeHeight), Size(holeWidth, holeHeight), CornerRadius(holeHeight / 3))
        x += pitch
    }
}

/** Fine scratches and darkened edges over a frame, like a much-loved print. */
fun DrawScope.drawWornFrame() {
    listOf(0.17f, 0.52f, 0.81f).forEach { f -> drawLine(Color.White.copy(alpha = 0.16f), Offset(size.width * f, 0f), Offset(size.width * (f + 0.02f), size.height), 1f) }
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x88000000)), radius = size.width * 0.75f))
}

/** The bolt itself: a glow in [glow], then a white-hot core with sparks at the corners. */
fun DrawScope.drawBolt(glow: Color, highlightRight: Boolean? = null) {
    val bolt = Path().apply {
        RowArt.bolt.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
    }
    val scale = size.minDimension / 600f
    listOf(40f to 0.10f, 22f to 0.22f, 10f to 0.55f).forEach { (w, al) ->
        drawPath(bolt, glow.copy(alpha = al), style = Stroke(w * scale.coerceAtLeast(0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(bolt, Color.White, style = Stroke(4f * scale.coerceAtLeast(0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    RowArt.bolt.drop(1).dropLast(1).forEach { (x, y) ->
        val c = Offset(x * size.width, y * size.height)
        repeat(5) { k ->
            val ang = (k * 72 + 20) * PI / 180
            val len = 18f * scale.coerceAtLeast(0.6f)
            drawLine(Color.White.copy(alpha = 0.7f), c, c + Offset((cos(ang) * len).toFloat(), (sin(ang) * len).toFloat()), 2f, StrokeCap.Round)
        }
    }
    // Dim the side not chosen (TV focus), so the chosen one reads as lit.
    if (highlightRight != null) drawPath(RowArtDrawing.boltSide(size.width, size.height, !highlightRight), Color(0x77000000))
}

/** Cressida's ink: a vine down the seam with leaves either side, spirals and a dotted crown. */
fun DrawScope.drawCressidaInk(ink: Color) {
    val split = RowArt.CRESSIDA_SPLIT
    // Sized to the card: the concept was drawn on a ~1150px-tall card.
    val unit = size.height / 1150f
    val stroke = Stroke(2.2f * unit, cap = StrokeCap.Round)
    val vine = Path().apply {
        moveTo(size.width * split, -10f)
        cubicTo(size.width * (split + 0.10f), size.height * 0.28f, size.width * (split - 0.12f), size.height * 0.62f, size.width * (split + 0.02f), size.height + 10f)
    }
    drawPath(vine, ink, style = Stroke(3f * unit, cap = StrokeCap.Round))
    for (i in 1..9) {
        val t = i / 10f
        val y = size.height * t
        val x = size.width * (split + 0.05f * sin(t * 2 * PI).toFloat())
        val side = if (i % 2 == 0) 1f else -1f
        rotate(side * 35f + (if (side > 0) 0f else 180f), Offset(x, y)) {
            drawOval(ink, Offset(x, y - 7f * unit), Size(46f * unit, 14f * unit), style = stroke)
            drawLine(ink, Offset(x, y), Offset(x + 40f * unit, y), 1.2f * unit)
        }
    }
    listOf(Offset(0.18f, 0.22f) to 34f, Offset(0.30f, 0.48f) to 22f, Offset(0.14f, 0.70f) to 28f).forEach { (f, r0) ->
        val c = Offset(size.width * f.x, size.height * f.y)
        val r = r0 * unit
        val p = Path()
        for (k in 0..120) {
            val ang = k / 120f * 4 * PI
            val rr = r * k / 120f
            val pt = c + Offset((cos(ang) * rr).toFloat(), (sin(ang) * rr).toFloat())
            if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        drawPath(p, ink, style = stroke)
    }
    for (row in 0..3) for (col in 0..8) drawCircle(ink.copy(alpha = 0.7f), 2.2f * unit, Offset((16f + col * 26f + (row % 2) * 13f) * unit, (18f + row * 16f) * unit))
}
