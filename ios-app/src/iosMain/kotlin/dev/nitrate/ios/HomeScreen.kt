package dev.nitrate.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.launch

/** Home: the server's rows (continue watching, next up, latest…), each a strip of cards. */
@Composable
internal fun HomeScreen(container: dev.nitrate.android.AppContainer, nav: Nav, onSignedOut: () -> Unit) {
    val server = container.server
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val rows by produceState<Result<List<MediaRow>>?>(null, reload) { value = runCatching { server.homeRows().filter { it.items.isNotEmpty() } } }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nitrate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { reload++ }) { Text("Refresh") }
                TextButton(onClick = { scope.launch { runCatching { server.logout() }; onSignedOut() } }) { Text("Sign out") }
            }
        }
        val result = rows
        when {
            result == null -> item { Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            result.isFailure -> item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load Home: ${result.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Try again") }
                }
            }
            else -> items(result.getOrThrow(), key = { it.key }) { row -> RowStrip(container, row) { nav.go(Screen.Details(it.id)) } }
        }
    }
}

@Composable
private fun RowStrip(container: dev.nitrate.android.AppContainer, row: MediaRow, onOpen: (MediaItem) -> Unit) {
    // Episodes and things in progress read best as wide stills; everything else as posters.
    val wide = row.items.count { it.kind == ItemKind.EPISODE || it.user.positionMs > 0 } > row.items.size / 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(row.items, key = { it.id }) { item -> Card(container, item, wide) { onOpen(item) } }
        }
    }
}

@Composable
private fun Card(container: dev.nitrate.android.AppContainer, item: MediaItem, wide: Boolean, onClick: () -> Unit) {
    val width = if (wide) 240.dp else 120.dp
    Column(Modifier.width(width).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val image = if (wide) container.server.backdropUrl(item, 640) ?: container.server.imageUrl(item, ImageKind.THUMB)
        else container.server.imageUrl(item, ImageKind.PRIMARY, 360)
        Box {
            AsyncImage(
                image, item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            val progress = item.user.playedPercentage
            if (item.user.positionMs > 0 && progress != null) {
                LinearProgressIndicator(
                    progress = { (progress / 100).toFloat() },
                    color = Accent,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(3.dp),
                )
            }
        }
        Text(item.seriesName ?: item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.episodeLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
