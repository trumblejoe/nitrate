package dev.nitrate.tv.ui.franchise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.AppContainer
import dev.nitrate.android.franchise.FranchiseHub
import dev.nitrate.android.franchise.FranchiseHubs
import dev.nitrate.core.franchise.FranchiseEntry
import dev.nitrate.core.franchise.Franchises
import dev.nitrate.core.franchise.WatchOrder
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.effects.PageBackdropSlot
import dev.nitrate.tv.ui.pages.changePrefs

private val Gold = Color(0xFFFFC46A)

/**
 * Menu → Franchises: the user's video playlists (their order is the watch order), each a wide card
 * with its own artwork. Holding OK on one moves it to "Not franchises" at the bottom, and back.
 */
@Composable
fun FranchisesScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val server = container.server
    val list by produceState<List<MediaItem>?>(null) { value = runCatching { FranchiseHubs.list(server) }.getOrDefault(emptyList()) }
    val hidden = LocalDisplayPrefs.current.franchiseHidden
    val shown = list.orEmpty().filter { it.id !in hidden }
    val apart = list.orEmpty().filter { it.id in hidden }
    val first = remember { FocusRequester() }
    fun toggle(id: String) = container.changePrefs { it.copy(franchiseHidden = if (id in it.franchiseHidden) it.franchiseHidden - id else it.franchiseHidden + id) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Franchises", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        list == null -> "Gathering your playlists…"
                        list!!.isEmpty() -> "No video playlists yet. A playlist in watch order (a saga's films, or a universe's episodes and films) becomes a franchise here."
                        else -> "Your playlists in watch order, each with its own page: what's next, everything in order, crossovers in place. Hold OK on one that isn't a franchise to move it down."
                    },
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 900.dp),
                )
            }
        }
        itemsIndexed(shown, key = { _, it -> it.id }) { i, c ->
            MediaCard(
                c, server.backdropUrl(c, 640), width = null, aspectRatio = 16f / 9f, onClick = { onOpen(c.id) }, onLongClick = { toggle(c.id) },
                title = c.name, logoUrl = server.logoUrl(c), showProgress = false,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        if (apart.isNotEmpty()) item(span = { GridItemSpan(3) }) {
            Text("Not franchises (hold OK to move one back)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
        itemsIndexed(apart, key = { _, it -> "h" + it.id }) { _, c ->
            MediaCard(c, server.backdropUrl(c, 480), width = null, aspectRatio = 16f / 9f, onClick = { onOpen(c.id) }, onLongClick = { toggle(c.id) }, title = c.name, showProgress = false, modifier = Modifier.alpha(0.6f))
        }
    }
    LaunchedEffect(list) { if (shown.isNotEmpty()) runCatching { first.requestFocus() } }
}

/**
 * A franchise's own page: its artwork and logo from the server, its theme music, a mood scene
 * (the one chosen for it, else one suggested from its tags), where the user is, what's next, and
 * everything in the chosen order with crossovers marked. OK on an entry plays it; holding OK opens it.
 */
@Composable
fun FranchiseHubScreen(container: AppContainer, playlistId: String, onPlay: (MediaItem) -> Unit, onOpen: (MediaItem) -> Unit) {
    val server = container.server
    val prefs = LocalDisplayPrefs.current
    val chosen = prefs.franchiseOrders[playlistId]
    var failed by remember { mutableStateOf<String?>(null) }
    val hub by produceState<FranchiseHub?>(null, playlistId, chosen) {
        runCatching { FranchiseHubs.load(server, playlistId, chosen) }.onSuccess { value = it }.onFailure { failed = it.message ?: "Couldn't open it" }
    }
    val pageKey = Franchises.pageKey(playlistId)
    val theme = prefs.pageThemes[pageKey] ?: hub?.mood
    // The franchise's theme music, as on a details page.
    val music = hub?.themeMusicFrom
    val themeOn = prefs.themeMusic ?: true
    DisposableEffect(music, themeOn) {
        if (music != null && themeOn) container.themeMusic.show(music)
        onDispose { if (music != null) container.themeMusic.hide(music) }
    }
    dev.nitrate.tv.ui.home.ThemedPage(pageKey, defaultTheme = hub?.mood) {
        PageBackdropSlot(hub?.backdrop, clean = true)
        Box(Modifier.fillMaxSize()) {
            // Shade under the words on the left and the rows at the bottom, the picture clear elsewhere.
            Box(Modifier.fillMaxHeight().width(760.dp).background(Brush.horizontalGradient(listOf(Color(0xD0000000), Color(0x66000000), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(330.dp).align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            val h = hub
            when {
                h == null && failed != null -> Text(failed!!, modifier = Modifier.padding(56.dp))
                h == null -> Text("Opening…", modifier = Modifier.padding(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> HubBody(container, h, theme, onPlay, onOpen)
            }
        }
    }
}

@Composable
private fun HubBody(container: AppContainer, hub: FranchiseHub, theme: dev.nitrate.core.effects.PageTheme?, onPlay: (MediaItem) -> Unit, onOpen: (MediaItem) -> Unit) {
    val server = container.server
    val playFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    val entries = hub.entries
    val nextIndex = hub.progress.next?.let { n -> entries.indexOfFirst { it.item.id == n.id } }?.takeIf { it >= 0 } ?: 0
    Column(Modifier.fillMaxSize().padding(start = 56.dp, top = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The franchise's logo, or its name set large.
        var logoFailed by remember(hub.logo) { mutableStateOf(hub.logo == null) }
        if (!logoFailed) AsyncImage(
            hub.logo, contentDescription = hub.playlist.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            onError = { logoFailed = true }, modifier = Modifier.heightIn(max = 120.dp).widthIn(max = 460.dp),
        ) else Text(hub.playlist.name, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 2, modifier = Modifier.widthIn(max = 720.dp))
        val films = hub.films
        val shows = hub.shows
        val episodes = entries.count { it.item.kind == ItemKind.EPISODE }
        Text(
            listOfNotNull(
                films.takeIf { it > 0 }?.let { "$it film" + if (it == 1) "" else "s" },
                shows.takeIf { it > 0 }?.let { "$it show" + if (it == 1) "" else "s" },
                episodes.takeIf { it > 0 }?.let { "$it episodes" },
                hub.order.label,
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.titleMedium, color = Color(0xFFE6E2DA),
        )
        // Where the user is, as a slim bar and in words.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(320.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(hub.progress.share.coerceIn(0f, 1f)).background(Brush.horizontalGradient(listOf(Color(0xFFFF9A3C), Gold))))
            }
            Text(hub.progress.line, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE6E2DA))
        }
        val next = hub.progress.next
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { next?.let(onPlay) }, enabled = next != null, modifier = Modifier.focusRequester(playFocus)) {
                Text(
                    when {
                        next == null -> "All watched"
                        hub.progress.resuming -> "Resume " + label(next)
                        hub.progress.watched == 0 -> "Start: " + label(next)
                        else -> "Next: " + label(next)
                    },
                )
            }
            OutlinedButton(onClick = {
                val other = WatchOrder.entries[(hub.order.ordinal + 1) % WatchOrder.entries.size]
                container.changePrefs { it.copy(franchiseOrders = it.franchiseOrders + (hub.playlist.id to other)) }
            }) { Text("Order: " + hub.order.label) }
            OutlinedButton(onClick = { picking = true }) { Text("Mood: " + (theme?.label ?: "None")) }
            OutlinedButton(onClick = { onOpen(hub.playlist) }) { Text("The playlist") }
        }
        Text(hub.order.detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9B4AA))
        Spacer(Modifier.weight(1f))
        // Everything in order, from just before what's next; crossovers marked.
        Text("In order", style = MaterialTheme.typography.titleMedium, color = Gold)
        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (nextIndex - 1).coerceAtLeast(0))
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 56.dp), modifier = Modifier.padding(top = 4.dp)) {
            itemsIndexed(entries, key = { _, e -> e.item.id }) { i, e ->
                EntryCard(container, e, i, isNext = i == nextIndex, onPlay = { onPlay(e.item) }, onOpen = { onOpen(e.item) })
            }
        }
    }
    LaunchedEffect(hub.playlist.id) { runCatching { playFocus.requestFocus() } }
    if (picking) dev.nitrate.tv.ui.effects.PageThemePicker(hub.playlist.name, theme ?: dev.nitrate.core.effects.PageTheme.NONE, onPick = { t ->
        container.changePrefs { it.copy(pageThemes = it.pageThemes + (Franchises.pageKey(hub.playlist.id) to t)) }
        picking = false
    }, onDismiss = { picking = false })
}

@Composable
private fun EntryCard(container: AppContainer, e: FranchiseEntry, index: Int, isNext: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    val item = e.item
    val server = container.server
    val image = if (item.kind == ItemKind.EPISODE) server.imageUrl(item) else server.backdropUrl(item, 480)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            MediaCard(
                item, image, width = 216.dp, aspectRatio = 16f / 9f, onClick = onPlay, onLongClick = onOpen,
                title = (item.seriesName ?: item.name), subtitle = listOfNotNull("${index + 1}.", Franchises.code(item) ?: item.productionYear?.toString()).joinToString(" "),
            )
            if (e.crossover != null || isNext) Text(
                if (isNext) "NEXT" else "CROSSOVER",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color(0xFF1A1208),
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(if (isNext) Color.White else Gold).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** "Arrow S3:E9", or a film's name. */
private fun label(item: MediaItem): String = Franchises.code(item)?.let { "${item.seriesName ?: ""} $it".trim() } ?: item.name
