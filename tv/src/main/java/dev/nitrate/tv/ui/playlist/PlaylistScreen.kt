package dev.nitrate.tv.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.rows.PlaylistPageView
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.tv.ui.components.onPlayKey
import kotlinx.coroutines.launch
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaKind

private val PagePadding = 58.dp

@Composable
fun PlaylistScreen(
    repository: MediaServer,
    settings: DisplaySettingsStore,
    playlistId: String,
    /** Play [items] in order, starting with the first. */
    onPlayQueue: (items: List<MediaItem>) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val id = remember(playlistId) { playlistId }
    val playlist by produceState<MediaItem?>(null, id) { value = runCatching { repository.getItem(id) }.getOrNull() }
    val items by produceState<List<MediaItem>?>(null, id) {
        value = runCatching { repository.playlistItems(id) }.getOrDefault(emptyList())
    }

    val userId = repository.userId.orEmpty()
    val view by remember { settings.view(userId, "playlist-page", PlaylistPageView()) }.collectAsState(PlaylistPageView())
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    val playFocus = remember { FocusRequester() }

    val list = items
    val pl = playlist
    if (pl == null || list == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
        return
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    val shown = focused ?: pl
    Box(Modifier.fillMaxSize()) {
        Backdrop(repository.backdropUrl(shown) ?: list.firstNotNullOfOrNull(repository::backdropUrl), Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize()) {
            if (view.cinematic) {
                // Large header for whatever is focused: the playlist itself until the list takes focus.
                Column(
                    Modifier.padding(start = PagePadding, end = PagePadding, top = 40.dp).fillMaxWidth(0.55f).height(250.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    TitleOrLogo(shown, repository.logoUrl(shown))
                    shown.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (shown.id != pl.id) ItemMetaLine(shown)
                    shown.overview?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(
                Modifier.padding(start = PagePadding, end = PagePadding, top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!view.cinematic) Text(pl.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                val runtime = list.sumOf { it.runtimeMs ?: 0L }
                Text(
                    "${list.size} items" + if (runtime > 0) " · ${formatRuntime(runtime)}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onPlayQueue(list) }, enabled = list.isNotEmpty(), modifier = Modifier.focusRequester(playFocus)) {
                    Text("Play all")
                }
                OutlinedButton(onClick = { onPlayQueue(list.shuffled()) }, enabled = list.isNotEmpty()) { Text("Shuffle") }
                OutlinedButton(onClick = { scope.launch { settings.saveView(userId, "playlist-page", view.copy(cinematic = !view.cinematic)) } }) {
                    Text(if (view.cinematic) "Compact view" else "Cinematic view")
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { i, it -> "$i-${it.id}" }) { index, item ->
                    PlaylistEntry(
                        index = index,
                        item = item,
                        repository = repository,
                        // Selecting an entry plays from there; long-press opens its details.
                        onClick = { onPlayQueue(list.drop(index)) },
                        onLongClick = { onOpen(item) },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) focused = item },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEntry(
    index: Int,
    item: MediaItem,
    repository: MediaServer,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val audio = item.mediaKind == MediaKind.AUDIO
    val thumb = if (audio) {
        repository.imageUrl(item, ImageKind.PRIMARY, 120)
    } else {
        repository.imageUrl(item, ImageKind.THUMB, 160) ?: repository.backdropUrl(item) ?: repository.imageUrl(item, ImageKind.PRIMARY, 160)
    }
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onPlayKey(onClick),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 14.dp))
                Box(
                    Modifier.height(48.dp).aspectRatio(if (audio) 1f else 16f / 9f)
                        .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        },
        headlineContent = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(item.episodeLabel ?: item.productionYear?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.user.played) Text("✓", color = MaterialTheme.colorScheme.primary)
                item.runtimeMs?.let { Text(formatRuntime(it)) }
            }
        },
    )
}
