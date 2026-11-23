package dev.nitrate.tv.ui.pages

import dev.nitrate.core.pages.applyToPage
import dev.nitrate.core.pages.applyEverywhere
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.pages.PageLook
import dev.nitrate.core.pages.PageLooks
import dev.nitrate.core.pages.RowExtent
import dev.nitrate.core.pages.RowPlace
import dev.nitrate.core.pages.PageArrangement
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.tv.ui.rows.ChoiceItem
import dev.nitrate.tv.ui.rows.ignoreHeldSelect
import dev.nitrate.tv.ui.rows.rowLookItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The app's container, for screens deep in a page that save settings (the Customize panel). */
val LocalTvContainer = staticCompositionLocalOf<AppContainer?> { null }

/** The rows of the page (or tab) showing, provided by whoever owns them, for the Customize panel. */
val LocalPageRows = compositionLocalOf<RowsViewModel?> { null }

/** The top-level page a page frame shows (its theme, background and layout go by it). */
val LocalLayoutPage = staticCompositionLocalOf<String?> { null }

/** Where the Customize panel opens from: the page frame ([dev.nitrate.tv.ui.home.ThemedPage]) holds it. */
val LocalCustomizeHost = staticCompositionLocalOf<CustomizeHost?> { null }

enum class CustomizeSection(val label: String) { ROW("This row"), PAGE("This page") }

/** What the panel was opened for: a row (with the rows it's in), or just the page. */
data class CustomizeRequest(val row: MediaRow?, val vm: RowsViewModel?, val section: CustomizeSection)

/**
 * Held by the page frame, so the panel stays open while the page behind it changes (a row's style,
 * or the page going from rows to full-screen artwork and back).
 */
class CustomizeHost {
    var request by mutableStateOf<CustomizeRequest?>(null)

    /** The row the page last had focus in (and the rows it's in), told by the page as focus moves. */
    var lastRow: MediaRow? = null
    var lastRows: RowsViewModel? = null

    /** Holding OK on a row: the panel on that row. */
    fun openRow(row: MediaRow, vm: RowsViewModel) { request = CustomizeRequest(row, vm, CustomizeSection.ROW) }

    /** The menu's Customize: the panel on the page, with the row last used for "This row". */
    fun openPage() { request = CustomizeRequest(lastRow, lastRows, CustomizeSection.PAGE) }
}

/**
 * Saves the stored display settings changed by [change] (never a page's own view of them), on the
 * app's own scope: a picker closes on the press that chose, and a save tied to it would sometimes
 * be cancelled before it was written (a theme that "sometimes doesn't change").
 */
internal fun AppContainer.changePrefs(change: (DisplayPrefs) -> DisplayPrefs) {
    val u = server.userId ?: return
    saveScope.launch { displaySettings.savePrefs(u, change(displaySettings.prefs(u).first())) }
}

/** The stored display settings, as they change (not a page's own view of them). */
@Composable
internal fun rememberStoredPrefs(container: AppContainer): DisplayPrefs {
    val u = container.server.userId
    val flow = remember(u) { u?.let { container.displaySettings.prefs(it) } ?: kotlinx.coroutines.flow.flowOf(DisplayPrefs()) }
    return flow.collectAsState(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current).value
}

/** The page frame's panel, when it's asked for. */
@Composable
fun CustomizeLayer(host: CustomizeHost, pageKey: String) {
    val container = LocalTvContainer.current ?: return
    val request = host.request ?: return
    CustomizePanel(container, pageKey, request, onClose = { host.request = null })
}

/**
 * Customize: a panel down the right with the page live beside it, so every change shows at once.
 * This row (its look, place, name, hide), this page (rows, tabs, theme, background) and its layout
 * (this page only, or every page). Back closes it.
 */
