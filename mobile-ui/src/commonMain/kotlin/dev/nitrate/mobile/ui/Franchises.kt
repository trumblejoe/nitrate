package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.nitrate.android.franchise.FranchiseHub
import dev.nitrate.android.franchise.FranchiseHubs
import dev.nitrate.core.franchise.FranchiseEntry
import dev.nitrate.core.franchise.Franchises
import dev.nitrate.core.franchise.WatchOrder
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object FranchisesRoute

@Serializable data class FranchiseHubRoute(val playlistId: String)

private val Gold = Color(0xFFFFC46A)

/** The Library tab's way in to franchises. */
@Composable
internal fun FranchisesEntry(actions: AppActions) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2A1640), Color(0xFF3A1A1A))))
            .pressable({ actions.nav.navigate(FranchisesRoute) }).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Franchises", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Your playlists in watch order, with what's next and crossovers", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0D6CC), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = Gold)
    }
}

/** The user's video playlists, each a wide card of its own artwork; a long press moves one to "Not franchises". */
@Composable
fun FranchisesScreen(actions: AppActions) {
    val server = actions.server
    val container = actions.container
    val scope = rememberCoroutineScope()
    val prefs = LocalDisplayPrefs.current
    val list by produceState<List<MediaItem>?>(null) { value = runCatching { FranchiseHubs.list(server) }.getOrDefault(emptyList()) }
    val shown = list.orEmpty().filter { it.id !in prefs.franchiseHidden }
    val apart = list.orEmpty().filter { it.id in prefs.franchiseHidden }
    fun toggle(id: String) {
        val u = server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(u, prefs.copy(franchiseHidden = if (id in prefs.franchiseHidden) prefs.franchiseHidden - id else prefs.franchiseHidden + id)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Franchises", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    when {
                        list == null -> "Gathering your playlists…"
                        list!!.isEmpty() -> "No video playlists yet. A playlist in watch order (a saga's films, or a universe's episodes and films) becomes a franchise here."
                        else -> "Your playlists in watch order: what's next, everything in order, crossovers in place. Long-press one that isn't a franchise to move it down."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(shown, key = { _, c -> c.id }) { _, c ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF16141C))
                    .pressable({ actions.nav.navigate(FranchiseHubRoute(c.id)) }, onLongClick = { toggle(c.id) }),
            ) {
                AsyncImage(server.backdropUrl(c, 960), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                FranchiseTitle(server.logoUrl(c), c.name, Modifier.align(Alignment.BottomStart).padding(16.dp), small = true)
            }
        }
        if (apart.isNotEmpty()) item { Text("Not franchises (long-press to move one back)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
        itemsIndexed(apart, key = { _, c -> "h" + c.id }) { _, c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .pressable({ actions.nav.navigate(FranchiseHubRoute(c.id)) }, onLongClick = { toggle(c.id) }).padding(14.dp),
            ) { Text(c.name, style = MaterialTheme.typography.bodyMedium) }
        }
        item { Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
}

/** The franchise's logo, or its name in large type when it has none (or it won't load). */
@Composable
private fun FranchiseTitle(logo: String?, name: String, modifier: Modifier, small: Boolean = false) {
    var failed by remember(logo) { mutableStateOf(logo == null) }
    if (!failed) AsyncImage(logo, name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { failed = true }, modifier = modifier.heightIn(max = if (small) 56.dp else 90.dp).widthIn(max = 280.dp))
    else Text(name, style = if (small) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White, modifier = modifier)
}

/**
 * A franchise's page: its own artwork and theme music, where you are, what's next, and everything in
 * the chosen order (the playlist's own, as it aired, or release), crossovers marked. Tap plays (the
 * playlist then carries on from it); a long press offers opening it and marking everything up to it.
 */
@Composable
fun FranchiseHubScreen(actions: AppActions, playlistId: String) {
    val container = actions.container
    val server = actions.server
    val prefs = LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    val chosen = prefs.franchiseOrders[playlistId]
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var hub by remember(playlistId) { mutableStateOf<FranchiseHub?>(null) }
    // What it held last time at once, then fresh.
    androidx.compose.runtime.LaunchedEffect(playlistId, chosen, reload) {
        runCatching { FranchiseHubs.hub(server, container.playlists, playlistId, chosen).collect { hub = it } }
            .onFailure { if (hub == null) failed = it.message ?: "Couldn't open it" }
    }
    // Playing from here: the playlist carries on from it (Up Next, Home, every device).
    val play: (MediaItem) -> Unit = { item -> scope.launch { container.playlists.startedFrom(playlistId, item.id); actions.play(item, false) } }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    var withSomeone by remember { mutableStateOf(false) }
    val music = hub?.themeMusicFrom
    val themeOn = prefs.themeMusic ?: false
    DisposableEffect(music, themeOn) {
        if (music != null && themeOn) container.themeMusic.show(music)
        onDispose { if (music != null) container.themeMusic.hide(music) }
    }
    fun setOrder(o: WatchOrder) {
        val u = server.userId ?: return
        scope.launch { container.displaySettings.savePrefs(u, prefs.copy(franchiseOrders = prefs.franchiseOrders + (playlistId to o))) }
    }
    val h = hub
    if (h == null) {
        Box(Modifier.fillMaxSize().padding(ScreenPadding)) { Text(failed ?: "Opening…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val nextIndex = h.progress.next?.let { n -> h.entries.indexOfFirst { it.item.id == n.id } }?.takeIf { it >= 0 } ?: 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                AsyncImage(h.backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x55000000), 0.45f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)))
                Column(Modifier.align(Alignment.BottomStart).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FranchiseTitle(h.logo, h.playlist.name, Modifier)
                    Text(
                        listOfNotNull(
                            h.films.takeIf { it > 0 }?.let { "$it film" + if (it == 1) "" else "s" },
                            h.shows.takeIf { it > 0 }?.let { "$it show" + if (it == 1) "" else "s" },
                            h.entries.count { it.item.kind == ItemKind.EPISODE }.takeIf { it > 0 }?.let { "$it episodes" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6E2DA),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(160.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(h.progress.share).background(Brush.horizontalGradient(listOf(Color(0xFFFF9A3C), Gold))))
                        }
                        Text(h.progress.line, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6E2DA))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val next = h.progress.next
                PillButton(
                    when {
                        next == null -> "All watched"
                        h.progress.resuming -> "Resume " + label(next)
                        h.progress.watched == 0 -> "Start: " + label(next)
                        else -> "Next: " + label(next)
                    },
                    null, primary = true, onClick = { next?.let(play) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchOrder.entries.forEach { o -> FilterChip(h.order == o, { setOrder(o) }, label = { Text(o.label) }) }
                }
                Text(h.order.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (container.companionOffers("partners")) androidx.compose.material3.TextButton(onClick = { withSomeone = true }) { Text("Watch with someone") }
            }
        }
        item { SectionHeader("In order", Modifier.padding(top = 8.dp)) }
        itemsIndexed(h.entries, key = { _, e -> e.item.id }) { i, e -> EntryLine(actions, e, i, i == nextIndex, onPlay = { play(e.item) }, onMenu = { menuFor = e.item }) }
        item { Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
    // A long press on an entry: what to do with it, and with everything up to it.
    menuFor?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(label(item)) },
            text = {
                Column {
                    listOf(
                        "Play from here" to { play(item) },
                        "Open it" to { actions.open(item) },
                        "Mark watched up to here" to { scope.launch { container.playlists.markUpTo(playlistId, item.id, watched = true); container.playlists.forget(playlistId); reload++ }; Unit },
                        "Mark unwatched from here on" to { scope.launch { container.playlists.markUpTo(playlistId, item.id, watched = false); container.playlists.forget(playlistId); reload++ }; Unit },
                    ).forEach { (text, act) ->
                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().pressable({ menuFor = null; act() }).padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { menuFor = null }) { Text("Close") } },
        )
    }
    if (withSomeone) WatchWithDialog(actions, h.playlist) { withSomeone = false }
}

@Composable
private fun EntryLine(actions: AppActions, e: FranchiseEntry, index: Int, isNext: Boolean, onPlay: () -> Unit, onMenu: () -> Unit) {
    val item = e.item
    val server = actions.server
    Row(
        Modifier.fillMaxWidth().pressable(onPlay, onLongClick = onMenu)
            .background(if (isNext) Gold.copy(alpha = 0.10f) else Color.Transparent).padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1A22))) {
            AsyncImage(if (item.kind == ItemKind.EPISODE) server.imageUrl(item) else server.backdropUrl(item, 320), item.spokenTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.user.played) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        }
        Column(Modifier.weight(1f)) {
            Text(item.seriesName ?: item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Franchises.code(item) ?: item.productionYear?.toString(), if (item.user.played) "Watched" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            e.crossover?.let { Text(it.label, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 2) }
        }
        if (isNext) Text("NEXT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1A1208),
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** "Arrow S3:E9", or a film's name. */
private fun label(item: MediaItem): String = Franchises.code(item)?.let { "${item.seriesName ?: ""} $it".trim() } ?: item.name


/** "In Arrowverse · 47 of 712 · after Arrow S3:E7 · before The Flash S1:E9", one per franchise holding [item]; a tap opens the hub. */
@Composable
internal fun FranchisePlaces(actions: AppActions, item: MediaItem) {
    val places by produceState(emptyList<dev.nitrate.android.franchise.PlaylistPlace>(), item.id) {
        value = runCatching { actions.container.playlists.placesOf(item) }.getOrDefault(emptyList())
    }
    places.take(3).forEach { p ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = 0.10f))
                .pressable({ actions.nav.navigate(FranchiseHubRoute(p.playlist.id)) }).padding(12.dp),
        ) {
            Text("In ${p.playlist.name}", style = MaterialTheme.typography.titleSmall, color = Gold)
            Text(p.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
