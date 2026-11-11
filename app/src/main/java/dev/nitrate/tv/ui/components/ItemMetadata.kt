package dev.nitrate.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Date

/** "Show name" for episodes, otherwise the item name. */
fun BaseItemDto.displayTitle(): String =
    (if (type == BaseItemKind.EPISODE) seriesName ?: name else name).orEmpty()

/** "S2:E5 · Episode name" for episodes, null otherwise. */
fun BaseItemDto.episodeLabel(): String? =
    if (type == BaseItemKind.EPISODE) "S${parentIndexNumber ?: "?"}:E${indexNumber ?: "?"} · ${name.orEmpty()}" else null

fun formatRuntime(ticks: Long): String {
    val minutes = ticks / 600_000_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** Year, runtime, ratings and the clock time it would finish if started now. */
@Composable
fun ItemMetaLine(item: BaseItemDto, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parts = buildList {
        if (item.type == BaseItemKind.SERIES) {
            val start = item.productionYear
            val end = item.endDate?.year
            if (start != null) add(if (end != null && end != start) "$start–$end" else "$start")
        } else {
            item.productionYear?.let { add(it.toString()) }
        }
        item.officialRating?.let { add(it) }
        item.runTimeTicks?.takeIf { item.type != BaseItemKind.SERIES }?.let { runtime ->
            add(formatRuntime(runtime))
            val remainingMs = (runtime - (item.userData?.playbackPositionTicks ?: 0L)) / 10_000
            val endsAt = Date(System.currentTimeMillis() + remainingMs)
            add("Ends at ${DateFormat.getTimeFormat(context).format(endsAt)}")
        }
        item.communityRating?.let { add("★ %.1f".format(it)) }
        item.criticRating?.let { add("${it.toInt()}% critics") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("   ·   "),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Logo image when the server has one, otherwise the title in display type. */
@Composable
fun TitleOrLogo(item: BaseItemDto, logoUrl: String?, modifier: Modifier = Modifier) {
    if (logoUrl != null) {
        AsyncImage(
            model = logoUrl,
            contentDescription = item.displayTitle(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = modifier.widthIn(max = 420.dp).heightIn(max = 130.dp),
        )
    } else {
        Text(
            item.displayTitle(),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

@Composable
fun GenreLine(item: BaseItemDto, modifier: Modifier = Modifier) {
    val genres = item.genres.orEmpty().take(4)
    if (genres.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(genres.joinToString("  /  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