@Composable
fun CustomizePanel(container: AppContainer, pageKey: String, request: CustomizeRequest, onClose: () -> Unit) {
    val owner = PageLooks.ownerOf(pageKey)
    val sections = CustomizeSection.entries.filter { it != CustomizeSection.ROW || (request.row != null && request.vm != null) }
    var section by remember { mutableStateOf(request.section.takeIf { it in sections } ?: CustomizeSection.PAGE) }
    val name by produceState(pageName(owner, null), owner) {
        val id = owner.removePrefix("library/").takeIf { owner.startsWith("library/") } ?: owner.removePrefix("custom/").takeIf { owner.startsWith("custom/") }
        if (id != null) value = pageName(owner, runCatching { container.server.getItem(id).name }.getOrNull())
    }
    val first = remember { FocusRequester() }
    val chips = remember { CustomizeSection.entries.associateWith { FocusRequester() } }
    // The row's look as it's saved now (it changes as choices are made).
    val emptyOverrides = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, dev.nitrate.core.rows.RowDisplay>()) }
    val overrides by (request.vm?.overrides ?: emptyOverrides).collectAsState()
    val emptyEntries = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.nitrate.core.pages.RowEntry>()) }
    val entries by (request.vm?.entries ?: emptyEntries).collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    val entry = request.row?.let { r -> entries.firstOrNull { it.rowKey == r.key } }
    if (renaming && entry != null && request.vm != null) NameDialog("Rename row", request.vm.titleOf(entry), onDismiss = { renaming = false }) { request.vm.rename(entry.id, it); renaming = false }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The page stays in full view beside the panel: no dimming.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(500.dp).ignoreHeldSelect()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)).padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Customize", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when (section) {
                        CustomizeSection.ROW -> request.row?.title.orEmpty()
                        CustomizeSection.PAGE -> name
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEach { s ->
                        FilterChip(selected = s == section, onClick = { section = s }, modifier = Modifier.focusRequester(chips.getValue(s))) { Text(s.label) }
                    }
                }
                LazyColumn(Modifier.weight(1f).onFocusChanged { listFocused = it.hasFocus }) {
                    when (section) {
                        CustomizeSection.ROW -> if (request.row != null && request.vm != null) rowSection(
                            request.row, request.vm, (overrides[request.row.key] ?: request.row.defaultDisplay).normalized(), entry, first,
                            onRename = { renaming = true }, onClose = onClose,
                        )
                        CustomizeSection.PAGE -> pageSection(container, owner, name, request.vm, first)
                    }
                }
            }
        }
    }
    // Into the section's first choice whenever it changes (the chips stay a press up).
    LaunchedEffect(section) {
        // The dialog places its own first focus a moment after opening: ask until the list has it.
        repeat(20) {
            kotlinx.coroutines.delay(50)
            runCatching { first.requestFocus() }
            kotlinx.coroutines.delay(20)
            if (listFocused) return@LaunchedEffect
        }
        runCatching { chips.getValue(section).requestFocus() }
    }
}

private fun pageName(owner: String, name: String?): String = when {
    owner == PageKeys.HOME -> "Home"
    owner == PageKeys.MORE_FROM -> "More from… pages"
    else -> name ?: "This page"
}

/** This row: its look (live on the row beside the panel), then where it sits, its name, and hiding it. */
private fun LazyListScope.rowSection(
    row: MediaRow,
    vm: RowsViewModel,
    display: dev.nitrate.core.rows.RowDisplay,
    entry: dev.nitrate.core.pages.RowEntry?,
    first: FocusRequester,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    rowLookItems(row, display, { vm.save(row, it) }, first)
    if (entry == null) return
    item { ListItem(selected = false, onClick = { vm.move(entry.id, -1) }, headlineContent = { Text("Move up") }, supportingContent = { Text("The row moves on the page as you press") }) }
    item { ListItem(selected = false, onClick = { vm.move(entry.id, 1) }, headlineContent = { Text("Move down") }) }
    item { ListItem(selected = false, onClick = onRename, headlineContent = { Text("Rename") }, trailingContent = { Text("${vm.titleOf(entry)}  ›", color = MaterialTheme.colorScheme.primary) }) }
    item { ListItem(selected = false, onClick = { vm.setHidden(entry.id, true); onClose() }, headlineContent = { Text("Hide this row") }, supportingContent = { Text("Turned off: not loaded at all until shown again from This page → Rows") }) }
}

/**
 * This page, by what's being changed: what's on it (rows, tabs), how it looks (theme, background,
 * tab style), how it's laid out (with "Use this layout on every page"), then What's shown, which
 * is every page's.
 */
