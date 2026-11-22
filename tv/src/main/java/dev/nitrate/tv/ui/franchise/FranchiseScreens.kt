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
 * Menu → Franchises: the user's collections, each as a wide card with its own artwork and logo.
 * Any collection can be a franchise: films of one saga, or several shows of one universe.
 */
@Composable
fun FranchisesScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val server = container.server
    val list by produceState<List<MediaItem>?>(null) { value = runCatching { FranchiseHubs.list(server) }.getOrDefault(emptyList()) }
    val first = remember { FocusRequester() }
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
                        list == null -> "Gathering your collections…"
                        list!!.isEmpty() -> "No collections yet. Make one in Jellyfin (a saga's films, or several shows of one universe) and it becomes a franchise here."
                        else -> "Your collections, each with its own page: what's next, every film and episode in order, and crossovers in place."
                    },
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 900.dp),
                )
            }
        }
        itemsIndexed(list.orEmpty(), key = { _, it -> it.id }) { i, c ->
            MediaCard(
                c, server.backdropUrl(c, 640), width = null, aspectRatio = 16f / 9f, onClick = { onOpen(c.id) },
                title = c.name, logoUrl = server.logoUrl(c), showProgress = false,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
    LaunchedEffect(list) { if (!list.isNullOrEmpty()) runCatching { first.requestFocus() } }
}

/**
 * A franchise's own page: its artwork and logo from the server, its theme music, a mood scene
 * (the one chosen for it, else one suggested from its tags), where the user is, what's next, and
 * everything in the chosen order with crossovers marked. OK on an entry plays it; holding OK opens it.
 */
@Composable
fun FranchiseHubScreen(container: AppContainer, collectionId: String, onPlay: (MediaItem) -> Unit, onOpen: (MediaItem) -> Unit) {
    val server = container.server
    val prefs = LocalDisplayPrefs.current
    val chosen = prefs.franchiseOrders[collectionId]
    var failed by remember { mutableStateOf<String?>(null) }
    val hub by produceState<FranchiseHub?>(null, collectionId, chosen) {
        runCatching { FranchiseHubs.load(server, collectionId, chosen) }.onSuccess { value = it }.onFailure { failed = it.message ?: "Couldn't open it" }
    }
    val pageKey = Franchises.pageKey(collectionId)
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
            hub.logo, contentDescription = hub.collection.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            onError = { logoFailed = true }, modifier = Modifier.heightIn(max = 120.dp).widthIn(max = 460.dp),
        ) else Text(hub.collection.name, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 2, modifier = Modifier.widthIn(max = 720.dp))
        val films = hub.titles.count { it.kind == ItemKind.MOVIE }
        val shows = hub.titles.count { it.kind == ItemKind.SERIES }
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
                container.changePrefs { it.copy(franchiseOrders = it.franchiseOrders + (hub.collection.id to other)) }
            }) { Text("Order: " + hub.order.label) }
            OutlinedButton(onClick = { picking = true }) { Text("Mood: " + (theme?.label ?: "None")) }
            OutlinedButton(onClick = { onOpen(hub.collection) }) { Text("The collection") }
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
    LaunchedEffect(hub.collection.id) { runCatching { playFocus.requestFocus() } }
    if (picking) dev.nitrate.tv.ui.effects.PageThemePicker(hub.collection.name, theme ?: dev.nitrate.core.effects.PageTheme.NONE, onPick = { t ->
        container.changePrefs { it.copy(pageThemes = it.pageThemes + (Franchises.pageKey(hub.collection.id) to t)) }
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
