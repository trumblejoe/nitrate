package dev.nitrate.mobile.ui

import dev.nitrate.core.pages.facts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowStyle

val ScreenPadding = 16.dp

/** Card pictures' height in pixels (see [cardImage]). */
const val CARD_IMAGE_PX = 540

enum class CardShape(val ratio: Float) { POSTER(2f / 3f), WIDE(16f / 9f), SQUARE(1f) }

/**
 * The image a card of [shape] should show for [item], with sensible fallbacks. [height] is in
 * pixels: the default suits a poster card on a high-density phone (about 175 dp tall at 3×), so
 * the picture is never stretched up and soft.
 */
fun MediaServer.cardImage(item: MediaItem, shape: CardShape, height: Int = CARD_IMAGE_PX): String? = when (shape) {
    CardShape.WIDE -> imageUrl(item, ImageKind.THUMB, height) ?: backdropUrl(item, 720) ?: imageUrl(item, ImageKind.PRIMARY, height)
    else -> imageUrl(item, ImageKind.PRIMARY, height)
}

/** An artwork card with watched tick and progress bar; [width] null fills the parent (grids). */
@Composable
fun MediaCard(
    item: MediaItem,
    imageUrl: String?,
    shape: CardShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    title: String? = null,
    subtitle: String? = null,
    /** Lets the artwork morph into the page this card opens. */
    sharedKey: String? = null,
    /** Holding the card (the "Watching together" row's choices). */
    onLongClick: (() -> Unit)? = null,
) {
    val prefs = LocalDisplayPrefs.current
    // Titles follow the row unless the user always or never wants them.
    val shownTitle = when (prefs.cardTitles) {
        dev.nitrate.core.pages.CardTitles.NEVER -> null
        dev.nitrate.core.pages.CardTitles.ALWAYS -> title ?: item.displayTitle
        dev.nitrate.core.pages.CardTitles.BY_ROW -> title
    }
    val shownSubtitle = subtitle.takeIf { prefs.cardTitles != dev.nitrate.core.pages.CardTitles.NEVER }
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).pressable(onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(shape.ratio)
                .sharedArtwork(sharedKey)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    imageUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (spoilerStill(item)) Modifier.blur(16.dp) else Modifier),
                )
            } else {
                Text(item.name, Modifier.align(Alignment.Center).padding(8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 3)
            }
            val progress = item.user.playedPercentage?.toFloat()?.div(100f)?.takeIf { prefs.progress != dev.nitrate.core.pages.ProgressStyle.NONE }
            if (progress != null && progress > 0f) {
                if (prefs.progress == dev.nitrate.core.pages.ProgressStyle.CIRCLE) {
                    // A small ring in the corner.
                    val ring = accentColor()
                    androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp)) {
                        val stroke = 3.dp.toPx()
                        drawCircle(Color(0xB0000000))
                        val inset = stroke / 2 + 2.dp.toPx()
                        val arc = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                        val at = androidx.compose.ui.geometry.Offset(inset, inset)
                        drawArc(Color(0x55FFFFFF), 0f, 360f, false, at, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        drawArc(ring, -90f, 360f * progress.coerceIn(0f, 1f), false, at, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                } else Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(accentColor()))
                }
            }
            if (prefs.qualityBadges) {
                // The most notable quality label, e.g. 4K or Dolby Vision.
                item.badges.firstOrNull { it == "4K" || it == "Dolby Vision" || it.startsWith("HDR") }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (prefs.unwatchedCounts && !item.user.played) {
                item.user.unplayedCount?.takeIf { it > 0 && item.kind == ItemKind.SERIES }?.let {
                    Text(
                        "$it",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentOn(accentColor()),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(accentColor(), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (item.user.played && prefs.watchedMarks) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(50)).padding(3.dp).size(14.dp),
                )
            }
        }
        if (shownTitle != null) {
            Text(shownTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (shownSubtitle != null) {
            Text(shownSubtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A titled horizontal row of cards, sized from the row's display style. */
@Composable
fun CardRow(row: MediaRow, style: RowStyle, server: MediaServer, onOpen: (MediaItem) -> Unit) {
    // Phones show showcase rows as wide cards; the full-screen showcase is a TV layout.
    val shape = if (style == RowStyle.POSTER) CardShape.POSTER else CardShape.WIDE
    val width = if (shape == CardShape.WIDE) 240.dp else 120.dp
    Column {
        SectionTitle(row.title)
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(row.items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    imageUrl = server.cardImage(item, shape),
                    shape = shape,
                    width = width,
                    title = if (shape == CardShape.WIDE) item.displayTitle else null,
                    subtitle = if (shape == CardShape.WIDE) item.episodeLabel ?: item.productionYear?.toString() else null,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 8.dp))
}

/** Year, rating, runtime and ratings in one line. */
fun MediaItem.metaLine(): String = buildList {
    if (kind == ItemKind.SERIES) {
        productionYear?.let { start -> add(if (endYear != null && endYear != start) "$start–$endYear" else "$start") }
    } else {
        productionYear?.let { add(it.toString()) }
    }
    officialRating?.let { add(it) }
    runtimeMs?.takeIf { kind != ItemKind.SERIES }?.let { add(formatRuntime(it)) }
    communityRating?.let { add("★ " + dev.nitrate.mobile.platform.formatDecimal(it.toDouble(), 1)) }
    criticRating?.let { add("${it.toInt()}% critics") }
}.joinToString("  ·  ")

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