private fun LazyListScope.pageSection(container: AppContainer, owner: String, name: String, vm: RowsViewModel?, first: FocusRequester) {
    val libraryId = owner.removePrefix("library/").takeIf { owner.startsWith("library/") }
    val hasRows = vm != null && owner != PageKeys.MORE_FROM
    val hasTabs = owner == PageKeys.HOME || libraryId != null
    // More from… pages are one row: nothing on them to arrange.
    if (hasRows || hasTabs) item { Heading("What's on it") }
    item {
        var open by remember { mutableStateOf<String?>(null) }
        Column {
            if (hasRows) ListItem(
                selected = false, onClick = { open = "rows" },
                headlineContent = { Text("Rows") }, supportingContent = { Text("Order, hide, rename and add your own") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.focusRequester(first),
            )
            if (hasTabs) ListItem(
                selected = false, onClick = { open = "tabs" },
                headlineContent = { Text("Tabs") },
                supportingContent = { Text(if (owner == PageKeys.HOME) "Your own pages as tabs beside Home" else "Recommended, All, Genres, Collections, your own…") },
                trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
                modifier = if (!hasRows) Modifier.focusRequester(first) else Modifier,
            )
        }
        when (open) {
            "rows" -> vm?.let { dev.nitrate.tv.ui.rows.PageEditor(name, it, container.server, libraryId = libraryId, onDone = { open = null }) }
            // A fixed-size dialog, not TvDialog: the tabs page is a lazy list, and TvDialog's
            // scrolling column would give it endless height, which Compose refuses (a crash).
            "tabs" -> androidx.compose.ui.window.Dialog(
                onDismissRequest = { open = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(width = 900.dp, height = 600.dp)
                        .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                ) {
                    if (owner == PageKeys.HOME) HomeSetupScreen(container) else LibraryTabsScreen(container, libraryId!!)
                }
            }
        }
    }
    item { Heading("How it looks") }
    item {
        val prefs = rememberStoredPrefs(container)
        var open by remember { mutableStateOf<String?>(null) }
        Column {
            ThemeItem(prefs.pageThemes[owner], first.takeIf { !hasRows && !hasTabs }) { open = "theme" }
            BackgroundItem(prefs.pageBackdrops[owner]) { open = "background" }
            if (hasTabs) {
                val style = PageLooks.resolved(prefs, owner).tabStyle ?: prefs.tvTabStyle
                ChoiceItem("Tab style", style.label) { open = "tabStyle" }
            }
        }
        when (open) {
            "theme" -> ThemeChoice(container, owner, name) { open = null }
            "background" -> BackgroundChoice(container, owner, name) { open = null }
            "tabStyle" -> dev.nitrate.tv.ui.components.TabStylePicker(
                PageLooks.resolved(prefs, owner).tabStyle ?: prefs.tvTabStyle,
                onPick = { style -> container.changePrefs { PageLooks.change(it, owner) { look -> look.copy(tabStyle = style) } }; open = null },
                onDismiss = { open = null },
            )
        }
    }
    item {
        val own = PageLooks.hasOwn(rememberStoredPrefs(container), owner)
        Heading("How it's laid out", if (own) "This page's own" else "Same as every page")
    }
    layoutItems(container, owner, null)
    item { Heading("Every page") }
    item {
        val prefs = rememberStoredPrefs(container)
        Column {
            dev.nitrate.tv.ui.rows.ToggleItem("Which row this is", prefs.tvRowMarker) { on -> container.changePrefs { it.copy(tvRowMarker = on) } }
            dev.nitrate.tv.ui.rows.ToggleItem("Clock", prefs.tvClock) { on -> container.changePrefs { it.copy(tvClock = on) } }
            dev.nitrate.tv.ui.rows.ToggleItem("Caption box", prefs.tvCaptionBox) { on -> container.changePrefs { it.copy(tvCaptionBox = on) } }
        }
    }
    item {
        var shown by remember { mutableStateOf(false) }
        ListItem(
            selected = false, onClick = { shown = true },
            headlineContent = { Text("What's shown") },
            supportingContent = { Text("Ratings, descriptions, badges and more, on every page and details page (your display profile)") },
            trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
        )
        if (shown) androidx.compose.ui.window.Dialog(
            onDismissRequest = { shown = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 820.dp, height = 600.dp)
                    .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(8.dp),
            ) { DisplaySettingsScreen(container, page = DisplayPage.SHOWN) }
        }
    }
}

