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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.hidesDescription

/**
 * The caption on a full-screen picture (Showcase, Full Showcase, full-art pages), laid out like the
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
) {
    val prefs = LocalDisplayPrefs.current
    val align = if (end) Alignment.End else Alignment.Start
    val textAlign = if (end) TextAlign.End else TextAlign.Start
    Column(modifier.widthIn(max = 620.dp), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
        if (logoUrl != null && prefs.logos) {
            AsyncImage(
                logoUrl, item.displayTitle, contentScale = ContentScale.Fit,
                alignment = if (end) Alignment.BottomEnd else Alignment.BottomStart,
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 340.dp).heightIn(max = 84.dp),
            )
        } else {
            Text(item.displayTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
        }
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (showDetails) ItemMetaLine(item, showRatings = showRatings)
        groupStanding(item)?.let { st ->
            Text("👥 ${st.long}", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                color = if (st.catchUp) Color(0xFFFFC94D) else Color(0xDDFFFFFF))
        }
        if (showOverview && prefs.overview && !prefs.hidesDescription(item)) item.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.8f), textAlign = textAlign, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The screensaver's shade along the bottom of a full-screen picture, under its caption. */
@Composable
fun ArtCaptionShade(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
}
