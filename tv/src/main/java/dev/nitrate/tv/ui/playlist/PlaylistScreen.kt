package dev.nitrate.tv.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.playlists.PlaylistPlaying
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaKind
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.playlists.PlaylistPlaces
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.components.onPlayKey
import dev.nitrate.tv.ui.rows.ignoreHeldSelect
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

/** Where a playlist is pinned: [onHome] as a Home tab, [inMenu] as a menu stop; the setters save it. */
class PlaylistPinning(
    val onHome: Boolean,
    val inMenu: Boolean,
    val setHome: (id: String, name: String, on: Boolean) -> Unit,
    val setMenu: (id: String, name: String, on: Boolean) -> Unit,
)

/**
 * A playlist's page. A video playlist plays as itself, in its own order: how much is watched, Continue
 * from where it was left (Jellyfin's play dates, any app), Play from the start, the entry you're up to
 * marked NEXT; holding OK on an entry offers playing from there, opening it and marking watched up to
 * it or unwatched from it on. A music playlist plays as a queue.
 */
@Composable
fun PlaylistScreen(
    repository: MediaServer,
    @Suppress("UNUSED_PARAMETER") settings: DisplaySettingsStore,
    playlistId: String,
    /** Play [items] in order, starting with the first. */
    onPlayQueue: (items: List<MediaItem>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    /** Leaves the page after the playlist was deleted. */
    onDeleted: () -> Unit = {},
    /** In story order: carries on from [next] (with a "Last time" recap when due). Null: no story-order switch. */
    onContinue: ((playlist: MediaItem, next: MediaItem, rest: List<MediaItem>) -> Unit)? = null,
    /** Marks the playlist in story order, or not. */
    onStoryOrder: ((playlistId: String, on: Boolean) -> Unit)? = null,
    /**
     * A video playlist: plays [item] as part of it, so it carries on in the playlist's order (Up Next,
     * Continue Watching, Home) from where it's left; resuming, not from the top. Null: played as a queue.
     */
    onPlayFrom: ((playlist: MediaItem, item: MediaItem) -> Unit)? = null,
    /** Everything in a video playlist (whole shows as their episodes), for where the user is and marking watched. */
    playlists: PlaylistPlaying? = null,
    /** Shown at the top (Home's tabs, when the playlist is pinned there); the page then leaves focus to them. */
    header: (@Composable () -> Unit)? = null,
    /** Pinned to Home's tabs / in the menu (`DisplayPrefs.homePlaylists`/`menuPlaylists`), and changing that. Null: not offered. */
    pinning: PlaylistPinning? = null,
) {
    val id = remember(playlistId) { playlistId }
    var refresh by remember { mutableStateOf(0) }
    // Kept while the page is in the back stack: coming back shows it at once, then refreshes quietly.
    var playlist by dev.nitrate.tv.ui.components.retained<MediaItem?>("playlist/$id") { null }
    var items by dev.nitrate.tv.ui.components.retained<List<MediaItem>?>("playlist-items/$id") { null }
    var entries by dev.nitrate.tv.ui.components.retained<List<MediaItem>?>("playlist-entries/$id") { null }
    LaunchedEffect(id, refresh) {
        runCatching { repository.getItem(id) }.getOrNull()?.let { playlist = it }
        items = runCatching { repository.playlistItems(id) }.getOrElse { items ?: emptyList() }
        if (playlists != null && items.orEmpty().any { it.mediaKind != MediaKind.AUDIO }) {
            if (entries == null) entries = playlists.cached(id)
            entries = runCatching { playlists.entries(id, fresherThanMs = if (refresh > 0) 0 else PlaylistPlaying.KEEP_MS) }.getOrNull() ?: entries
        }
    }
    var editing by remember { mutableStateOf(false) }
    var entryActions by remember { mutableStateOf<Int?>(null) }
    var entryMenu by remember { mutableStateOf<MediaItem?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    val playFocus = remember { FocusRequester() }

    val list = items
    val pl = playlist
    if (pl == null || list == null) {
        dev.nitrate.tv.ui.components.ListPlaceholder()
        return
    }
    if (header == null) LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // A video playlist plays as itself: from where it was left, carrying on in its order.
    val video = onPlayFrom != null && list.any { it.mediaKind != MediaKind.AUDIO }
    val all = entries ?: list
    val status = if (video) PlaylistPlaces.progress(all) else null
    val placeAt = if (video) PlaylistPlaces.lastPlayed(all) else null
    val next = if (video) PlaylistPlaces.nextUp(all) else null
    // The playlist's own entry holding what's next (a whole show or season stands for its episodes).
    val nextEntry = next?.let { n -> list.indexOfFirst { it.id == n.id || it.id == n.seriesId || it.id == n.seasonId } }?.takeIf { it >= 0 }
    val prefsNow = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    val inOrder = pl.id in prefsNow.recapPlaylists

    val shown = focused ?: pl
    Box(Modifier.fillMaxSize()) {
        Backdrop(repository.backdropUrl(shown) ?: list.firstNotNullOfOrNull(repository::backdropUrl), Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize()) {
            header?.invoke()
            // Large header for whatever is focused: the playlist itself until the list takes focus.
            Column(
                Modifier.padding(start = PagePadding, end = PagePadding, top = 40.dp).fillMaxWidth(0.55f).height(230.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                TitleOrLogo(shown, repository.logoUrl(shown))
                if (shown.id == pl.id) {
                    val runtime = all.sumOf { it.runtimeMs ?: 0L }
                    Text(
                        listOfNotNull("${all.size} ${if (all.size == 1) "item" else "items"}", runtime.takeIf { it > 0 }?.let(::formatRuntime)).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status?.takeIf { it.total > 0 }?.let { WatchedBar(it.share, it.line) }
                } else {
                    shown.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    ItemMetaLine(shown)
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
                // As on a details page: one primary button, a second way to play, then round Shuffle and
                // More (editing, renaming, deleting, story order), the focused one's name under the row.
                // Continue from where it was left (or the first one under way), when that isn't the very start.
                val canContinue = video && next != null && (placeAt != null || next.id != all.firstOrNull()?.id)
                // Story order (not a video playlist played as itself): carry on from the first unwatched, with a recap after a break.
                val storyNext = list.firstOrNull { !it.user.played }
                val storyContinue = !video && inOrder && storyNext != null && storyNext != list.first() && onContinue != null
                if (canContinue) {
                    Button(onClick = { onPlayFrom!!(pl, next!!) }, modifier = Modifier.focusRequester(playFocus)) { Text("Continue: ${dev.nitrate.core.recap.Recaps.label(next!!)}") }
                } else if (storyContinue) {
                    Button(onClick = { onContinue!!(pl, storyNext!!, list.dropWhile { it.id != storyNext.id }) }, modifier = Modifier.focusRequester(playFocus)) {
                        Text("Continue: ${dev.nitrate.core.recap.Recaps.label(storyNext!!)}")
                    }
                }
                val playAll: () -> Unit = { if (video) onPlayFrom!!(pl, list.first()) else onPlayQueue(list) }
                val playAllLabel = if (video) "Play from the start" else "Play all"
                if (canContinue || storyContinue) OutlinedButton(onClick = playAll, enabled = list.isNotEmpty()) { Text(playAllLabel) }
                else Button(onClick = playAll, enabled = list.isNotEmpty(), modifier = Modifier.focusRequester(playFocus)) { Text(playAllLabel) }
                dev.nitrate.tv.ui.components.RoundAction(dev.nitrate.tv.ui.components.ShuffleIcon, "Shuffle", onHint = { hint = it }) { if (list.isNotEmpty()) onPlayQueue(list.shuffled()) }
                dev.nitrate.tv.ui.components.RoundAction(androidx.compose.material.icons.Icons.Filled.MoreVert, "More", onHint = { hint = it }) { more = true }
                if (editing) OutlinedButton(onClick = { editing = false }) { Text("Done editing") }
            }
            Box(Modifier.padding(start = PagePadding)) { dev.nitrate.tv.ui.components.ActionHint(hint) }
            if (more) {
                val first = remember { FocusRequester() }
                dev.nitrate.tv.ui.lists.TvDialog({ more = false }) {
                    Text(pl.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    ListItem(selected = false, onClick = { more = false; editing = !editing }, headlineContent = { Text(if (editing) "Done editing" else "Edit") },
                        supportingContent = { Text("Move or remove what's in it") }, modifier = Modifier.focusRequester(first))
                    if (onStoryOrder != null) ListItem(
                        selected = false, onClick = { onStoryOrder(pl.id, !inOrder) },
                        headlineContent = { Text("In story order") },
                        supportingContent = { Text("Carry on where you left off, with a recap after a break") },
                        trailingContent = { androidx.tv.material3.Switch(checked = inOrder, onCheckedChange = null) },
                    )
                    pinning?.let { pin ->
                        ListItem(
                            selected = false, onClick = { pin.setHome(pl.id, pl.name.orEmpty(), !pin.onHome) },
                            headlineContent = { Text(if (pin.onHome) "Unpin from Home" else "Pin to Home") },
                            supportingContent = { Text("A tab of its own beside Home") },
                            trailingContent = { androidx.tv.material3.Switch(checked = pin.onHome, onCheckedChange = null) },
                        )
                        ListItem(
                            selected = false, onClick = { pin.setMenu(pl.id, pl.name.orEmpty(), !pin.inMenu) },
                            headlineContent = { Text(if (pin.inMenu) "Remove from the menu" else "Add to the menu") },
                            supportingContent = { Text("A menu stop that opens this playlist") },
                            trailingContent = { androidx.tv.material3.Switch(checked = pin.inMenu, onCheckedChange = null) },
                        )
                    }
                    ListItem(selected = false, onClick = { more = false; renaming = true }, headlineContent = { Text("Rename") })
                    ListItem(selected = false, onClick = { more = false; deleting = true }, headlineContent = { Text("Delete playlist") }, supportingContent = { Text("What's in it stays") })
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }

            LazyColumn(
                contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { i, it -> it.playlistEntryId ?: "$i-${it.id}" }) { index, item ->
                    val itemVideo = item.mediaKind != MediaKind.AUDIO
                    PlaylistEntry(
                        index = index,
                        item = item,
                        isNext = index == nextEntry,
                        repository = repository,
                        // Selecting an entry plays from there (or, editing, offers moves); holding OK offers the rest.
                        onClick = {
                            when {
                                editing -> entryActions = index
                                video && itemVideo -> onPlayFrom!!(pl, item)
                                else -> onPlayQueue(list.drop(index))
                            }
                        },
                        onLongClick = {
                            when {
                                editing -> entryActions = index
                                video && itemVideo -> entryMenu = item
                                else -> onOpen(item)
                            }
                        },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) focused = item },
                    )
                }
            }
        }
    }

    fun change(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
            refresh++
        }
    }
    entryMenu?.let { item ->
        EntryMenu(
            item,
            onPlay = { entryMenu = null; onPlayFrom?.invoke(pl, item) },
            onOpen = { entryMenu = null; onOpen(item) },
            onMark = playlists?.let { p -> { watched: Boolean -> entryMenu = null; change { p.markUpTo(id, item.id, watched) } } },
            onDismiss = { entryMenu = null },
        )
    }
    entryActions?.let { index ->
        val item = list.getOrNull(index)
        val entry = item?.playlistEntryId
        if (item == null || entry == null) {
            entryActions = null
        } else {
            dev.nitrate.tv.ui.lists.PlaylistEntryDialog(
                item,
                canMoveUp = index > 0,
                canMoveDown = index < list.lastIndex,
                onMove = { delta -> entryActions = null; change { repository.movePlaylistItem(id, entry, index + delta) } },
                onRemove = { entryActions = null; change { repository.removeFromPlaylist(id, listOf(entry)) } },
                onOpen = { entryActions = null; onOpen(item) },
                // The visible way to what holding OK offers: marking watched up to here, or unwatched from here on.
                onMark = playlists?.takeIf { item.mediaKind != MediaKind.AUDIO }?.let { p -> { watched: Boolean -> entryActions = null; change { p.markUpTo(id, item.id, watched) } } },
                onDismiss = { entryActions = null },
            )
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(pl.name) }
        dev.nitrate.tv.ui.lists.TvDialog({ renaming = false }) {
            Text("Rename playlist", style = MaterialTheme.typography.headlineSmall)
            dev.nitrate.tv.ui.components.TvTextField(name, { name = it }, placeholder = "Name")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = name.isNotBlank(), onClick = { renaming = false; change { repository.renamePlaylist(id, name.trim()) } }) { Text("Save") }
                OutlinedButton(onClick = { renaming = false }) { Text("Cancel") }
            }
        }
    }
    if (deleting) {
        val confirm = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ deleting = false }) {
            Text("Delete “${pl.name}”?", style = MaterialTheme.typography.headlineSmall)
            Text("The playlist goes; what's in it stays.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { deleting = false }, modifier = Modifier.focusRequester(confirm)) { Text("Keep it") }
                Button(onClick = { deleting = false; scope.launch { runCatching { repository.deleteList(id) }.onSuccess { onDeleted() } } }) { Text("Delete") }
            }
            LaunchedEffect(Unit) { runCatching { confirm.requestFocus() } }
        }
    }
}