/** A group's heading in the panel, with an optional note beside it. */
@Composable
private fun Heading(text: String, note: String? = null) {
    Row(Modifier.padding(top = 14.dp, bottom = 4.dp, start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The page's layout: how its rows are laid out and captioned. Changes are this page's own; "Use on
 * every page" makes them every page's, and "Same as every page" drops the page's own.
 */
internal fun LazyListScope.layoutItems(container: AppContainer, owner: String, first: FocusRequester?) {
    item {
        val scope = rememberCoroutineScope()
        val prefs = rememberStoredPrefs(container)
        val look = PageLooks.resolved(prefs, owner)
        val own = PageLooks.hasOwn(prefs, owner)
        fun set(change: (PageLook) -> PageLook) = container.changePrefs { PageLooks.change(it, owner, change) }
        val arrangement = look.arrangement ?: PageArrangement.ROWS
        Column {
            // A named look sets everything below at once; the choices under it fine-tune it.
            val preset = dev.nitrate.core.pages.LayoutPreset.matching(look)
            ChoiceItem("Look", preset?.label ?: "Your own", first?.let { Modifier.focusRequester(it) } ?: Modifier) {
                val next = dev.nitrate.core.pages.LayoutPreset.entries.let { all -> all[((preset?.ordinal ?: -1) + 1) % all.size] }
                container.changePrefs { dev.nitrate.core.pages.PageLooks.applyToPage(it, owner, next) }
            }
            ChoiceItem("Arrangement", arrangement.label) {
                set { it.copy(arrangement = (it.arrangement ?: PageArrangement.ROWS).next()) }
            }
            // Stacked rows keep their summary at the top left: no corner to choose there.
            if (arrangement != PageArrangement.STACKED) ChoiceItem("Caption corner", (look.captionCorner ?: dev.nitrate.core.pages.CaptionCorner.ALTERNATE).label) {
                set { it.copy(captionCorner = (it.captionCorner ?: dev.nitrate.core.pages.CaptionCorner.ALTERNATE).cycleNext()) }
            }
            ChoiceItem("Caption size", (look.captionSize ?: dev.nitrate.core.pages.CaptionSize.NORMAL).label) {
                set { it.copy(captionSize = (it.captionSize ?: dev.nitrate.core.pages.CaptionSize.NORMAL).cycleNext()) }
            }
            if (arrangement != PageArrangement.FULL_ART) {
                ChoiceItem("Focus style", (look.focusStyle ?: dev.nitrate.core.pages.FocusStyle.GROW).label) {
                    set { it.copy(focusStyle = (it.focusStyle ?: dev.nitrate.core.pages.FocusStyle.GROW).cycleNext()) }
                }
                ChoiceItem("Card corners", (look.cardCorners ?: dev.nitrate.core.pages.CardCorners.ROUNDED).label) {
                    set { it.copy(cardCorners = (it.cardCorners ?: dev.nitrate.core.pages.CardCorners.ROUNDED).cycleNext()) }
                }
                ChoiceItem("Card spacing", (look.cardSpacing ?: dev.nitrate.core.pages.CardSpacing.NORMAL).label) {
                    set { it.copy(cardSpacing = (it.cardSpacing ?: dev.nitrate.core.pages.CardSpacing.NORMAL).cycleNext()) }
                }
            }
            if (arrangement == PageArrangement.FULL_ART) {
                ChoiceItem("Up/Down", if (look.upDownAlong == true) "Along the row, then rows" else "Between rows") {
                    set { it.copy(upDownAlong = it.upDownAlong != true) }
                }
            }
            if (arrangement.oneAtATime) {
                ChoiceItem("Row position", (look.rowPlace ?: RowPlace.BOTTOM).label) { set { it.copy(rowPlace = (it.rowPlace ?: RowPlace.BOTTOM).cycleNext()) } }
                ChoiceItem("Row length", (look.rowExtent ?: RowExtent.FULL).label) { set { it.copy(rowExtent = (it.rowExtent ?: RowExtent.FULL).cycleNext()) } }
            }
            ListItem(
                selected = false, onClick = { container.changePrefs { PageLooks.useEverywhere(it, owner) } },
                headlineContent = { Text("Use this layout on every page") },
                supportingContent = { Text("Home, every library and your own pages all take it") },
            )
            if (own) ListItem(
                selected = false, onClick = { container.changePrefs { PageLooks.reset(it, owner) } },
                headlineContent = { Text("Same as every page") },
                supportingContent = { Text("Drop this page's own layout") },
            )
        }
    }
}

private inline fun <reified E : Enum<E>> E.cycleNext(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }
