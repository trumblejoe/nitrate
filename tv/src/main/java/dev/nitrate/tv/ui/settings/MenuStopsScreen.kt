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
internal fun spotName(index: Int, dial: Boolean) =
    if (dial) "Ring ${index / MenuStops.SPOKES + 1}, ${SpokeNames[index % MenuStops.SPOKES]}" else "Stop ${index + 1}"

/**
 * Arranging the menu: choose a spot, then what goes there. The dial is arranged on a picture of
 * the dial (D-pad around it, OK on a spot); the rail and bars as a numbered list. Choosing a stop
 * that's already somewhere moves it, and whatever was in the spot takes its old place.
 */
@Composable
fun MenuStopsScreen(container: AppContainer) {
    // The stored settings (not a page's view of them), since they're saved back.
    val prefs = dev.nitrate.tv.ui.pages.rememberStoredPrefs(container)
    val scope = rememberCoroutineScope()
    val libraries = dev.nitrate.tv.ui.home.rememberLibraries(container)
    val pages = rememberCustomPages(container)
    val catalog = remember(libraries, pages, prefs.menuPlaylists) { menuCatalog(libraries, pages, MenuActions(), current = "", playlists = prefs.menuPlaylists) }
    MenuStopsEditor(prefs, catalog) { change ->
        val user = container.server.userId ?: return@MenuStopsEditor
        scope.launch { container.displaySettings.savePrefs(user, change(prefs)) }
    }
}

