package dev.nitrate.tv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.rows.RowSettingsDialog

/** Space kept clear for the menu at each edge (so text never sits under it). */
data class MenuInsets(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp)

/**
 * Home as one picture at a time: the focused title fills the screen with its details in the
 * corner. Left/Right step through the row, Up/Down move between rows, OK opens, Play plays, and a
 * long press (or Menu) changes the row. At the first/last item or row the D-pad moves on to the
 * menu, wherever it is.
 */
@Composable
fun FullArtPage(
    rows: List<MediaRow>,
    overrides: Map<String, RowDisplay>,
    repository: MediaServer,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSaveDisplay: (MediaRow, RowDisplay) -> Unit,
    onEditPage: () -> Unit,
    insets: MenuInsets = MenuInsets(),
    modifier: Modifier = Modifier,
) {
    val shown = rows.filter { it.items.isNotEmpty() }
    if (shown.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here yet") }
        return
    }
    var rowIndex by remember { mutableIntStateOf(0) }
    val itemIndex = remember { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<MediaRow?>(null) }
    var longPressHandled by remember { mutableStateOf(false) }

    val row = shown[rowIndex.coerceIn(0, shown.lastIndex)]
    val current = (itemIndex[row.key] ?: 0).coerceIn(0, row.items.lastIndex)
    val item = row.items[current]
    val showcase = (overrides[row.key] ?: row.defaultDisplay).showcase

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionRight -> if (current < row.items.lastIndex) { if (down) itemIndex[row.key] = current + 1; true } else false
                    Key.DirectionLeft -> if (current > 0) { if (down) itemIndex[row.key] = current - 1; true } else false
                    Key.DirectionDown -> if (rowIndex < shown.lastIndex) { if (down) rowIndex++; true } else false
                    Key.DirectionUp -> if (rowIndex > 0) { if (down) rowIndex--; true } else false
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (down && event.nativeKeyEvent.repeatCount > 0 && !longPressHandled) {
                            longPressHandled = true
                            editing = row
                        } else if (!down) {
                            if (!longPressHandled) onOpen(item)
                            longPressHandled = false
                        }
                        true
                    }
                    Key.MediaPlay, Key.MediaPlayPause -> { if (!down) onPlay(item); true }
                    Key.Menu -> { if (!down) editing = row; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Backdrop(repository.backdropUrl(item), Modifier.fillMaxSize())

        val pad = Modifier.padding(start = 58.dp + insets.start, end = 58.dp + insets.end)
        // Which row this is, top left, as a quiet label.
        AnimatedContent(row.title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "row-title",
            modifier = Modifier.align(Alignment.TopStart).then(pad).padding(top = 36.dp + insets.top)) { title ->
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alpha(0.85f))
        }

        AnimatedContent(item, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "details",
            contentKey = { it.id },
            modifier = Modifier.align(Alignment.BottomStart).then(pad).padding(bottom = 48.dp + insets.bottom).fillMaxWidth(0.55f)) { it ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TitleOrLogo(it, if (showcase.showLogo) repository.logoUrl(it) else null)
                it.episodeLabel?.let { label -> Text(label, style = MaterialTheme.typography.titleMedium) }
                if (showcase.showDetails) ItemMetaLine(it, showRatings = showcase.showRatings)
                val prefs = LocalDisplayPrefs.current
                if (showcase.showOverview && prefs.overview && !prefs.isSpoiler(it)) {
                    it.overview?.let { text ->
                        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text(
            "${current + 1} / ${row.items.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).then(pad).padding(bottom = 48.dp + insets.bottom),
        )
    }

    editing?.let { r ->
        RowSettingsDialog(
            row = r,
            display = overrides[r.key] ?: r.defaultDisplay,
            onChange = { onSaveDisplay(r, it) },
            onDismiss = { editing = null },
            onEditPage = { editing = null; onEditPage() },
            fullArt = true,
        )
    }
}
