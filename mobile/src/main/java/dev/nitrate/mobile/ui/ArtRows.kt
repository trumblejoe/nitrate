package dev.nitrate.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nitrate.android.art.RowArtDrawing
import dev.nitrate.android.art.drawBolt
import dev.nitrate.android.art.drawCressidaInk
import dev.nitrate.android.art.drawSprockets
import dev.nitrate.android.art.drawWornFrame
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.facts
import dev.nitrate.core.rows.RowArt
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The widest picture for an item: backdrop, then thumb, then its poster. */
private fun AppActions.artwork(item: MediaItem) =
    server.backdropUrl(item) ?: server.imageUrl(item, ImageKind.THUMB, 720) ?: server.imageUrl(item, maxHeight = 720)

private fun playLabel(item: MediaItem) = if (item.kind == ItemKind.SERIES) "Watch" else if (item.user.positionMs > 0) "Resume" else "Play"

/** Turns content on its side, reading upwards, and takes up the turned size. */
private fun Modifier.sideways() = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
}.rotate(-90f)

// ---- Film strip ----

/**
 * The row as a tilted strip of film running off both edges. Frames scroll with the strip and
 * snap; the one in the middle (the "gate") is lit and named underneath with Play.
 */
@Composable
fun FilmStripRow(row: MediaRow, actions: AppActions) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val gate by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }?.index ?: 0
        }
    }
    val prefs = LocalDisplayPrefs.current
    val accent = accentColor()
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(236.dp)) {
            val frameWidth = 224.dp
            // Wide enough that the tilted strip still runs off both edges.
            val stripWidth = maxWidth + 160.dp
            Box(Modifier.align(Alignment.Center).requiredWidth(stripWidth).height(196.dp).rotate(-4f).background(RowArtDrawing.FilmBase)) {
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(horizontal = (stripWidth - frameWidth) / 2),
                    flingBehavior = rememberSnapFlingBehavior(state),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(row.items, key = { _, it -> it.id }) { i, item ->
                        val inGate = i == gate
                        Box(
                            Modifier.width(frameWidth).fillMaxHeight()
                                .pressable({ if (inGate) actions.open(item, "${row.key}-${item.id}") else scope.launch { state.animateScrollToItem(i) } }),
                        ) {
                            Canvas(Modifier.fillMaxSize()) { drawSprockets(14.dp.toPx(), 10.dp.toPx(), 24.dp.toPx(), 8.dp.toPx()) }
                            Column(Modifier.padding(top = 30.dp)) {
                                Box(Modifier.padding(horizontal = 7.dp).fillMaxWidth().height(118.dp).clip(RoundedCornerShape(3.dp)).alpha(if (inGate) 1f else 0.55f)) {
                                    AsyncImage(actions.artwork(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().sharedArtwork("${row.key}-${item.id}"))
                                    Canvas(Modifier.fillMaxSize()) { drawWornFrame() }
                                }
                                Text(
                                    "${24 + i * 2}  ▸  ${item.displayTitle.uppercase()}",
                                    color = accent.copy(alpha = if (inGate) 0.95f else 0.5f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 2.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        // What's in the gate.
        row.items.getOrNull(gate)?.let { item ->
            Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.displayTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(item.episodeLabel) + prefs.facts(item).take(4)
                if (line.isNotEmpty()) Text(line.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    PillButton(playLabel(item), Icons.Default.PlayArrow, primary = true, onClick = { actions.playOrOpen(item) })
                    PillButton("Details", Icons.Default.Info, primary = false, onClick = { actions.open(item, "${row.key}-${item.id}") })
                }
            }
        }
    }
}

// ---- Lightning split ----

/** Two titles to a card, split by a glowing bolt: tap the side you want. */
@Composable
fun LightningRow(row: MediaRow, actions: AppActions) {
    val state = rememberLazyListState()
    val pairs = remember(row.items) { row.items.chunked(2) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - ScreenPadding * 2).coerceAtMost(520.dp)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), flingBehavior = rememberSnapFlingBehavior(state)) {
            items(pairs, key = { p -> p.joinToString { it.id } }) { pair ->
                if (pair.size == 2) DoubleBill(pair[0], pair[1], actions, Modifier.width(cardWidth))
                else SingleBill(pair[0], actions, Modifier.width(cardWidth))
            }
        }
    }
}

@Composable
private fun DoubleBill(left: MediaItem, right: MediaItem, actions: AppActions, modifier: Modifier) {
    val accent = accentColor()
    Box(
        modifier.aspectRatio(0.92f).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.06f))
            .pointerInput(left.id, right.id) {
                detectTapGestures { pos ->
                    val onRight = pos.x / size.width > RowArt.boltX(pos.y / size.height)
                    if (onRight) actions.open(right) else actions.open(left)
                }
            },
    ) {
        AsyncImage(actions.artwork(left), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(RowArtDrawing.boltSide(s.width, s.height, false)) }))
        AsyncImage(actions.artwork(right), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(RowArtDrawing.boltSide(s.width, s.height, true)) }))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xAA000000)))))
        Canvas(Modifier.fillMaxSize()) { drawBolt(accent) }
        Column(Modifier.align(Alignment.TopStart).padding(20.dp).fillMaxWidth(0.55f)) {
            Text(left.displayTitle.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(left.episodeLabel ?: left.productionYear?.toString()).joinToString(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(20.dp).fillMaxWidth(0.55f), horizontalAlignment = Alignment.End) {
            Text(right.displayTitle.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(right.episodeLabel ?: right.productionYear?.toString()).joinToString(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

/** The odd one out at the end of a lightning row: a whole card to itself. */
@Composable
private fun SingleBill(item: MediaItem, actions: AppActions, modifier: Modifier) {
    Box(modifier.aspectRatio(0.92f).clip(RoundedCornerShape(24.dp)).pressable({ actions.open(item) })) {
        AsyncImage(actions.artwork(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(item.displayTitle.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PillButton(playLabel(item), Icons.Default.PlayArrow, primary = true, onClick = { actions.playOrOpen(item) }, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ---- Cressida ----

/** Tall marked cards: half colour, half monochrome under ink, the title up the edge. */
@Composable
fun CressidaRow(row: MediaRow, actions: AppActions, ink: dev.nitrate.core.rows.CressidaInk = dev.nitrate.core.rows.CressidaInk.IVY) {
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - ScreenPadding * 2).coerceAtMost(520.dp)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), flingBehavior = rememberSnapFlingBehavior(state)) {
            items(row.items, key = { it.id }) { item -> CressidaCard(item, actions, "${row.key}-${item.id}", ink, Modifier.width(cardWidth)) }
        }
    }
}

@Composable
fun CressidaCard(item: MediaItem, actions: AppActions, sharedKey: String, ink: dev.nitrate.core.rows.CressidaInk, modifier: Modifier) {
    val prefs = LocalDisplayPrefs.current
    val accent = accentColor()
    val art = actions.artwork(item)
    BoxWithConstraints(modifier.height(440.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.06f)).pressable({ actions.open(item, sharedKey) })) {
        // Narrow cards (small phones, galleries) get a round play button instead of a pill.
        val compact = maxWidth < 300.dp
        AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().sharedArtwork(sharedKey))
        AsyncImage(
            art, null, contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = Modifier.fillMaxSize().clip(GenericShape { s, _ -> addPath(RowArtDrawing.cressidaMarked(s.width, s.height)) }),
        )
        Box(Modifier.fillMaxHeight().fillMaxWidth(RowArt.CRESSIDA_SPLIT).background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color(0x22000000)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA000000))))
        Canvas(Modifier.fillMaxSize()) { drawCressidaInk(accent, ink) }
        Text(
            item.displayTitle.uppercase(),
            color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 18.dp).sideways(),
        )
        Column(Modifier.align(Alignment.BottomEnd).padding(18.dp).fillMaxWidth(0.5f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.episodeLabel?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge) }
            val facts = prefs.facts(item).take(3)
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (compact) {
                Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent).pressable({ actions.playOrOpen(item) }), Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, playLabel(item), tint = contentOn(accent))
                }
            } else {
                PillButton(playLabel(item), Icons.Default.PlayArrow, primary = true, onClick = { actions.playOrOpen(item) })
            }
        }
    }
}
