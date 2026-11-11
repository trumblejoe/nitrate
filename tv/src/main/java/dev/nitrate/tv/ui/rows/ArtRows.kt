package dev.nitrate.tv.ui.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.art.RowArtDrawing
import dev.nitrate.android.art.drawBolt
import dev.nitrate.android.art.drawCressidaInk
import dev.nitrate.android.art.drawSprockets
import dev.nitrate.android.art.drawWornFrame
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.facts
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowArt
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.onPlayKey

private fun MediaServer.artwork(item: MediaItem) = backdropUrl(item) ?: imageUrl(item, ImageKind.THUMB, 720) ?: imageUrl(item, ImageKind.PRIMARY, 720)

@Composable
private fun RowTitle(row: MediaRow) =
    Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = RowPadding, bottom = 12.dp))

/** A focus ring in the accent colour that follows [shape]. */
@Composable
private fun accentBorder(shape: Shape) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = shape),
)

private fun Modifier.sideways() = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
}.rotate(-90f)

// ---- Film strip ----

/** A tilted strip of film; the focused frame is in the gate, lit and named below. */
@Composable
fun FilmStripRow(row: MediaRow, repository: MediaServer, actions: RowActions) {
    var gate by remember(row.key) { mutableIntStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val prefs = LocalDisplayPrefs.current
    Column {
        RowTitle(row)
        BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp)) {
            Box(Modifier.align(Alignment.Center).requiredWidth(maxWidth + 200.dp).height(206.dp).rotate(-3f).background(RowArtDrawing.FilmBase)) {
                LazyRow(contentPadding = PaddingValues(start = RowPadding + 100.dp, end = 200.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(row.items, key = { _, it -> it.id }) { i, item ->
                        val inGate = i == gate
                        Box(Modifier.width(264.dp).fillMaxHeight()) {
                            Canvas(Modifier.fillMaxSize()) { drawSprockets(14.dp.toPx(), 10.dp.toPx(), 22.dp.toPx(), 8.dp.toPx()) }
                            Column(Modifier.padding(top = 30.dp, start = 12.dp, end = 12.dp)) {
                                val shape = RoundedCornerShape(3.dp)
                                Surface(
                                    onClick = { actions.onOpen(item) },
                                    onLongClick = { actions.onEditRow(row) },
                                    shape = ClickableSurfaceDefaults.shape(shape),
                                    border = accentBorder(shape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                                    modifier = Modifier.fillMaxWidth().height(135.dp).alpha(if (inGate) 1f else 0.55f)
                                        .onFocusChanged { if (it.isFocused) { gate = i; actions.onFocus(item) } }.onPlayKey { actions.onPlay(item) },
                                ) {
                                    AsyncImage(repository.artwork(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Canvas(Modifier.fillMaxSize()) { drawWornFrame() }
                                }
                                Text(
                                    "${24 + i * 2}  ▸  ${item.displayTitle.uppercase()}",
                                    color = accent.copy(alpha = if (inGate) 0.95f else 0.5f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        row.items.getOrNull(gate)?.let { item ->
            Column(Modifier.padding(start = RowPadding, top = 4.dp)) {
                Text(item.displayTitle, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(item.episodeLabel) + prefs.facts(item).take(4)
                if (line.isNotEmpty()) Text(line.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("OK for details  ·  ▶ to play", style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---- Lightning split ----

/** Two titles per card, split by a bolt: each side is its own stop for the D-pad. */
@Composable
fun LightningRow(row: MediaRow, repository: MediaServer, actions: RowActions) {
    Column {
        RowTitle(row)
        LazyRow(contentPadding = PaddingValues(horizontal = RowPadding), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(row.items.chunked(2), key = { p -> p.joinToString { it.id } }) { pair ->
                if (pair.size == 2) DoubleBill(row, pair[0], pair[1], repository, actions) else SingleBill(row, pair[0], repository, actions)
            }
        }
    }
}

/** The part of a card left or right of the bolt, for a box starting at [startFraction] and [widthFraction] wide. */
private fun boltShape(right: Boolean, startFraction: Float, widthFraction: Float) = GenericShape { s, _ ->
    val cardW = s.width / widthFraction
    val dx = -cardW * startFraction
    val path = RowArtDrawing.boltSide(cardW, s.height, right)
    path.translate(androidx.compose.ui.geometry.Offset(dx, 0f))
    addPath(path)
}

@Composable
private fun DoubleBill(row: MediaRow, left: MediaItem, right: MediaItem, repository: MediaServer, actions: RowActions) {
    var lit by remember { mutableStateOf<Boolean?>(null) } // true = right side focused
    val accent = MaterialTheme.colorScheme.primary
    // Each side's box spans from the card edge to the bolt's furthest point.
    val leftWidth = RowArt.bolt.maxOf { it.first }
    val rightStart = RowArt.bolt.minOf { it.first }
    Box(Modifier.size(640.dp, 340.dp).clip(RoundedCornerShape(20.dp))) {
        listOf(false to left, true to right).forEach { (isRight, item) ->
            val start = if (isRight) rightStart else 0f
            val width = if (isRight) 1f - rightStart else leftWidth
            val shape = boltShape(isRight, start, width)
            Surface(
                onClick = { actions.onOpen(item) },
                onLongClick = { actions.onEditRow(row) },
                shape = ClickableSurfaceDefaults.shape(shape),
                border = accentBorder(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier.align(if (isRight) Alignment.TopEnd else Alignment.TopStart).fillMaxHeight().fillMaxWidth(width)
                    .onFocusChanged { if (it.isFocused) { lit = isRight; actions.onFocus(item) } else if (lit == isRight) lit = null }
                    .onPlayKey { actions.onPlay(item) },
            ) {
                // The picture is laid out for the whole card, then this side shows its part.
                AsyncImage(
                    repository.artwork(item), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().layout { m, c ->
                        val cardW = (c.maxWidth / width).toInt()
                        val p = m.measure(Constraints.fixed(cardW, c.maxHeight))
                        layout(c.maxWidth, c.maxHeight) { p.place((-cardW * start).toInt(), 0) }
                    },
                )
            }
        }
        // Above both sides, even the focused one (TV surfaces rise when focused).
        Box(Modifier.fillMaxSize().zIndex(2f).background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent, Color(0x99000000)))))
        Canvas(Modifier.fillMaxSize().zIndex(2f)) { drawBolt(accent, highlightRight = lit) }
        Column(Modifier.align(Alignment.TopStart).zIndex(2f).padding(22.dp).fillMaxWidth(0.5f)) {
            Text(left.displayTitle.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(left.episodeLabel ?: left.productionYear?.toString().orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomEnd).zIndex(2f).padding(22.dp).fillMaxWidth(0.5f), horizontalAlignment = Alignment.End) {
            Text(right.displayTitle.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(right.episodeLabel ?: right.productionYear?.toString().orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun SingleBill(row: MediaRow, item: MediaItem, repository: MediaServer, actions: RowActions) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = { actions.onOpen(item) },
        onLongClick = { actions.onEditRow(row) },
        shape = ClickableSurfaceDefaults.shape(shape),
        border = accentBorder(shape),
        modifier = Modifier.size(640.dp, 340.dp).onFocusChanged { if (it.isFocused) actions.onFocus(item) }.onPlayKey { actions.onPlay(item) },
    ) {
        AsyncImage(repository.artwork(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000))))
        Text(item.displayTitle.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomStart).padding(22.dp))
    }
}

// ---- Cressida ----

/** Wide marked cards: half colour, half monochrome under ink, the title up the edge. */
@Composable
fun CressidaRow(row: MediaRow, repository: MediaServer, actions: RowActions, ink: dev.nitrate.core.rows.CressidaInk = dev.nitrate.core.rows.CressidaInk.IVY) {
    val accent = MaterialTheme.colorScheme.primary
    val prefs = LocalDisplayPrefs.current
    Column {
        RowTitle(row)
        LazyRow(contentPadding = PaddingValues(horizontal = RowPadding), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(row.items, key = { it.id }) { item ->
                val shape = RoundedCornerShape(20.dp)
                val art = repository.artwork(item)
                Surface(
                    onClick = { actions.onOpen(item) },
                    onLongClick = { actions.onEditRow(row) },
                    shape = ClickableSurfaceDefaults.shape(shape),
                    border = accentBorder(shape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    modifier = Modifier.size(600.dp, 340.dp).onFocusChanged { if (it.isFocused) actions.onFocus(item) }.onPlayKey { actions.onPlay(item) },
                ) {
                    AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    AsyncImage(
                        art, null, contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                        modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(RowArtDrawing.cressidaMarked(s.width, s.height)) }),
                    )
                    Box(Modifier.fillMaxHeight().fillMaxWidth(RowArt.CRESSIDA_SPLIT).background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x22000000)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA000000))))
                    Canvas(Modifier.fillMaxSize()) { drawCressidaInk(accent, ink) }
                    Text(
                        item.displayTitle.uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                        maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp).sideways(),
                    )
                    Column(Modifier.align(Alignment.BottomEnd).padding(20.dp).fillMaxWidth(0.48f), horizontalAlignment = Alignment.End) {
                        item.episodeLabel?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall) }
                        val facts = prefs.facts(item).take(3)
                        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