@Composable
internal fun MenuStopsEditor(prefs: DisplayPrefs, catalog: MenuCatalog, update: ((DisplayPrefs) -> DisplayPrefs) -> Unit) {
    val dial = prefs.tvMenu == TvMenuStyle.RADIAL
    val available = catalog.available(prefs)
    val lookup: (String) -> dev.nitrate.tv.ui.home.NavEntry? = { catalog.entry(it, prefs) }
    val slots = MenuStops.arrange(prefs.tvDialStops, available, catalog.defaults)
    fun saveDial(next: List<String?>?) = update { p -> p.copy(tvDialStops = next) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var placesOpen by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    val intro: @Composable () -> Unit = {
        Text("Menu stops", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (dial) "Pick a spot on the dial with the D-pad and press OK to choose what goes there. Stops sit straight up, right, down and left, ring after ring."
            else "Choose a stop to move it, put it in More (a page of its own, one press away) or hide it. Groups open in the menu onto their libraries. ${dev.nitrate.tv.ui.pages.menuKindName(prefs.tvMenu)} menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (prefs.tvMenu.cornerStops) Text(
            if (prefs.tvMenu == dev.nitrate.core.pages.TvMenuStyle.OVERLAY) "Search and your account (Settings, Switch user) sit in the menu's top row, so they aren't stops in this menu. Stops in More show in the menu under a More heading."
            else "Search, Settings and Switch user are in the top-right corner of every page (Up from the top of a page), so they aren't stops in this menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (dial) OutlinedButton(onClick = { placesOpen = true }) { Text("Groups, More and hidden") }
            OutlinedButton(onClick = { update { p -> if (dial) p.copy(tvDialStops = null) else p.copy(tvMenuStops = null, tvMoreStops = null) } }) { Text("Reset to default") }
        }
    }
    if (dial) {
        // Words on the left, the dial filling the right.
        Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { intro() }
            DialEditor(slots, lookup, Modifier.weight(1f).fillMaxSize(), first) { picking = it }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            intro()
            StopsList(prefs, catalog, canMove = true, first = first, update = update, modifier = Modifier.weight(1f))
        }
    }
    // The list's items are composed a frame later: ask until the first one has it.
    LaunchedEffect(Unit) { repeat(20) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(32) } }

    if (placesOpen) dev.nitrate.tv.ui.lists.TvPanel({ placesOpen = false }, width = 620) {
        val panelFirst = remember { FocusRequester() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Groups, More and hidden", style = MaterialTheme.typography.headlineSmall)
            StopsList(prefs, catalog, canMove = false, first = panelFirst, update = update, modifier = Modifier.weight(1f))
        }
        LaunchedEffect(Unit) { repeat(20) { if (runCatching { panelFirst.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(32) } }
    }
    picking?.let { index ->
        StopPicker(
            title = spotName(index, true),
            current = slots.getOrNull(index),
            slots = slots,
            dial = true,
            entries = (catalog.entries.keys + prefs.tvMenuGroups.map { MenuStops.group(it.id) }).filter { prefs.tvMenuGroups.none { g -> it in g.stops } }.mapNotNull(lookup),
            onPick = { id -> saveDial(MenuStops.place(slots, index, id)); picking = null },
            onClear = { saveDial(MenuStops.remove(slots, index)); picking = null },
            onDismiss = { picking = null },
        )
    }
}

/**
 * The menu's stops by where they are: group suggestions first ("Make “Anime”"), then In the menu (in
 * order), In More and Hidden. A stop opens its choices: move it (rail and bar), put it in the menu,
 * in More or hide it, and for a group rename it, change its libraries or split it up.
 */
@Composable
private fun StopsList(
    prefs: DisplayPrefs,
    catalog: MenuCatalog,
    canMove: Boolean,
    first: FocusRequester,
    update: ((DisplayPrefs) -> DisplayPrefs) -> Unit,
    modifier: Modifier,
) {
    val available = catalog.available(prefs)
    val arranged = MenuStops.linear(MenuStops.arrange(prefs.tvMenuStops, available, catalog.defaults))
    val more = dev.nitrate.core.pages.MenuLayout.more(prefs.tvMoreStops, catalog.moreDefaults, available, prefs.tvMenuGroups)
    // The overlay and the rail: Search, Settings and Switch user are in the profile corner, not offered here.
    val notShown = if (prefs.tvMenu.cornerStops) MenuStops.IN_CORNER else emptySet()
    val shown = MenuStops.shownIn(dev.nitrate.core.pages.MenuLayout.main(arranged, prefs.tvMenuGroups, more), prefs.tvMenu).filter { it != MenuStops.MORE }
    val members = prefs.tvMenuGroups.flatMap { it.stops }.toSet()
    val hidden = available.filter { it !in shown && it !in more && it !in members && it != MenuStops.MORE && it !in notShown }
    val libraries = catalog.entries.filterKeys { it.startsWith("library:") }.mapValues { it.value.label }
    val suggestions = dev.nitrate.core.pages.MenuGroups.suggest(libraries, prefs.tvMenuGroups, prefs.tvGroupSuggestionsDismissed)
    var choosing by remember { mutableStateOf<String?>(null) }
    // The first suggestion takes focus, else the menu's first stop (fixed, so items composed again keep it).
    fun takeFirst(isFirst: Boolean): Modifier = if (isFirst) Modifier.focusRequester(first) else Modifier

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        suggestions.forEach { s ->
            item(key = "suggest-${s.key}") {
                Row(Modifier.widthIn(max = 760.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Make “${s.name}”", style = MaterialTheme.typography.titleMedium)
                        Text(s.stops.mapNotNull(libraries::get).joinToString(", ") + " as one stop that opens onto them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.tv.material3.Button(onClick = { update { p -> dev.nitrate.core.pages.MenuGroups.make(p, s.name, s.stops, arranged) } }, modifier = takeFirst(s == suggestions.first())) { Text("Make group") }
                    OutlinedButton(onClick = { update { p -> p.copy(tvGroupSuggestionsDismissed = p.tvGroupSuggestionsDismissed + s.key) } }) { Text("Not now") }
                }
            }
        }
        fun section(title: String, ids: List<String>, numbered: Boolean) {
            if (ids.isEmpty()) return
            item(key = "h-$title") { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
            itemsIndexed(ids, key = { _, id -> "$title/$id" }) { i, id ->
                val entry = catalog.entry(id, prefs) ?: return@itemsIndexed
                ListItem(
                    selected = false,
                    onClick = { choosing = id },
                    leadingContent = { Icon(entry.icon, contentDescription = null) },
                    headlineContent = { Text((if (numbered) "${i + 1}.  " else "") + entry.label) },
                    supportingContent = if (entry.children.isNotEmpty()) ({ Text("Group: " + entry.children.joinToString(", ") { it.label }) }) else null,
                    trailingContent = { Text("Change  ›", color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.widthIn(max = 760.dp).then(takeFirst(i == 0 && title == "In the menu" && suggestions.isEmpty())),
                )
            }
        }
        section("In the menu", shown, numbered = true)
        section("In More", more.filter { it !in notShown }, numbered = false)
        section("Hidden", hidden, numbered = false)
    }

    choosing?.let { id ->
        StopChoices(
            id, prefs, catalog, arranged, more, shown, canMove, update, notShown,
            onDismiss = { choosing = null },
        )
    }
}

/** One stop's choices: move it, where it goes, and for a group its name, libraries or splitting it up. */
@Composable
private fun StopChoices(
    id: String,
    prefs: DisplayPrefs,
    catalog: MenuCatalog,
    arranged: List<String>,
    more: List<String>,
    shown: List<String>,
    canMove: Boolean,
    update: ((DisplayPrefs) -> DisplayPrefs) -> Unit,
    notShown: Set<String>,
    onDismiss: () -> Unit,
) {
    val entry = catalog.entry(id, prefs) ?: return
    val group = prefs.tvMenuGroups.firstOrNull { MenuStops.group(it.id) == id }
    val place = dev.nitrate.core.pages.MenuLayout.placeOf(id, shown, more)
    var renaming by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    if (renaming && group != null) {
        var name by remember { mutableStateOf(group.name) }
        dev.nitrate.tv.ui.lists.TvDialog({ renaming = false }) {
            Text("Rename “${group.name}”", style = MaterialTheme.typography.headlineSmall)
            dev.nitrate.tv.ui.components.TvTextField(name, { name = it }, placeholder = "Name")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.tv.material3.Button(enabled = name.isNotBlank(), onClick = { update { p -> dev.nitrate.core.pages.MenuGroups.rename(p, group.id, name.trim()) }; renaming = false }) { Text("Save") }
                OutlinedButton(onClick = { renaming = false }) { Text("Cancel") }
            }
        }
        return
    }
    if (members && group != null) {
        val libraryIds = catalog.entries.keys.filter { it.startsWith("library:") }
        val inner = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog({ members = false }) {
            Text("Libraries in “${group.name}”", style = MaterialTheme.typography.headlineSmall)
            libraryIds.forEachIndexed { i, lib ->
                val on = lib in group.stops
                ListItem(
                    selected = on,
                    onClick = { update { p -> dev.nitrate.core.pages.MenuGroups.setStops(p, group.id, if (on) group.stops - lib else group.stops + lib) } },
                    headlineContent = { Text(catalog.entries[lib]?.label.orEmpty()) },
                    trailingContent = { androidx.tv.material3.Switch(checked = on, onCheckedChange = null) },
                    modifier = if (i == 0) Modifier.focusRequester(inner) else Modifier,
                )
            }
            OutlinedButton(onClick = { members = false }) { Text("Done") }
        }
        LaunchedEffect(Unit) { runCatching { inner.requestFocus() } }
        return
    }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss) {
        Text(entry.label, style = MaterialTheme.typography.headlineSmall)
        val fixed = id in dev.nitrate.core.pages.MenuLayout.NEVER_MORE
        var firstUsed = false
        fun takeFirst(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
        if (canMove && place == dev.nitrate.core.pages.StopPlace.MENU) {
            val at = shown.indexOf(id)
            if (at > 0) ListItem(selected = false, onClick = { update { p -> p.copy(tvMenuStops = dev.nitrate.core.pages.MenuLayout.move(arranged, p.tvMenuGroups, more, id, -1, notShown)) } }, headlineContent = { Text("Move up") }, modifier = takeFirst())
            if (at in 0 until shown.lastIndex) ListItem(selected = false, onClick = { update { p -> p.copy(tvMenuStops = dev.nitrate.core.pages.MenuLayout.move(arranged, p.tvMenuGroups, more, id, +1, notShown)) } }, headlineContent = { Text("Move down") }, modifier = takeFirst())
        }
        if (fixed) Text("Always in the menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else dev.nitrate.core.pages.StopPlace.entries.forEach { p ->
            ListItem(
                selected = p == place,
                onClick = { update { prefs2 -> dev.nitrate.core.pages.MenuLayout.setPlace(prefs2, id, p, arranged, more) }; onDismiss() },
                headlineContent = { Text(p.label) },
                supportingContent = {
                    Text(
                        when (p) {
                            dev.nitrate.core.pages.StopPlace.MENU -> "One press from anywhere"
                            dev.nitrate.core.pages.StopPlace.MORE -> "On the More page, out of the way"
                            dev.nitrate.core.pages.StopPlace.HIDDEN -> "Not shown (come back here to bring it back)"
                        },
                    )
                },
                trailingContent = { if (p == place) Text("✓", color = MaterialTheme.colorScheme.primary) },
                modifier = takeFirst(),
            )
        }
        if (id.startsWith("playlist:")) {
            val playlistId = id.removePrefix("playlist:")
            ListItem(
                selected = false,
                onClick = { update { p -> p.copy(menuPlaylists = p.menuPlaylists - playlistId, tvMenuStops = p.tvMenuStops?.minus(id), tvMoreStops = p.tvMoreStops?.minus(id)) }; onDismiss() },
                headlineContent = { Text("Remove from the menu") },
                supportingContent = { Text("The playlist stays; add it again from its page's More") },
            )
        }
        if (group != null) {
            ListItem(selected = false, onClick = { renaming = true }, headlineContent = { Text("Rename") })
            ListItem(selected = false, onClick = { members = true }, headlineContent = { Text("Change libraries") }, supportingContent = { Text(entry.children.joinToString(", ") { it.label }) })
            ListItem(selected = false, onClick = { update { p -> dev.nitrate.core.pages.MenuGroups.split(p, group.id) }; onDismiss() }, headlineContent = { Text("Split up") }, supportingContent = { Text("Its libraries back in the menu on their own") })
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** The dial with every spot visible (empty ones as +), one more ring than is used. */
@Composable
private fun DialEditor(slots: List<String?>, lookup: (String) -> dev.nitrate.tv.ui.home.NavEntry?, modifier: Modifier, focus: FocusRequester, onPick: (Int) -> Unit) {
    val rings = maxOf(2, (slots.size + MenuStops.SPOKES - 1) / MenuStops.SPOKES + 1)
    var at by remember { mutableStateOf(0 to 0) }
    var focused by remember { mutableStateOf(false) }
    fun idAt(spoke: Int, ring: Int) = slots.getOrNull(ring * MenuStops.SPOKES + spoke)
    val selected = idAt(at.first, at.second)?.let(lookup)
    DialFace(
        rings = rings,
        stop = { s, r -> idAt(s, r)?.let(lookup) },
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
    entries: List<dev.nitrate.tv.ui.home.NavEntry>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvPanel(onDismiss, width = 520) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Choose what goes here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(entries, key = { it.id }) { entry ->
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
                        modifier = if (entry.id == (current ?: entries.firstOrNull()?.id)) Modifier.focusRequester(first) else Modifier,
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
