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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem

/** A title: backdrop, facts, overview and Play; for a show, its seasons and episodes. */
@Composable
internal fun DetailsScreen(container: IosContainer, nav: Nav, itemId: String) {
    val server = container.server
    val item by produceState<Result<MediaItem>?>(null, itemId) { value = runCatching { server.getItem(itemId) } }
    val loaded = item?.getOrNull()
    // A show plays its next episode; seasons pick which episodes show below.
    val seriesId = when (loaded?.kind) {
        ItemKind.SERIES -> loaded.id
        ItemKind.SEASON -> loaded.seriesId
        else -> null
    }
    val seasons by produceState(emptyList<MediaItem>(), seriesId) { value = seriesId?.let { runCatching { server.seasons(it) }.getOrNull() }.orEmpty() }
    var season by remember(seriesId) { mutableStateOf<String?>(null) }
    LaunchedEffect(seasons) { if (season == null) season = (if (loaded?.kind == ItemKind.SEASON) loaded.id else null) ?: seasons.firstOrNull { (it.user.unplayedCount ?: 0) > 0 }?.id ?: seasons.firstOrNull()?.id }
    val episodes by produceState(emptyList<MediaItem>(), seriesId, season) {
        value = if (seriesId != null && season != null) runCatching { server.episodes(seriesId, season!!) }.getOrNull().orEmpty() else emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            item == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            loaded == null -> Text("Couldn't open this: ${item?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                        AsyncImage(server.backdropUrl(loaded, 1280), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(loaded.seriesName?.takeIf { loaded.kind == ItemKind.EPISODE } ?: loaded.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        loaded.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        val facts = listOfNotNull(
                            loaded.productionYear?.toString(),
                            loaded.officialRating,
                            loaded.runtimeMs?.let { "${it / 60_000} min" },
                            loaded.communityRating?.let { "★ ${(it * 10).toInt() / 10.0}" },
                        )
                        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loaded.kind != ItemKind.SERIES && loaded.kind != ItemKind.SEASON) {
                            val resume = loaded.user.positionMs
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { nav.go(Screen.Player(loaded.id, resume)) }) { Text(if (resume > 0) "Resume" else "Play") }
                                if (resume > 0) androidx.compose.material3.OutlinedButton(onClick = { nav.go(Screen.Player(loaded.id, 0)) }) { Text("From the start") }
                            }
                        }
                        loaded.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                if (seasons.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons, key = { it.id }) { s -> FilterChip(selected = season == s.id, onClick = { season = s.id }, label = { Text(s.name) }) }
                    }
                }
                items(episodes, key = { it.id }) { e -> EpisodeRow(container, e) { nav.go(Screen.Details(e.id)) } }
            }
        }
        IconButton(onClick = nav::back, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }
}

@Composable
private fun EpisodeRow(container: IosContainer, episode: MediaItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            container.server.imageUrl(episode, ImageKind.PRIMARY, 240), null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${episode.indexNumber ?: ""}. ${episode.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            episode.runtimeMs?.let { Text("${it / 60_000} min" + if (episode.user.played) "  ·  Watched" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
