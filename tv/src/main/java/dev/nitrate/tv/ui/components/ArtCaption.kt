package dev.nitrate.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.CaptionSize

/**
 * The caption on a full-screen picture (rows one at a time, Artwork with its cards hidden, full-art pages), laid out like the
 * screensaver's: a small label in capitals (the row, and where in it), the title in bold (or its logo,
 * kept modest), then a quiet line or two, in a bottom corner, [end] = the right one. Pages alternate
 * the side from one title to the next, so nothing sits in one place and the picture shows.
 */
@Composable
fun ArtCaption(
    item: MediaItem,
    label: String?,
    end: Boolean,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true,
    showRatings: Boolean = true,
    showOverview: Boolean = true,
    /** The row's own caption size, else the page's. */
    size: CaptionSize = LocalDisplayPrefs.current.tvCaptionSize,
    compact: Boolean = size != CaptionSize.NORMAL,
    /** Minimal: the logo (passed as [logoUrl] when the title has one) and facts, never a description. */
    minimal: Boolean = size == CaptionSize.MINIMAL,
) {
    val prefs = LocalDisplayPrefs.current
    val align = if (end) Alignment.End else Alignment.Start
    val textAlign = if (end) TextAlign.End else TextAlign.Start
    // The caption box (`DisplayPrefs.tvCaptionBox`): the words on a dark, rounded box instead of a shade.
    val boxed = LocalDisplayPrefs.current.tvCaptionBox
    Column(
        modifier.then(if (boxed) Modifier.background(CaptionBoxColor, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp) else Modifier)
            .widthIn(max = captionWidth(compact)),
        horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        label?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
        if (logoUrl != null && prefs.logos) {
            AsyncImage(
                logoUrl, item.displayTitle, contentScale = ContentScale.Fit,
                alignment = if (end) Alignment.BottomEnd else Alignment.BottomStart,
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = if (compact) 220.dp else 300.dp).heightIn(max = if (compact) 56.dp else 76.dp),
            )
        } else {
            Text(item.displayTitle, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
        }
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (showDetails) ItemMetaLine(item, showRatings = showRatings)
        groupStanding(item)?.let { st ->
            // The user's word in bold, then each partner's ("Behind · Kevin 2 ahead · Kim 1 behind").
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(androidx.compose.ui.text.AnnotatedString(st.word, androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)))
                    if (st.partners.isNotEmpty()) append("  ·  " + st.partners)
                },
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st.catchUp) Color(0xFFFFC94D) else Color(0xDDFFFFFF),
            )
        }
        if (!compact && !minimal && showOverview && prefs.overview && !dev.nitrate.tv.ui.partners.descriptionHidden(item)) item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.8f), textAlign = textAlign, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The logo a caption shows: always with the Minimal caption, else as [wanted]. */
@Composable
fun captionLogo(repository: dev.nitrate.core.MediaServer, item: MediaItem, wanted: Boolean = false, size: CaptionSize = LocalDisplayPrefs.current.tvCaptionSize): String? =
    if (wanted || size == CaptionSize.MINIMAL) repository.logoUrl(item) else null

/** How wide a caption may run: kept narrow so the picture shows. */
fun captionWidth(compact: Boolean): Dp = if (compact) 260.dp else 340.dp

/**
 * The screensaver's shade under a caption: along the bottom of a full-screen picture by default, or
 * along the [top]; [side] (start/end, null = the whole width) adds a shade from that edge for a caption
 * set beside a column of cards.
 */
@Composable
fun ArtCaptionShade(modifier: Modifier = Modifier, top: Boolean = false, side: Boolean? = null) {
    // With the caption box the words carry their own contrast: the picture stays whole.
    if (LocalDisplayPrefs.current.tvCaptionBox) return
    val dark = Color.Black.copy(alpha = 0.75f)
    val brush = if (top) Brush.verticalGradient(0f to dark, 0.45f to Color.Transparent, 1f to Color.Transparent)
    else Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to dark)
    Box(modifier.fillMaxSize().background(brush))
    side?.let { end ->
        val b = if (end) Brush.horizontalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to dark.copy(alpha = 0.6f))
        else Brush.horizontalGradient(0f to dark.copy(alpha = 0.6f), 0.4f to Color.Transparent, 1f to Color.Transparent)
        Box(modifier.fillMaxSize().background(b))
    }
}

/** The caption box's colour: dark enough for white words over any picture, the picture still seen through it. */
val CaptionBoxColor = Color(0xA6000000)