/** How much of the playlist is watched: a slim bar and "12 of 40 watched". */
@Composable
private fun WatchedBar(share: Float, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(320.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
        }
        Text(line, style = MaterialTheme.typography.titleSmall)
    }
}

/** Holding OK on a video playlist's entry. */
@Composable
private fun EntryMenu(item: MediaItem, onPlay: () -> Unit, onOpen: () -> Unit, onMark: ((Boolean) -> Unit)?, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 520) {
        Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall)
        item.episodeLabel?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ListItem(selected = false, onClick = onPlay, headlineContent = { Text("Play from here") }, modifier = Modifier.focusRequester(first).ignoreHeldSelect())
        ListItem(selected = false, onClick = onOpen, headlineContent = { Text("Open it") })
        if (onMark != null) {
            ListItem(selected = false, onClick = { onMark(true) }, headlineContent = { Text("Mark watched up to here") })
            ListItem(selected = false, onClick = { onMark(false) }, headlineContent = { Text("Mark unwatched from here on") })
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}


@Composable
private fun PlaylistEntry(
    index: Int,
    item: MediaItem,
    isNext: Boolean,
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isNext) {
                    Text(
                        "NEXT", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (item.user.played) Text("✓", color = MaterialTheme.colorScheme.primary)
                item.runtimeMs?.let { Text(formatRuntime(it)) }
            }
        },
    )
}
