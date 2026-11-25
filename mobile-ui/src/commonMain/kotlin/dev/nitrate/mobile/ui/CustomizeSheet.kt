package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowSize
import dev.nitrate.core.rows.RowStyle

/** The page a row is on, for the Customize sheet: its name, its rows, and the library it's in. */
class PagePanel(val title: String, val vm: RowsViewModel, val libraryId: String?)

/** Provided by each page of rows (Home, a library, your own pages), so a row's header opens Customize. */
val LocalPagePanel = compositionLocalOf<PagePanel?> { null }

/**
 * A library's full list showing (its All tab, or the list of a library without Recommended), for the
 * Customize sheet: its [title], the library, and the list's own options (its view among them).
 */
class ListPanel(val title: String, val libraryId: String, val vm: dev.nitrate.android.viewmodel.BrowseViewModel)

/**
 * Customize, from a row's header: This row (its look, where it sits, its name, hiding it) and This
 * page (its rows). From a library's full list ([row] and [panel] null, [list] set: the top bar's
 * pencil) only This page, with the list's View (Posters, Artwork, List) and Full list.
 * The page shows the changes as they're made.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomizeSheet(row: MediaRow?, panel: PagePanel?, actions: AppActions, onDismiss: () -> Unit, list: ListPanel? = null) {
    if (row != null && panel != null) RowCustomizeSheet(row, panel, actions, onDismiss)
    else ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Customize", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Text(list?.title ?: panel?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ListSections(list, libraryPage = list != null || panel?.libraryId != null, actions, divider = false)
            }
        }
    }
}

/**
 * This page's sections for a library's full list: View (the list showing, saved with its sort and
 * filters under its own key, as the bar's View pill once did) and Full list (Fixed or Scrolling grid,
 * for every library).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListSections(list: ListPanel?, libraryPage: Boolean, actions: AppActions, divider: Boolean) {
    if (list != null) {
        val state by list.vm.state.collectAsStateWithLifecycle()
        // TV-only views (Bouncing box…) show as Posters here, as the list draws them.
        val view = state.options.view.let { if (it.tvOnly) dev.nitrate.core.pages.BrowseView.GRID else it }
        if (divider) HorizontalDivider()
        Text("View", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.pages.BrowseView.entries.filterNot { it.tvOnly }.forEach { v ->
                FilterChip(selected = view == v, onClick = { list.vm.update { it.copy(view = v) } }, label = { Text(v.label) })
            }
        }
        Text("How this list shows its titles: posters, wide artwork, or lines with details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // A library's page: how its full list (All) is laid out, the same choice as Settings › Display.
    if (libraryPage) {
        val prefs = LocalDisplayPrefs.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        if (divider || list != null) HorizontalDivider()
        Text("Full list", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.nitrate.core.pages.GridLayout.entries.forEach { g ->
                FilterChip(
                    selected = prefs.phoneGridLayout == g,
                    onClick = { actions.server.userId?.let { u -> scope.launch { actions.container.displaySettings.savePrefs(u, prefs.copy(phoneGridLayout = g)) } } },
                    label = { Text(g.label) },
                )
            }
        }
        Text(prefs.phoneGridLayout.phoneDetail + ". For every library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RowCustomizeSheet(row: MediaRow, panel: PagePanel, actions: AppActions, onDismiss: () -> Unit) {
    val vm = panel.vm
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val display = (overrides[row.key] ?: row.defaultDisplay).normalized()
    val entry = entries.firstOrNull { it.rowKey == row.key }
    fun save(d: RowDisplay) = vm.save(row, d)
    var tab by remember { mutableIntStateOf(0) }
    var colouring by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingRows by remember { mutableStateOf(false) }
    if (colouring) InkColourDialog(display, onSave = { save(it); colouring = false }, onDismiss = { colouring = false })
    if (editingRows) PageEditor(panel.title, vm, actions, libraryId = panel.libraryId, onDone = { editingRows = false })
    if (renaming && entry != null) {
        var name by remember { mutableStateOf(vm.titleOf(entry)) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename row") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(entry.id, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Customize", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Text(if (tab == 0) row.title else panel.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("This row") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("This page") })
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    Text("Style", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowStyle.choices.forEach { s -> FilterChip(selected = display.style == s, onClick = { save(display.copy(style = s, cards = true)) }, label = { Text(s.label) }) }
                    }
                    Text("Size", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowSize.entries.forEach { s -> FilterChip(selected = display.size == s, onClick = { save(display.copy(size = s)) }, label = { Text(s.label) }) }
                    }
                    if (display.style == RowStyle.WIDE) SwitchLine("Show cards", "Off: one title at a time filling the screen, swiped through", display.cards) { save(display.copy(cards = it)) }
                    if (display.style == RowStyle.POSTER || (display.style == RowStyle.WIDE && display.cards)) {
                        SwitchLine("Titles under pictures", null, display.showTitles) { save(display.copy(showTitles = it)) }
                    }
                    if (display.style == RowStyle.CRESSIDA) {
                        Text("Ink", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dev.nitrate.core.rows.CressidaInk.entries.forEach { i -> FilterChip(selected = display.ink == i, onClick = { save(display.copy(ink = i)) }, label = { Text(i.label) }) }
                        }
                        TextButton(onClick = { colouring = true }) { Text("Ink colour: ${display.inkColourLabel()}") }
                    }
                    if (entry != null) {
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.move(entry.id, -1) }) { Text("Move up") }
                            TextButton(onClick = { vm.move(entry.id, 1) }) { Text("Move down") }
                            TextButton(onClick = { renaming = true }) { Text("Rename") }
                        }
                        TextButton(onClick = { vm.setHidden(entry.id, true); onDismiss() }) { Text("Hide this row") }
                    }
                    if (display != row.defaultDisplay.normalized()) TextButton(onClick = { save(row.defaultDisplay.normalized()) }) { Text("Reset this row's look") }
                } else {
                    Text("Order, hide, rename and add your own rows (from filters, collections or playlists).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { editingRows = true }) { Text("Edit rows") }
                    ListSections(null, libraryPage = panel.libraryId != null, actions, divider = true)
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.labelled(title))
    }
}
