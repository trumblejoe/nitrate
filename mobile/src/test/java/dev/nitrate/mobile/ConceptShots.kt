package dev.nitrate.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.imageDecoderEnabled
import com.github.takahirom.roborazzi.captureRoboImage
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import dev.nitrate.mobile.ui.AppActions
import dev.nitrate.mobile.ui.CinematicRow
import dev.nitrate.mobile.ui.NitrateTheme
import dev.nitrate.mobile.ui.ProvideDisplayPrefs
import dev.nitrate.mobile.ui.Wordmark
import dev.nitrate.testing.FakeServer
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Design concepts only (not in the app): Home with Continue Watching first, then one bold idea.
 * Rendered tall so the whole idea fits: build/outputs/roborazzi/concept-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1180dp-night-xxhdpi", application = android.app.Application::class)
class ConceptShots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ink = Color(0xFF0B0C10)
    private val amber = Color(0xFFE8A23A)

    private fun shoot(name: String, content: @Composable ColumnScope.(AppActions, List<MediaRow>) -> Unit) {
        val container = AppContainer(compose.activity, "test", "0", serverOverride = FakeServer())
        kotlinx.coroutines.runBlocking { container.displaySettings.import("user-1", emptyMap()) }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity).coroutineContext(Dispatchers.Unconfined).fetcherCoroutineContext(Dispatchers.Unconfined)
                .decoderCoroutineContext(Dispatchers.Unconfined).imageDecoderEnabled(false).build(),
        )
        compose.setContent {
            ProvideDisplayPrefs(container, "user-1") {
                NitrateTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        val actions = AppActions(container, rememberNavController(), play = { _, _ -> })
                        val rows by produceState(emptyList<MediaRow>()) { value = container.server.homeRows() }
                        Column(Modifier.fillMaxSize().background(ink)) {
                            Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 22.dp, bottom = 8.dp)) { Wordmark() }
                            rows.firstOrNull { it.key == "home/continue_watching" }?.let { CinematicRow(it, RowDisplay(RowStyle.WIDE, showTitles = true), actions) }
                            Spacer(Modifier.height(30.dp))
                            if (rows.isNotEmpty()) content(actions, rows)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        Thread.sleep(400)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/concept-$name.png")
    }

    private fun films(rows: List<MediaRow>) = rows.first { it.key == "home/latest/lib-movies" }.items
    private fun art(a: AppActions, item: MediaItem) = a.server.backdropUrl(item) ?: a.server.imageUrl(item, ImageKind.PRIMARY, 720)

    @Composable
    private fun Label(text: String, modifier: Modifier = Modifier) =
        Text(text, modifier.padding(horizontal = 18.dp), color = amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)

    // ---- 1. Film strip ----

    @Test
    fun filmStrip() = shoot("1-film-strip") { a, rows ->
        val list = films(rows)
        Label("LATEST · REEL 01 · NITRATE SAFETY FILM")
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            // A strip of film, tilted, running off both edges.
            Box(Modifier.requiredWidth(900.dp).height(200.dp).align(Alignment.Center).rotate(-5f).background(Color(0xFF14110C))) {
                Canvas(Modifier.fillMaxSize()) {
                    val hole = Size(14.dp.toPx(), 10.dp.toPx())
                    var x = 6.dp.toPx()
                    while (x < size.width) {
                        drawRoundRect(Color(0xFF2C2A26), Offset(x, 7.dp.toPx()), hole, CornerRadius(3.dp.toPx()))
                        drawRoundRect(Color(0xFF2C2A26), Offset(x, size.height - 17.dp.toPx()), hole, CornerRadius(3.dp.toPx()))
                        x += 24.dp.toPx()
                    }
                }
                Row(Modifier.align(Alignment.Center).offset(x = 120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    list.forEachIndexed { i, film ->
                        Column {
                            Box(Modifier.width(210.dp).height(118.dp).clip(RoundedCornerShape(3.dp))) {
                                AsyncImage(art(a, film), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                // Scratches and burn at the edges, like a much-loved print.
                                Canvas(Modifier.fillMaxSize()) {
                                    listOf(0.17f, 0.52f, 0.81f).forEach { f -> drawLine(Color.White.copy(alpha = 0.18f), Offset(size.width * f, 0f), Offset(size.width * (f + 0.02f), size.height), 1f) }
                                    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x99000000)), radius = size.width * 0.75f))
                                }
                            }
                            Text("${24 + i * 2}  ▸  ${film.name.uppercase()}", color = amber.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(list[1].name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 18.dp))
        Text("Scrub the strip to change frame. The one in the gate plays.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 18.dp))
    }

    // ---- 2. Lightning split ----

    private val boltPoints = listOf(0.60f to 0f, 0.43f to 0.34f, 0.61f to 0.38f, 0.36f to 0.70f, 0.55f to 0.73f, 0.41f to 1f)

    private fun boltPath(w: Float, h: Float, rightSide: Boolean) = Path().apply {
        if (rightSide) {
            moveTo(w, 0f)
            boltPoints.forEach { (x, y) -> lineTo(x * w, y * h) }
            lineTo(w, h)
        } else {
            moveTo(0f, 0f)
            boltPoints.forEach { (x, y) -> lineTo(x * w, y * h) }
            lineTo(0f, h)
        }
        close()
    }

    @Test
    fun lightning() = shoot("2-lightning-split") { a, rows ->
        val list = films(rows)
        val left = list[0]
        val right = list[1]
        Label("DOUBLE BILL · PICK A SIDE")
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().aspectRatio(0.82f).clip(RoundedCornerShape(26.dp))) {
            AsyncImage(art(a, left), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(boltPath(s.width, s.height, false)) }))
            AsyncImage(art(a, right), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(boltPath(s.width, s.height, true)) }))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent, Color(0x99000000)))))
            Canvas(Modifier.fillMaxSize()) {
                val bolt = Path().apply {
                    boltPoints.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
                }
                // Glow, then a white-hot core.
                listOf(40f to 0.10f, 22f to 0.22f, 10f to 0.55f).forEach { (w, al) -> drawPath(bolt, amber.copy(alpha = al), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                drawPath(bolt, Color.White, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // Sparks off the corners.
                boltPoints.drop(1).dropLast(1).forEach { (x, y) ->
                    val c = Offset(x * size.width, y * size.height)
                    repeat(5) { k ->
                        val ang = (k * 72 + 20) * PI / 180
                        drawLine(Color.White.copy(alpha = 0.7f), c, c + Offset((cos(ang) * 18).toFloat(), (sin(ang) * 18).toFloat()), 2f, StrokeCap.Round)
                    }
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                Text(left.name.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("${left.productionYear} · tap this side", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.End) {
                Text(right.name.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("${right.productionYear} · or this one", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
        }
        Text("Swipe the bolt left or right to give one film more of the screen.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(18.dp))
    }

    // ---- 3. Cressida: half colour, half monochrome under tattoo linework ----

    @Test
    fun cressida() = shoot("3-cressida") { a, rows ->
        val film = films(rows)[1]
        Label("FEATURED · MARKED")
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(470.dp)) {
            val split = 0.47f
            AsyncImage(art(a, film), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // The marked side: drained of colour and darkened.
            AsyncImage(
                art(a, film), null, contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ ->
                    moveTo(0f, 0f); lineTo(s.width * split, 0f)
                    cubicTo(s.width * (split + 0.08f), s.height * 0.3f, s.width * (split - 0.08f), s.height * 0.65f, s.width * split, s.height)
                    lineTo(0f, s.height); close()
                }),
            )
            Box(Modifier.fillMaxHeight().fillMaxWidth(split).background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x22000000)))))
            Canvas(Modifier.fillMaxSize()) {
                val gold = Color(0xFFD9B26A)
                val stroke = Stroke(2.2f, cap = StrokeCap.Round)
                // A vine down the seam, leaves either side, curling past it.
                val vine = Path().apply {
                    moveTo(size.width * split, -10f)
                    cubicTo(size.width * (split + 0.10f), size.height * 0.28f, size.width * (split - 0.12f), size.height * 0.62f, size.width * (split + 0.02f), size.height + 10f)
                }
                drawPath(vine, gold, style = Stroke(3f, cap = StrokeCap.Round))
                for (i in 1..9) {
                    val t = i / 10f
                    val y = size.height * t
                    val x = size.width * (split + 0.05f * sin(t * 2 * PI).toFloat())
                    val side = if (i % 2 == 0) 1f else -1f
                    rotate(side * 35f + (if (side > 0) 0f else 180f), Offset(x, y)) {
                        drawOval(gold, Offset(x, y - 7f), Size(46f, 14f), style = stroke)
                        drawLine(gold, Offset(x, y), Offset(x + 40f, y), 1.2f)
                    }
                }
                // Spirals, like ink fading into the hair line.
                listOf(Offset(size.width * 0.18f, size.height * 0.22f) to 34f, Offset(size.width * 0.30f, size.height * 0.48f) to 22f, Offset(size.width * 0.14f, size.height * 0.70f) to 28f).forEach { (c, r) ->
                    val p = Path()
                    for (k in 0..120) {
                        val ang = k / 120f * 4 * PI
                        val rr = r * k / 120f
                        val pt = c + Offset((cos(ang) * rr).toFloat(), (sin(ang) * rr).toFloat())
                        if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                    }
                    drawPath(p, gold, style = stroke)
                }
                // Dotted scale pattern along the top of the marked side.
                for (row in 0..3) for (col in 0..8) drawCircle(gold.copy(alpha = 0.7f), 2.2f, Offset(16f + col * 26f + (row % 2) * 13f, 18f + row * 16f))
            }
            // Title set up the edge, tall and narrow.
            Text(
                film.name.uppercase(),
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 18.dp).sideways(),
            )
            Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.End) {
                Text("${film.productionYear} · PG-13 · 1h 56m", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("▶  Play", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFD9B26A)).padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }

    /** Turns content on its side (reading upwards), taking up the turned size. */
    private fun Modifier.sideways() = layout { measurable, _ ->
        val p = measurable.measure(androidx.compose.ui.unit.Constraints())
        layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
    }.rotate(-90f)

    // ---- 4. Marquee and ticket stubs ----

    @Test
    fun marquee() = shoot("4-marquee") { a, rows ->
        val list = films(rows)
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(330.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFF7A1C17), Color(0xFF3B0D0B))))) {
            Canvas(Modifier.fillMaxSize()) {
                // Chasing bulbs round the frame, every third one lit brighter.
                val step = 22.dp.toPx()
                val inset = 10.dp.toPx()
                val w = size.width
                val h = size.height
                val pts = buildList {
                    var x = inset; while (x <= w - inset) { add(Offset(x, inset)); add(Offset(x, h - inset)); x += step }
                    var y = inset + step; while (y < h - inset) { add(Offset(inset, y)); add(Offset(w - inset, y)); y += step }
                }
                pts.forEachIndexed { i, p ->
                    val lit = i % 3 == 0
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE6A8).copy(alpha = if (lit) 0.8f else 0.35f), Color.Transparent), center = p, radius = 16f), 16f, p)
                    drawCircle(if (lit) Color(0xFFFFF4D6) else Color(0xFFE8B866), 4.5f, p)
                }
            }
            Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Letterboard: white letters on a ribbed black board.
                Box(Modifier.fillMaxWidth().background(Color(0xFF111111)).border(2.dp, Color(0xFFE8B866))) {
                    Canvas(Modifier.matchParentSize()) { var y = 0f; while (y < size.height) { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f); y += 6f } }
                    Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW SHOWING", color = Color(0xFFFFE6A8), fontSize = 13.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Bold)
                        Text(list[0].name.uppercase() + "  ·  7:30", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace)
                        Text(list[2].name.uppercase() + "  ·  9:45", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    listOf(list[0], list[2]).forEach { f ->
                        AsyncImage(art(a, f), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).border(2.dp, Color(0xFFE8B866), RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Label("NEXT UP · YOUR TICKETS")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val next = rows.firstOrNull { it.key == "home/next_up" }?.items.orEmpty() + list.drop(1).take(1)
            next.take(2).forEach { item -> Ticket(a, item, Modifier.weight(1f)) }
        }
    }

    /** "Admit one": art on the stub's left, perforation, details on the right. */
    @Composable
    private fun Ticket(a: AppActions, item: MediaItem, modifier: Modifier) {
        val paper = Color(0xFFF1E4C8)
        val notch = 9.dp
        val shape = GenericShape { s, _ ->
            val r = with(androidx.compose.ui.unit.Density(1f)) { 26f }
            val cut = s.width * 0.42f
            moveTo(0f, 0f); lineTo(cut - r, 0f)
            arcTo(androidx.compose.ui.geometry.Rect(cut - r, -r, cut + r, r), 180f, -180f, false)
            lineTo(s.width, 0f); lineTo(s.width, s.height); lineTo(cut + r, s.height)
            arcTo(androidx.compose.ui.geometry.Rect(cut - r, s.height - r, cut + r, s.height + r), 0f, -180f, false)
            lineTo(0f, s.height); close()
        }
        Box(modifier.height(118.dp).clip(shape).background(paper)) {
            Row(Modifier.fillMaxSize()) {
                AsyncImage(a.server.imageUrl(item, ImageKind.THUMB, 300) ?: art(a, item), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(0.42f).fillMaxHeight())
                Column(Modifier.weight(0.58f).fillMaxHeight().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("ADMIT ONE", color = Color(0xFF7A1C17), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text(item.displayTitle, color = Color(0xFF1B1408), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.episodeLabel ?: "${item.productionYear}", color = Color(0xFF5A4A30), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * 0.42f
                var y = notch.toPx() + 4f
                while (y < size.height - notch.toPx() - 4f) { drawCircle(Color(0xFF9C8A66), 1.6f, Offset(x, y)); y += 8f }
            }
        }
    }
}
