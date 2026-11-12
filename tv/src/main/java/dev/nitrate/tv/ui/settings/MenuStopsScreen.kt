package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.core.pages.TvMenuStyle
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.home.DialFace
import dev.nitrate.tv.ui.home.MenuActions
import dev.nitrate.tv.ui.home.MenuCatalog
import dev.nitrate.tv.ui.home.menuCatalog
import dev.nitrate.tv.ui.pages.rememberCustomPages
import kotlinx.coroutines.launch

private val SpokeNames = listOf("up", "right", "down", "left")

/** Where a slot is, in words: "Ring 2, left" on the dial, "Stop 3" otherwise. */
private fun spotName(index: Int, dial: Boolean) =
    if (dial) "Ring ${index / MenuStops.SPOKES + 1}, ${SpokeNames[index % MenuStops.SPOKES]}" else "Stop ${index + 1}"

/**
 * Arranging the menu: choose a spot, then what goes there. The dial is arranged on a picture of
 * the dial (D-pad around it, OK on a spot); the rail and bars as a numbered list. Choosing a stop
 * that's already somewhere moves it, and whatever was in the spot takes its old place.
 */
@Composable
fun MenuStopsScreen(container: AppContainer) {
    val prefs = LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    val libraries by produceState(emptyList<MediaItem>(), container.server) { value = runCatching { container.server.views() }.getOrDefault(emptyList()) }
    val pages = rememberCustomPages(container)
    val catalog = remember(libraries, pages) { menuCatalog(libraries, pages, MenuActions(), current = "") }
    MenuStopsEditor(prefs, catalog) { change ->
        val user = container.server.userId ?: return@MenuStopsEditor
        scope.launch { container.displaySettings.savePrefs(user, change(prefs)) }
    }
}

@Composable
internal fun MenuStopsEditor(prefs: DisplayPrefs, catalog: MenuCatalog, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
    val dial = prefs.tvMenu == TvMenuStyle.RADIAL
    val slots = MenuStops.arrange(if (dial) prefs.tvDialStops else prefs.tvMenuStops, catalog.entries.keys, catalog.defaults)
        .let { if (dial) it else MenuStops.linear(it) }
    fun save(next: List<String?>?) = update { p ->
        if (dial) p.copy(tvDialStops = next) else p.copy(tvMenuStops = next?.let(MenuStops::linear))
    }
    var picking by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }

    val intro: @Composable () -> Unit = {
        Text("Menu stops", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (dial) "Pick a spot on the dial with the D-pad and press OK to choose what goes there. Stops sit straight up, right, down and left, ring after ring."
            else "Pick a stop and press OK to change it or move another stop there. ${prefs.tvMenu.label} menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { save(null) }) { Text("Reset to default") }
    }
    if (dial) {
        // Words on the left, the dial filling the right.
        Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { intro() }
            DialEditor(slots, catalog, Modifier.weight(1f).fillMaxSize(), first) { picking = it }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            intro()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(slots) { i, id ->
                    val entry = id?.let(catalog.entries::get)
                    ListItem(
                        selected = false,
                        onClick = { picking = i },
                        leadingContent = { entry?.let { Icon(it.icon, contentDescription = null) } },
                        headlineContent = { Text("${i + 1}.  ${entry?.label ?: "—"}") },
                        trailingContent = { Text("Change  ›", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.widthIn(max = 640.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    )
                }
                item {
                    ListItem(selected = false, onClick = { picking = slots.size }, headlineContent = { Text("+  Add a stop") }, modifier = Modifier.widthIn(max = 640.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    picking?.let { index ->
        StopPicker(
            title = if (index < slots.size && slots[index] != null) spotName(index, dial) else if (dial) spotName(index, true) else "New stop",
            current = slots.getOrNull(index),
            slots = slots,
            dial = dial,
            catalog = catalog,
            onPick = { id -> save(MenuStops.place(slots, index, id)); picking = null },
            onClear = { save(MenuStops.remove(slots, index)); picking = null },
            onDismiss = { picking = null },
        )
    }
}

/** The dial with every spot visible (empty ones as +), one more ring than is used. */
@Composable
private fun DialEditor(slots: List<String?>, catalog: MenuCatalog, modifier: Modifier, focus: FocusRequester, onPick: (Int) -> Unit) {
    val rings = maxOf(2, (slots.size + MenuStops.SPOKES - 1) / MenuStops.SPOKES + 1)
    var at by remember { mutableStateOf(0 to 0) }
    var focused by remember { mutableStateOf(false) }
    fun idAt(spoke: Int, ring: Int) = slots.getOrNull(ring * MenuStops.SPOKES + spoke)
    val selected = idAt(at.first, at.second)?.let(catalog.entries::get)
    DialFace(
        rings = rings,
        stop = { s, r -> idAt(s, r)?.let(catalog.entries::get) },
        at = at.takeIf { focused },
        centre = "${spotName(at.second * MenuStops.SPOKES + at.first, true)}\n${selected?.label ?: "Empty"}",
        showEmpty = true,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val dir = listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft).indexOf(e.key)
                when {
                    dir >= 0 -> {
                        val (s, r) = at
                        // Left from the outermost left spot leaves the dial (for Reset).
                        if (dir == 3 && s == 3 && r == rings - 1) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) at = when {
                            dir == s -> s to minOf(r + 1, rings - 1)
                            dir == (s + 2) % 4 -> if (r > 0) s to r - 1 else dir to 0
                            else -> dir to r
                        }
                        true
                    }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyUp) onPick(at.second * MenuStops.SPOKES + at.first)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    )
}

/** What can go in a spot: every stop (saying where it is now), or nothing. */
@Composable
private fun StopPicker(
    title: String,
    current: String?,
    slots: List<String?>,
    dial: Boolean,
    catalog: MenuCatalog,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Choose what goes here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(catalog.entries.values.toList(), key = { it.id }) { entry ->
                    val where = slots.indexOf(entry.id)
                    ListItem(
                        selected = entry.id == current,
                        onClick = { onPick(entry.id) },
                        leadingContent = { Icon(entry.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        headlineContent = { Text(entry.label) },
                        supportingContent = {
                            Text(
                                when {
                                    entry.id == current -> "Here now"
                                    where >= 0 -> "Now at ${spotName(where, dial).lowercase()}; moves here"
                                    entry.id == MenuStops.SETTINGS -> "Always in the menu"
                                    else -> "Not in the menu"
                                },
                            )
                        },
                        modifier = if (entry.id == (current ?: catalog.entries.keys.first())) Modifier.focusRequester(first) else Modifier,
                    )
                }
                if (current != null && current != MenuStops.SETTINGS) {
                    item {
                        ListItem(
                            selected = false,
                            onClick = onClear,
                            headlineContent = { Text(if (dial) "Leave this spot empty" else "Remove from the menu") },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
